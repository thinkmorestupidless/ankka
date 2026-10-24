package com.thinkmorestupidless.ankka.mcp.features

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import com.thinkmorestupidless.ankka.mcp.signin.{IssuerClient, SignInRecord, SignInRecordEntity}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `features/mcp-endpoints/brokered-sign-in.feature`, every scenario, on the kit. The issuer
 * `customers` is a `TestIssuer` serving the authorization code flow on loopback, and the MCP client
 * is a `BrokerClient` that follows every redirect as a browser would. Every request reaches the
 * service through the gateway, so the broker names itself https.
 */
class BrokeredSignInFeatures
    extends McpEndpointSteps("../../features/mcp-endpoints/brokered-sign-in.feature"):
  import Fixtures.*

  private var signingIn: Option[BrokerClient] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    signingIn = None

  private def port: Int = server.get.boundPort.get

  /** The scenario's MCP client, at the orders' service; started on first use. */
  private def mcpClient: BrokerClient = signingIn.getOrElse {
    running
    val created = BrokerClient(s"http://127.0.0.1:$port", fromOutside)
    signingIn = Some(created)
    created
  }

  private def signedIn: BrokerClient =
    signingIn.filter(_.tokens.isDefined).getOrElse(fail("no MCP client signed in"))

  /** A token the broker minted is a secret: the scan after the scenario looks for it everywhere. */
  private def remember(c: BrokerClient): Unit =
    (c.accessToken ++ c.refreshToken).foreach(minted += _)

  private def bearer(c: BrokerClient) =
    "Authorization" -> s"Bearer ${c.accessToken.getOrElse(fail("no token was minted"))}"

  Given("an MCP endpoint of {string} whose tool is {string} and whose ACL admits tokens") {
    (service: String, tool: String) =>
      assertEquals((service, tool), ("orders", "list_orders"))
      ordersAcl = tokensAcl
  }

  Given("{string} opts the MCP endpoint into brokered sign-in") { (service: String) =>
    assertEquals(service, "orders")
    brokeredSignIn = true
  }

  Then("the resource metadata names the sign-in broker of {string}, at a hostname of {string}") {
    (service: String, host: String) =>
      assertEquals((service, host), ("orders", "orders"))
      // The host the client reached the service by: locally, its loopback address.
      assertEquals(authorizationServers, Vector(s"https://127.0.0.1:$port/_ankka/sign-in/mcp"))
  }

  Then("the resource metadata does not name the issuer {string}") { (name: String) =>
    assertEquals(name, issuer.name)
    assert(!authorizationServers.contains(issuer.issuer), authorizationServers)
  }

  When("an MCP client registers itself with the sign-in broker of {string} and asks to sign in") {
    (service: String) =>
      assertEquals(service, "orders")
      mcpClient.register(): Unit
      mcpClient.authorize(): Unit
  }

  Then("the MCP client is registered") { () =>
    val answer = mcpClient.registered.getOrElse(fail("the MCP client did not register"))
    assertEquals(answer.status, 201, answer.body)
    assert(mcpClient.clientId.exists(_.nonEmpty), answer.body)
  }

  Then("the person is sent to the issuer {string} to sign in") { (name: String) =>
    assertEquals(name, issuer.name)
    val sentTo = mcpClient.firstRedirect.getOrElse(fail("the broker sent the person nowhere"))
    assert(sentTo.startsWith(s"${issuer.base}/authorize?"), sentTo)
    val query = IssuerClient.form(URI.create(sentTo).getRawQuery)
    assertEquals(query.get("client_id"), Some(BrokerClientId))
    assertEquals(
      query.get("redirect_uri"),
      Some(s"https://127.0.0.1:$port/_ankka/sign-in/mcp/callback")
    )
    assertEquals(query.get("code_challenge_method"), Some("S256"))
  }

  Given(
    "an MCP client has signed in through the sign-in broker of {string} as the person {string} of the issuer {string}"
  ) { (service: String, person: String, name: String) =>
    assertEquals((service, name), ("orders", issuer.name))
    issuer.signingIn = person
    val tokens = mcpClient.signIn()
    remember(mcpClient)
    assert(mcpClient.accessToken.isDefined, s"no token: ${mcpClient.sentBack} $tokens")
  }

  When("the MCP client calls {string} with the token the sign-in broker minted") { (tool: String) =>
    rpc(
      "tools/call",
      Json.obj("name" -> Json.str(tool), "arguments" -> Json.obj()),
      fromOutside,
      bearer(signedIn)
    ): Unit
  }

  Given(
    "a service {string} that lists the issuer {string} with the audience {string}, with an MCP endpoint whose ACL admits tokens"
  ) { (service: String, name: String, audience: String) =>
    assertEquals((service, name, audience), ("billing", issuer.name, issuer.defaultAudience))
    val acl     = Oidc.authenticate(OidcConfig(Vector(issuer.asIssuer())))
    val started = HttpServer.at("127.0.0.1", 0)(_ => BillingMcp(acl))
    val k = AnkkaTestKit.start(McpRuntime.descriptors, Seq(started, McpRuntime(clock, 1.minute)))
    officeKit = Some(k -> started)
  }

  When(
    "the MCP client calls a tool of {string} with the token the sign-in broker of {string} minted"
  ) { (service: String, broker: String) =>
    assertEquals((service, broker), ("billing", "orders"))
    val (_, billing) = officeKit.getOrElse(fail("billing is not running"))
    val call         = Json.obj("name" -> Json.str("file_invoice"), "arguments" -> Json.obj())
    rpcTo(
      s"http://127.0.0.1:${billing.boundPort.get}/mcp",
      "tools/call",
      call,
      fromOutside,
      bearer(signedIn)
    ): Unit
  }

  Given("the issuer {string} refuses to sign in the person {string}") {
    (name: String, person: String) =>
      assertEquals(name, issuer.name)
      issuer.refusing.add(person): Unit
  }

  When("an MCP client asks the sign-in broker of {string} to sign in as {string}") {
    (service: String, person: String) =>
      assertEquals(service, "orders")
      issuer.signingIn = person
      mcpClient.signIn(): Unit
      remember(mcpClient)
  }

  Then("the MCP client is told the sign-in failed") { () =>
    val back  = mcpClient.sentBack.getOrElse(fail("the MCP client was never sent back"))
    val query = IssuerClient.form(URI.create(back).getRawQuery)
    assertEquals(query.get("error"), Some("access_denied"), back)
    assertEquals(query.get("state"), Some(mcpClient.clientState), back)
  }

  Then("no token is minted") { () =>
    val back = mcpClient.sentBack.getOrElse(fail("the MCP client was never sent back"))
    assert(!IssuerClient.form(URI.create(back).getRawQuery).contains("code"), back)
    assertEquals(mcpClient.tokens, None)
    // Nor can the client get one by guessing: the broker holds no code for this sign-in.
    val guessed = mcpClient.exchange("anything.at-all")
    assertEquals(guessed.status, 400, guessed.body)
    assert(guessed.body.contains("invalid_grant"), guessed.body)
  }

  When("an MCP client asks the sign-in broker of {string} to register it") { (service: String) =>
    assertEquals(service, "orders")
    mcpClient.register(): Unit
  }

  Then("the MCP client finds no sign-in broker") { () =>
    assertEquals(mcpClient.registered.map(_.status), Some(404), mcpClient.registered.toString)
    val discovery = mcpClient.get(
      s"${mcpClient.origin}/.well-known/oauth-authorization-server/_ankka/sign-in/mcp"
    )
    assertEquals(discovery.status, 404, discovery.body)
  }

  Then("the resource metadata of {string} names the issuer {string} and no sign-in broker") {
    (service: String, name: String) =>
      assertEquals((service, name), ("orders", issuer.name))
      readMetadata()
      assertEquals(authorizationServers, Vector(issuer.issuer))
  }

  Then("{string} holds no password of {string}") { (service: String, person: String) =>
    assertEquals(service, "orders")
    val c = signedIn
    // Nothing the client or the issuer was sent or answered names a password.
    assert(
      !issuer.asked.asScala.exists(_._2.exists(_.toLowerCase.contains("password"))),
      issuer.asked
    )
    // The grant is gone once its code was taken; the refresh token holds who signed in, and no more.
    val code               = IssuerClient.form(URI.create(c.sentBack.get).getRawQuery)("code")
    val grant              = SignInRecordEntity.grantId(code.takeWhile(_ != '.'))
    def record(id: String) =
      running.componentClient.forKeyValueEntity(EntityId(id)).call(SignInRecordEntity.get).invoke()
    assertEquals(record(grant), SignInRecord.Absent)
    record(SignInRecordEntity.refreshId(IssuerClient.digest(c.refreshToken.get))) match
      case refresh: SignInRecord.RefreshToken =>
        assertEquals(refresh.signedIn.subject, person)
        assert(!refresh.toString.toLowerCase.contains("password"), refresh)
      case other => fail(s"no refresh token is held: $other")
    // The one secret the broker keeps is its own signing key, under the key's id.
    val keys = Json
      .parse(c.get(s"${c.broker}/jwks.json").body)
      .toOption
      .flatMap(_("keys"))
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
    val kids = keys.flatMap(_("kid")).flatMap(_.asString)
    assert(kids.nonEmpty, keys)
    kids.foreach(kid => assert(running.secrets.get(s"ankka-sign-in-key-$kid").isDefined, kid))
  }

  Then("the issuer {string} was asked for nothing but to sign {string} in") {
    (name: String, person: String) =>
      assertEquals(name, issuer.name)
      val asked = issuer.asked.asScala.toVector.map(_._1).filterNot(_ == "discovery")
      assertEquals(asked, Vector("authorize", "token"))
      assertEquals(issuer.authorizations.size, 1)
      assertEquals(issuer.signingIn, person)
  }

  // ── R19.7, which no scenario names: an ID token is believed only when the issuer signed it ──

  test(
    "an ID token signed by a key the service's issuer does not publish gets no token from the sign-in broker"
  ) {
    val served = com.thinkmorestupidless.ankka.auth.oidc.TestIssuer.serving("customers", "shop")
    served.registeredClients.put(BrokerClientId, None): Unit
    customers = Some(served)
    ordering = true
    ordersAcl = tokensAcl
    brokeredSignIn = true
    served.signIdTokensWithForeignKey = true
    assertEquals(mcpClient.signIn(), None)
    val back = IssuerClient.form(URI.create(mcpClient.sentBack.get).getRawQuery)
    assertEquals(back.get("error"), Some("access_denied"), back)
    assertEquals(back.get("code"), None)
  }
