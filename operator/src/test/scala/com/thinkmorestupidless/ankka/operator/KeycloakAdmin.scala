package com.thinkmorestupidless.ankka.operator

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * A minimal client for Keycloak's admin REST API and its token endpoint, for tests only.
 *
 * The *platform* never holds an admin credential (feature 008, research R9): the control plane
 * verifies tokens and reads its own state, and that is all. Tests do hold one — the bootstrap admin
 * of a throwaway instance — because a test that mints real tokens is the only thing that can catch
 * the shipped realm and the verifier disagreeing about a claim.
 *
 * In the operator's test scope so the control plane's suites and the k3s stack share it, like
 * `ClusterImages` and `GatewayStack`.
 */
final class KeycloakAdmin(
    baseUrl: String,
    realm: String,
    adminUser: String,
    adminPassword: String,
    http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
):

  private val mapper = new ObjectMapper()

  def realmUrl: String = s"$baseUrl/realms/$realm"

  // ── admin API ──────────────────────────────────────────────────────────────

  def adminToken(): String =
    val response = form(
      s"$baseUrl/realms/master/protocol/openid-connect/token",
      Map(
        "client_id"  -> "admin-cli",
        "grant_type" -> "password",
        "username"   -> adminUser,
        "password"   -> adminPassword
      )
    )
    require(response.statusCode == 200, s"admin token: ${response.statusCode} ${response.body}")
    mapper.readTree(response.body).get("access_token").asText()

  /** Creates a user, or finds the existing one of that username. Returns the user's id. */
  def createUser(
      username: String,
      email: String,
      password: String,
      emailVerified: Boolean,
      realmRoles: Seq[String] = Nil
  ): String =
    val body = mapper.createObjectNode()
    body.put("username", username)
    body.put("email", email)
    // Keycloak's default user profile requires a first and last name; without them a password
    // grant fails with "Account is not fully set up" (a pending UPDATE_PROFILE action).
    body.put("firstName", username)
    body.put("lastName", "Test")
    body.put("emailVerified", emailVerified)
    body.put("enabled", true)
    val credential = body.putArray("credentials").addObject()
    credential.put("type", "password")
    credential.put("value", password)
    credential.put("temporary", false)
    val created = admin("POST", s"/admin/realms/$realm/users", Some(body.toString))
    val id = created.statusCode match
      case 201 => created.headers.firstValue("Location").orElseThrow().split('/').last
      case 409 =>
        val found = admin("GET", s"/admin/realms/$realm/users?username=$username&exact=true", None)
        mapper.readTree(found.body).get(0).get("id").asText()
      case other => throw new IllegalStateException(s"create user: $other ${created.body}")
    realmRoles.foreach(role => assignRealmRole(id, role))
    id

  def assignRealmRole(userId: String, role: String): Unit =
    val found = admin("GET", s"/admin/realms/$realm/roles/$role", None)
    require(found.statusCode == 200, s"role $role: ${found.statusCode} ${found.body}")
    val node    = mapper.readTree(found.body)
    val mapping = mapper.createArrayNode()
    val entry   = mapping.addObject()
    entry.put("id", node.get("id").asText())
    entry.put("name", node.get("name").asText())
    val response =
      admin(
        "POST",
        s"/admin/realms/$realm/users/$userId/role-mappings/realm",
        Some(mapping.toString)
      )
    require(response.statusCode == 204, s"assign $role: ${response.statusCode} ${response.body}")

  /**
   * Creates a confidential client, or finds the existing one. Returns its internal id (not the
   * client id).
   */
  def createClient(
      clientId: String,
      secret: String,
      serviceAccounts: Boolean,
      directAccessGrants: Boolean,
      defaultScopes: Seq[String] = Seq("basic", "profile", "email", "roles", "ankka-controlplane"),
      optionalScopes: Seq[String] = Seq("offline_access")
  ): String =
    val body = mapper.createObjectNode()
    body.put("clientId", clientId)
    body.put("secret", secret)
    body.put("enabled", true)
    body.put("protocol", "openid-connect")
    body.put("publicClient", false)
    body.put("standardFlowEnabled", false)
    body.put("serviceAccountsEnabled", serviceAccounts)
    body.put("directAccessGrantsEnabled", directAccessGrants)
    val defaults = body.putArray("defaultClientScopes")
    defaultScopes.foreach(defaults.add)
    val optionals = body.putArray("optionalClientScopes")
    optionalScopes.foreach(optionals.add)
    val created = admin("POST", s"/admin/realms/$realm/clients", Some(body.toString))
    created.statusCode match
      case 201 => created.headers.firstValue("Location").orElseThrow().split('/').last
      case 409 =>
        val found = admin("GET", s"/admin/realms/$realm/clients?clientId=$clientId", None)
        mapper.readTree(found.body).get(0).get("id").asText()
      case other => throw new IllegalStateException(s"create client: $other ${created.body}")

  def serviceAccountUserId(clientInternalId: String): String =
    val response =
      admin("GET", s"/admin/realms/$realm/clients/$clientInternalId/service-account-user", None)
    require(response.statusCode == 200, s"service account: ${response.statusCode} ${response.body}")
    mapper.readTree(response.body).get("id").asText()

  def setEmail(userId: String, email: String, verified: Boolean): Unit =
    val current = admin("GET", s"/admin/realms/$realm/users/$userId", None)
    require(current.statusCode == 200, s"get user: ${current.statusCode}")
    val node =
      mapper.readTree(current.body).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    node.put("email", email)
    node.put("emailVerified", verified)
    val response = admin("PUT", s"/admin/realms/$realm/users/$userId", Some(node.toString))
    require(response.statusCode == 204, s"set email: ${response.statusCode} ${response.body}")

  // ── tokens ─────────────────────────────────────────────────────────────────

  def tokenEndpoint: String  = s"$realmUrl/protocol/openid-connect/token"
  def deviceEndpoint: String = s"$realmUrl/protocol/openid-connect/auth/device"
  def jwksUrl: String        = s"$realmUrl/protocol/openid-connect/certs"

  /** The whole token response as JSON, or fails with the status and body. */
  def passwordGrant(
      clientId: String,
      secret: Option[String],
      username: String,
      password: String,
      scope: String = "openid"
  ): JsonNode =
    tokenResponse(
      Map(
        "grant_type" -> "password",
        "client_id"  -> clientId,
        "username"   -> username,
        "password"   -> password,
        "scope"      -> scope
      ) ++ secret.map("client_secret" -> _)
    )

  def clientCredentials(clientId: String, secret: String, scope: String = "openid"): JsonNode =
    tokenResponse(
      Map(
        "grant_type"    -> "client_credentials",
        "client_id"     -> clientId,
        "client_secret" -> secret,
        "scope"         -> scope
      )
    )

  def refresh(clientId: String, secret: Option[String], refreshToken: String): JsonNode =
    tokenResponse(
      Map(
        "grant_type"    -> "refresh_token",
        "client_id"     -> clientId,
        "refresh_token" -> refreshToken
      ) ++ secret.map("client_secret" -> _)
    )

  def deviceAuthorization(clientId: String, scope: String): HttpResponse[String] =
    form(deviceEndpoint, Map("client_id" -> clientId, "scope" -> scope))

  private def tokenResponse(fields: Map[String, String]): JsonNode =
    val response = form(tokenEndpoint, fields)
    require(response.statusCode == 200, s"token: ${response.statusCode} ${response.body}")
    mapper.readTree(response.body)

  // ── plumbing ───────────────────────────────────────────────────────────────

  def admin(method: String, path: String, body: Option[String]): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder(URI.create(baseUrl + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer ${adminToken()}")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  def form(url: String, fields: Map[String, String]): HttpResponse[String] =
    val encoded = fields
      .map((k, v) =>
        URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" +
          URLEncoder.encode(v, StandardCharsets.UTF_8)
      )
      .mkString("&")
    val request = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(encoded))
      .build()
    http.send(request, HttpResponse.BodyHandlers.ofString())

object KeycloakAdmin:

  /** The payload of a JWT, undecoded and unverified — for looking at claims in a test. */
  def claims(jwt: String): JsonNode =
    val payload = jwt.split('.')(1)
    new ObjectMapper().readTree(Base64.getUrlDecoder.decode(payload))

  /**
   * `aud` as a set. Keycloak writes a lone audience as a string and several as an array — the JWT
   * spec allows both, and a verifier (and a test) has to read both.
   */
  def audiences(claims: JsonNode): Set[String] =
    Option(claims.get("aud")) match
      case None => Set.empty
      case Some(node) if node.isArray =>
        val it = node.elements()
        Iterator.continually(it).takeWhile(_.hasNext).map(_.next().asText()).toSet
      case Some(node) => Set(node.asText())

  /** The image tag `build.sbt` pins, forwarded to the forked test JVM; never a literal here. */
  def image: String =
    val version = sys.props.getOrElse(
      "ankka.keycloak.version",
      throw new IllegalStateException(
        "ankka.keycloak.version is not set: run through sbt, which forwards build.sbt's keycloakVersion"
      )
    )
    s"quay.io/keycloak/keycloak:$version"
