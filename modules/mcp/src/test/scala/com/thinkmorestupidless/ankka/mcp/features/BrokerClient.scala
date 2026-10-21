package com.thinkmorestupidless.ankka.mcp.features

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.mcp.signin.IssuerClient

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * An MCP client signing in through a service's sign-in broker, as Claude's does: it registers
 * itself, asks to sign in with PKCE, follows every redirect the broker and the issuer answer as a
 * browser would, and exchanges the code it is handed for a token.
 *
 * Every request to the service arrives through the gateway (`outside`), so the broker names itself
 * https, as it would behind the platform's gateway; a link to `https://127.0.0.1` is followed over
 * plain http, which is what the gateway would have done.
 */
final class BrokerClient(service: String, outside: (String, String)):

  private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  /** Where the client is sent back to: never served, only read from the last redirect. */
  val redirectUri = "http://127.0.0.1:7/callback"
  val verifier    = IssuerClient.randomValue()
  val clientState = "client-state"

  /** Every response the client was given, in order, for the scan that no secret leaked. */
  val read = Vector.newBuilder[String]

  final case class Answer(status: Int, location: Option[String], body: String)

  private def toService(address: String): Boolean =
    address.startsWith(service.replace("http://", "https://")) ||
      address.startsWith(service)

  private def send(builder: HttpRequest.Builder, address: String): Answer =
    if toService(address) then builder.header(outside._1, outside._2): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    read += response.body()
    Answer(
      response.statusCode(),
      Option(response.headers().firstValue("Location").orElse(null)),
      response.body()
    )

  private def plain(address: String): URI =
    URI.create(if toService(address) then address.replace("https://", "http://") else address)

  def get(address: String): Answer = send(HttpRequest.newBuilder(plain(address)).GET(), address)

  def post(address: String, contentType: String, body: String): Answer =
    send(
      HttpRequest
        .newBuilder(plain(address))
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body)),
      address
    )

  /** `https://127.0.0.1:<port>` of the service, as the broker names it. */
  def origin: String = service.replace("http://", "https://")

  def broker: String = s"$origin/_ankka/sign-in/mcp"

  // ── the steps of a sign-in ───────────────────────────────────────────────

  var clientId: Option[String]      = None
  var registered: Option[Answer]    = None
  var firstRedirect: Option[String] = None
  var sentBack: Option[String]      = None
  var tokens: Option[Json]          = None

  def register(): Answer =
    val body = Json.obj(
      "redirect_uris"              -> Json.arr(Json.str(redirectUri)),
      "client_name"                -> Json.str("An assistant"),
      "token_endpoint_auth_method" -> Json.str("none")
    )
    val answer = post(s"$broker/register", "application/json", body.render)
    registered = Some(answer)
    clientId = Json.parse(answer.body).toOption.flatMap(_("client_id")).flatMap(_.asString)
    answer

  /** Asks to sign in, and answers where the broker sent the person. */
  def authorize(): Answer =
    val query = Vector(
      "client_id"             -> clientId.getOrElse(""),
      "redirect_uri"          -> redirectUri,
      "response_type"         -> "code",
      "code_challenge"        -> IssuerClient.challenge(verifier),
      "code_challenge_method" -> "S256",
      "state"                 -> clientState,
      "resource"              -> s"$origin/mcp"
    ).map((k, v) => s"$k=${IssuerClient.encode(v)}").mkString("&")
    val answer = get(s"$broker/authorize?$query")
    firstRedirect = answer.location
    answer

  /** Follows redirects from where the broker sent the person until one leads back to the client. */
  def followToClient(): Map[String, String] =
    var at    = firstRedirect.getOrElse(throw AssertionError("the broker sent the person nowhere"))
    var steps = 0
    while !at.startsWith(redirectUri) do
      steps += 1
      if steps > 5 then throw AssertionError(s"too many redirects, last $at")
      val answer = get(at)
      at = answer.location.getOrElse(
        throw AssertionError(s"no redirect from $at: ${answer.status} ${answer.body}")
      )
    sentBack = Some(at)
    IssuerClient.form(Option(URI.create(at).getRawQuery).getOrElse(""))

  def exchange(code: String): Answer =
    val form = Vector(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "redirect_uri"  -> redirectUri,
      "client_id"     -> clientId.getOrElse(""),
      "code_verifier" -> verifier,
      "resource"      -> s"$origin/mcp"
    ).map((k, v) => s"$k=${IssuerClient.encode(v)}").mkString("&")
    val answer = post(s"$broker/token", "application/x-www-form-urlencoded", form)
    tokens = Option.when(answer.status == 200)(Json.parse(answer.body).toOption).flatten
    answer

  /** The whole sign-in, from registering to a token; the token's JSON when one was minted. */
  def signIn(): Option[Json] =
    register(): Unit
    authorize(): Unit
    followToClient().get("code").foreach(exchange(_): Unit)
    tokens

  def accessToken: Option[String]  = tokens.flatMap(_("access_token")).flatMap(_.asString)
  def refreshToken: Option[String] = tokens.flatMap(_("refresh_token")).flatMap(_.asString)
