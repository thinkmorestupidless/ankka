package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig, TestIssuer}
import com.thinkmorestupidless.ankka.http.{Caller, LocalCallers}
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.bridge.TestAcls
import com.thinkmorestupidless.ankka.javasdk.cart.{CartEndpoint, CartState, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.mcp.{ClientKeyRequest, Expiry}
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.mcp.features.BrokerClient
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Java MCP endpoints, served by the Scala MCP endpoint: tools, client keys and brokered sign-in.
 */
class McpSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit       = null
  private var signingIn: AnkkaTestKit = null
  private var issuer: TestIssuer      = null
  private val http                    = HttpClient.newHttpClient()
  private val BrokerClientId          = "shop-broker"

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka
        .service("shop")
        .register(classOf[ShoppingCart])
        .withMcpEndpoint(classOf[CartTools])
        .withMcpEndpoint(classOf[KeyedTools])
        .withEndpoint(classOf[CartEndpoint])
        .withMcpEndpoint(classOf[RouteTools])
    )
    issuer = TestIssuer.serving("customers", "shop")
    issuer.registeredClients.put(BrokerClientId, None)
    TestAcls.hold(
      Oidc.authenticate(
        OidcConfig(Vector(issuer.asIssuer().copy(clientId = Some(BrokerClientId))))
      )
    )
    signingIn = AnkkaTestKit.start(Ankka.service("desk").withMcpEndpoint(classOf[SignedInTools]))

  override def afterAll(): Unit =
    try if signingIn != null then signingIn.close()
    finally
      try if kit != null then kit.close()
      finally if issuer != null then issuer.stop()

  /** The status and body of one JSON-RPC request. */
  private def send(
      on: AnkkaTestKit,
      path: String,
      method: String,
      params: Json,
      headers: (String, String)*
  ): (Int, String) =
    val message = Json.obj(
      "jsonrpc" -> Json.str("2.0"),
      "id"      -> Json.num(1),
      "method"  -> Json.str(method),
      "params"  -> params
    )
    val request = HttpRequest
      .newBuilder(URI.create(s"${on.http()}$path"))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .header("MCP-Protocol-Version", "2025-06-18")
    headers.foreach((k, v) => request.header(k, v))
    val response = http.send(
      request.POST(HttpRequest.BodyPublishers.ofString(message.render)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    response.statusCode() -> response.body()

  private def rpc(
      on: AnkkaTestKit,
      path: String,
      method: String,
      params: Json,
      headers: (String, String)*
  ): Json =
    val (status, body) = send(on, path, method, params, headers*)
    Json.parse(body).fold(e => fail(s"$status: $e: $body"), identity)

  private def call(
      on: AnkkaTestKit,
      path: String,
      tool: String,
      arguments: Json,
      headers: (String, String)*
  ) =
    rpc(
      on,
      path,
      "tools/call",
      Json.obj("name" -> Json.str(tool), "arguments" -> arguments),
      headers*
    )

  private def text(answer: Json): String =
    answer("result")
      .flatMap(_("content"))
      .flatMap(_.asArray)
      .flatMap(_.headOption)
      .flatMap(_("text"))
      .flatMap(_.asString)
      .getOrElse(fail(s"no text in ${answer.render}"))

  private def isError(answer: Json): Boolean =
    answer("result").flatMap(_("isError")).contains(Json.Bool(true))

  test("tools/list lists every tool with its description and its arguments' schema") {
    val listed = rpc(kit, "/mcp", "tools/list", Json.obj())
    val tools =
      listed("result").flatMap(_("tools")).flatMap(_.asArray).getOrElse(fail(listed.render))
    val byName = tools.map(t => t("name").flatMap(_.asString).get -> t).toMap
    assertEquals(
      byName.keySet,
      Set("add_item", "remove_item", "get_cart", "greet", "who", "refuse")
    )
    assertEquals(
      byName("add_item")("description").flatMap(_.asString),
      Some("Adds an item to a cart")
    )
    val schema = byName("add_item")("inputSchema").get
    val properties =
      schema("properties").collect { case Json.Obj(ps) => ps.keySet }.getOrElse(Set.empty)
    assertEquals(properties, Set("cartId", "productId", "name", "quantity"))
    val required = schema("required").flatMap(_.asArray).get.flatMap(_.asString).toSet
    assertEquals(required, Set("cartId", "productId", "name", "quantity"))
    val greet = byName("greet")("inputSchema").get("properties").get
    assertEquals(greet("times").flatMap(_("type")), Some(Json.str("integer")))
  }

  test("a tool built from a record of arguments answers what its handler returns") {
    val answer =
      call(kit, "/mcp", "greet", Json.obj("name" -> Json.str("ada"), "times" -> Json.num(2)))
    assertEquals(text(answer), "hello ada hello ada")
  }

  test("a tool naming a command calls it, and one naming a query reads what it did") {
    val added = call(
      kit,
      "/mcp",
      "add_item",
      Json.obj(
        "cartId"    -> Json.str("c1"),
        "productId" -> Json.str("p1"),
        "name"      -> Json.str("socks"),
        "quantity"  -> Json.num(3)
      )
    )
    assertEquals(text(added), "done", added.render)
    val cart = call(kit, "/mcp", "get_cart", Json.obj("cartId" -> Json.str("c1")))
    val items =
      cart("result").flatMap(_("structuredContent")).flatMap(_("items")).flatMap(_.asArray)
    assertEquals(items.map(_.size), Some(1), cart.render)
    // And the entity holds it, read through the Java component client.
    val state: CartState =
      kit.componentClient().forEventSourcedEntity("c1").call(KindRefs.getCart).invoke()
    assertEquals(state.items.asScala.map(_.quantity).toVector, Vector(3))
  }

  test("a command whose input is not a record takes it under the argument it was named") {
    call(
      kit,
      "/mcp",
      "add_item",
      Json.obj(
        "cartId"    -> Json.str("c2"),
        "productId" -> Json.str("p2"),
        "name"      -> Json.str("hat"),
        "quantity"  -> Json.num(1)
      )
    ): Unit
    val removed =
      call(
        kit,
        "/mcp",
        "remove_item",
        Json.obj("cartId" -> Json.str("c2"), "productId" -> Json.str("p2"))
      )
    assertEquals(text(removed), "done", removed.render)
  }

  test("an argument of the wrong type is refused, naming the parameter, and no handler runs") {
    val answer = call(
      kit,
      "/mcp",
      "add_item",
      Json.obj(
        "cartId"    -> Json.str("c3"),
        "productId" -> Json.str("p3"),
        "name"      -> Json.str("scarf"),
        "quantity"  -> Json.str("lots")
      )
    )
    val error = answer("error").getOrElse(fail(answer.render))
    assertEquals(error("code"), Some(Json.num(-32602)))
    assertEquals(
      error("message").flatMap(_.asString),
      Some("parameter 'quantity': must be an integer, not a string")
    )
    val untouched =
      kit.componentClient().forEventSourcedEntity("c3").call(KindRefs.getCart).invoke()
    assertEquals(untouched.items.size, 0)
  }

  test("a tool naming a route calls it with its path parameters and its body") {
    val item = Json.obj(
      "productId" -> Json.str("p9"),
      "name"      -> Json.str("gloves"),
      "quantity"  -> Json.num(2)
    )
    val added =
      call(kit, "/routes", "add_by_route", Json.obj("id" -> Json.str("c9"), "body" -> item))
    assert(!isError(added), added.render)
    val read = call(kit, "/routes", "read_cart", Json.obj("id" -> Json.str("c9")))
    assert(text(read).contains("gloves"), read.render)
    val state: CartState =
      kit.componentClient().forEventSourcedEntity("c9").call(KindRefs.getCart).invoke()
    assertEquals(state.items.asScala.map(_.name).toVector, Vector("gloves"))
  }

  test("a handler's refusal is a tool error carrying the problem's detail") {
    val answer = call(kit, "/mcp", "refuse", Json.obj())
    assert(isError(answer), answer.render)
    assertEquals(text(answer), "not today")
  }

  test(
    "a client key is issued, admitted at its endpoint as its subject, and refused once revoked"
  ) {
    val whoami = Json.obj("name" -> Json.str("whoami"), "arguments" -> Json.obj())
    assertEquals(send(kit, "/keyed", "tools/call", whoami)._1, 401)
    val issued = kit
      .clientKeys()
      .issue(ClientKeyRequest.of("grace", Expiry.after(Duration.ofHours(1))).atEndpoint("/keyed"))
    val bearer = "Authorization" -> s"Bearer ${issued.clientKey}"
    assertEquals(text(call(kit, "/keyed", "whoami", Json.obj(), bearer)), "grace")
    assertEquals(kit.clientKeys().list().asScala.map(_.subject).toVector, Vector("grace"))
    kit.clientKeys().revoke(issued.id)
    assertEquals(send(kit, "/keyed", "tools/call", whoami, bearer)._1, 401)
  }

  test("a key scoped to some tools lists and calls only those") {
    val issued = kit
      .clientKeys()
      .issue(
        ClientKeyRequest
          .of("lin", Expiry.never())
          .atEndpoint("/keyed")
          .scopedTo("whoami")
      )
    val bearer = "Authorization" -> s"Bearer ${issued.clientKey}"
    val listed = rpc(kit, "/keyed", "tools/list", Json.obj(), bearer)
    val names  = listed("result").flatMap(_("tools")).flatMap(_.asArray).get.flatMap(_("name"))
    assertEquals(names, Vector(Json.str("whoami")))
    val other = call(kit, "/keyed", "other", Json.obj(), bearer)
    assertEquals(
      other("error").flatMap(_("message")).flatMap(_.asString),
      Some("tool not available: other")
    )
  }

  test("issuing a key with no endpoint, when the service has two, is refused naming them") {
    val e = intercept[CommandException](kit.clientKeys().issue("x", Expiry.never()))
    assert(e.getMessage.contains("/mcp") && e.getMessage.contains("/keyed"), e.getMessage)
  }

  test("a person signs in through the service's own broker, and calls a tool as themselves") {
    val outside = LocalCallers.header(Caller.Gateway)
    val client  = BrokerClient(signingIn.http().toString.stripSuffix("/"), outside)
    issuer.signingIn = "ada"
    client.signIn(): Unit
    val token = client.accessToken.getOrElse(fail(s"no token: ${client.sentBack}"))
    val answer =
      call(signingIn, "/mcp", "whoami", Json.obj(), outside, "Authorization" -> s"Bearer $token")
    assertEquals(text(answer), "ada", answer.render)
  }

  test("an MCP endpoint that declares no tool is refused at start") {
    val e = intercept[ServiceProblems](
      Ankka.service("empty").withMcpEndpoint(classOf[NoTools]).start()
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("declares no tool"), messages)
  }
