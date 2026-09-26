package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.operator.{KeycloakAdmin, KeycloakStack}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.{DockerImageName, MountableFile}

import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The shipped realm against a real Keycloak.
 *
 * The one test that can catch the realm and the verifier disagreeing: it starts the pinned image
 * with the realm out of the same import resource the operator applies and docker-compose reads,
 * creates a user and clients through the admin API the way an administrator would in the console,
 * and reads the claims real tokens carry. Research R5 (audience by client scope), R6 (device grant,
 * offline refresh) and "verify at implementation" items 3–5 are pinned here.
 *
 * Needs Docker, like every integration suite.
 */
class KeycloakRealmSuite extends munit.FunSuite:

  override val munitTimeout = 6.minutes

  private var keycloak: KeycloakContainer = null
  private var admin: KeycloakAdmin        = null

  private val Secret = "test-secret-not-a-secret"

  override def beforeAll(): Unit =
    val realm = Files.createTempFile("ankka-realm", ".json")
    Files.writeString(realm, KeycloakStack.realm(repoRoot))
    keycloak = new KeycloakContainer(DockerImageName.parse(KeycloakAdmin.image))
      .withCommand("start-dev", "--import-realm")
      .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
      .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
      .withCopyFileToContainer(
        // World-readable: a temp file is 0600, and Keycloak runs unprivileged in the image.
        MountableFile.forHostPath(realm, 0x1a4),
        "/opt/keycloak/data/import/ankka-realm.json"
      )
      .withExposedPorts(8080)
      .waitingFor(
        Wait
          .forHttp("/realms/ankka")
          .forPort(8080)
          .forStatusCode(200)
          .withStartupTimeout(Duration.ofMinutes(4))
      )
    keycloak.start()
    admin = KeycloakAdmin(
      s"http://${keycloak.getHost}:${keycloak.getMappedPort(8080)}",
      "ankka",
      "admin",
      "admin"
    )

  override def afterAll(): Unit = if keycloak != null then keycloak.stop()

  test("the realm import took: registration is off and platform-admin exists") {
    val realm = admin.admin("GET", "/admin/realms/ankka", None)
    assertEquals(realm.statusCode, 200)
    assert(realm.body.contains("\"registrationAllowed\":false"))
    assertEquals(
      admin.admin("GET", "/admin/realms/ankka/roles/platform-admin", None).statusCode,
      200
    )
  }

  test("the device authorization grant is enabled on ankka-cli (research R6)") {
    val response = admin.deviceAuthorization("ankka-cli", "openid offline_access")
    assertEquals(response.statusCode, 200, response.body)
    for field <- Seq("device_code", "user_code", "verification_uri", "expires_in", "interval") do
      assert(response.body.contains(s"\"$field\""), s"no $field in ${response.body}")
  }

  test("a user's token carries the audience, email, verification, name and realm roles (R5)") {
    admin.createUser(
      "alice",
      "alice@example.test",
      "alice",
      emailVerified = true,
      Seq("platform-admin")
    )
    admin.createClient("test-direct", Secret, serviceAccounts = false, directAccessGrants = true)
    val token =
      admin.passwordGrant("test-direct", Some(Secret), "alice", "alice", "openid offline_access")
    val claims = KeycloakAdmin.claims(token.get("access_token").asText())

    def text(name: String): Option[String] = Option(claims.get(name)).map(_.asText())
    assert(
      KeycloakAdmin.audiences(claims).contains("ankka-controlplane"),
      s"aud was ${claims.get("aud")}"
    )
    assertEquals(text("email"), Some("alice@example.test"), claims.toString)
    assertEquals(text("email_verified"), Some("true"), claims.toString)
    assertEquals(text("typ"), Some("Bearer"), claims.toString)
    assert(text("iss").exists(_.endsWith("/realms/ankka")), claims.toString)
    assert(text("sub").exists(_.nonEmpty), claims.toString)
    // The realm declares its own client scopes, so Keycloak's built-in `profile` does not exist and
    // cannot put these on a token: `ankka-controlplane` maps them itself, as it does `sub`.
    assertEquals(text("name"), Some("alice Test"), claims.toString)
    assertEquals(text("preferred_username"), Some("alice"), claims.toString)
    val roles = Option(claims.get("realm_access"))
      .flatMap(node => Option(node.get("roles")))
      .map(_.elements().asScala.map(_.asText()).toSet)
      .getOrElse(Set.empty)
    assert(roles.contains("platform-admin"), s"roles were $roles in $claims")
    // Offline tokens report no refresh expiry — that is what makes a CLI login survive an idle day.
    assertEquals(token.get("refresh_expires_in").asInt(), 0)
  }

  test("an unverified email is reported as unverified") {
    admin.createUser("bob", "bob@example.test", "bob", emailVerified = false)
    admin.createClient("test-direct", Secret, serviceAccounts = false, directAccessGrants = true)
    val token  = admin.passwordGrant("test-direct", Some(Secret), "bob", "bob")
    val claims = KeycloakAdmin.claims(token.get("access_token").asText())
    assertEquals(claims.get("email").asText(), "bob@example.test")
    assertEquals(claims.get("email_verified").asBoolean(), false)
  }

  test(
    "a service account with a verified email carries it in a client-credentials token (item 5)"
  ) {
    val internalId =
      admin.createClient("ci-deployer", Secret, serviceAccounts = true, directAccessGrants = false)
    val user = admin.serviceAccountUserId(internalId)
    admin.setEmail(user, "ci@example.test", verified = true)
    val token  = admin.clientCredentials("ci-deployer", Secret, "openid")
    val claims = KeycloakAdmin.claims(token.get("access_token").asText())
    assertEquals(
      Option(claims.get("email")).map(_.asText()),
      Some("ci@example.test"),
      claims.toString
    )
    assertEquals(
      Option(claims.get("email_verified")).map(_.asBoolean()),
      Some(true),
      claims.toString
    )
    assert(
      KeycloakAdmin.audiences(claims).contains("ankka-controlplane"),
      s"aud was ${claims.get("aud")} in $claims"
    )
    assert(claims.get("sub").asText().nonEmpty, claims.toString)
  }

  test(
    "a CI client's token is accepted by a control plane, and its verified email claims an invitation (US4)"
  ) {
    // The control plane, in this JVM, verifying against the *real* realm's keys — the only
    // place real tokens meet the real verifier. Alice invites the client's email; its first
    // refused write claims the membership; the apply it then performs is attributed to it.
    val auth = com.thinkmorestupidless.ankka.controlplane.auth.AuthConfig(
      issuer = admin.realmUrl,
      jwksUrl = admin.jwksUrl,
      audience = "ankka-controlplane",
      clientId = "ankka-cli",
      realmHint = "ankka",
      clockSkew = scala.concurrent.duration.Duration(60, "s")
    )
    val server = com.thinkmorestupidless.ankka.http.HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(ControlPlane.aclFor(auth), auth = Some(auth))*
    )
    val testKit = com.thinkmorestupidless.ankka.testkit.AnkkaTestKit.start(
      ControlPlane.components,
      Seq(com.thinkmorestupidless.ankka.runtime.ProjectionRuntime(), server)
    )
    try
      val base = s"http://127.0.0.1:${server.boundPort.get}"
      val http = java.net.http.HttpClient.newHttpClient()
      def send(
          method: String,
          path: String,
          token: String,
          body: Option[String] = None
      ): (Int, String) =
        val builder = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(base + path))
          .header("Authorization", s"Bearer $token")
        body.foreach(_ => builder.header("Content-Type", "application/json"))
        builder.method(
          method,
          body.fold(java.net.http.HttpRequest.BodyPublishers.noBody())(
            java.net.http.HttpRequest.BodyPublishers.ofString
          )
        )
        val response =
          http.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        (response.statusCode, response.body)

      admin.createUser("alice", "alice@example.test", "alice", emailVerified = true)
      admin.createClient("test-direct", Secret, serviceAccounts = false, directAccessGrants = true)
      val alice = admin
        .passwordGrant("test-direct", Some(Secret), "alice", "alice")
        .get("access_token")
        .asText()
      val ciId = admin.createClient(
        "ci-deployer",
        Secret,
        serviceAccounts = true,
        directAccessGrants = false
      )
      admin.setEmail(admin.serviceAccountUserId(ciId), "ci@example.test", verified = true)
      val ci        = admin.clientCredentials("ci-deployer", Secret).get("access_token").asText()
      val ciSubject = KeycloakAdmin.claims(ci).get("sub").asText()

      assertEquals(
        send("GET", "/auth/whoami", ci)._1,
        200,
        "a real client-credentials token verifies"
      )
      assertEquals(send("POST", "/organizations/acme", alice, Some("""{"name":"Acme"}"""))._1, 204)
      assertEquals(
        send(
          "POST",
          "/projects/checkout",
          alice,
          Some("""{"name":"Checkout","organizationId":"acme"}""")
        )._1,
        204
      )
      val descriptor = """{"name":"cart","service":{"image":"cart:1.0"}}"""
      assertEquals(
        send("PUT", "/services/checkout/cart", ci, Some(descriptor))._1,
        404,
        "not a member yet"
      )
      assertEquals(
        send(
          "POST",
          "/organizations/acme/members",
          alice,
          Some("""{"email":"ci@example.test"}""")
        )._1,
        204
      )
      assertEquals(
        send("PUT", "/services/checkout/cart", ci, Some(descriptor))._1,
        200,
        "claimed on the refused write, then applied"
      )
      val (_, history) = send("GET", "/services/checkout/cart/history", alice)
      assert(history.contains(s"\"subject\":\"$ciSubject\""), history)
      assertEquals(send("GET", "/organizations/acme", "not-a-jwt")._1, 401)
    finally testKit.stop()
  }

  test("the discovery document names the device and token endpoints") {
    val response = admin.admin("GET", "/realms/ankka/.well-known/openid-configuration", None)
    assertEquals(response.statusCode, 200)
    assert(response.body.contains("\"device_authorization_endpoint\""))
    assert(response.body.contains("\"token_endpoint\""))
    assert(response.body.contains("\"jwks_uri\""))
  }

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

/** testcontainers' self-typed `GenericContainer` needs a concrete subclass for Scala 3 to infer. */
final class KeycloakContainer(image: DockerImageName)
    extends GenericContainer[KeycloakContainer](image)
