package nakka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import nakka.controlplane.api.*
import nakka.controlplane.api.Wire.given

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{ConnectException, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A request the control plane refused, carrying the status so the CLI can exit sensibly. */
final case class ApiError(status: Int, detail: String) extends RuntimeException(detail)

/**
 * The CLI's view of the control plane: one HTTP call per command.
 *
 * Uses the JDK client rather than a Pekko one deliberately. The CLI depends only on
 * `controlplane-api`, so it starts in milliseconds and carries no actor system, no database driver
 * and no Kubernetes client — which is the whole reason the wire types live in a module of their
 * own.
 */
final class ControlPlaneClient(settings: Settings):

  private val http = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  // ── Organizations ─────────────────────────────────────────────────────────

  def listOrganizations(): Vector[OrganizationSummary] =
    get[Vector[OrganizationSummary]]("/organizations")

  def getOrganization(id: String): OrganizationSummary =
    get[OrganizationSummary](s"/organizations/${segment(id)}")

  def createOrganization(id: String, name: String): Unit =
    send(
      "POST",
      s"/organizations/${segment(id)}",
      Some(writeToString(CreateOrganization(name)))
    ): Unit

  def renameOrganization(id: String, name: String): Unit =
    send("PUT", s"/organizations/${segment(id)}/name", Some(writeToString(Rename(name)))): Unit

  def deleteOrganization(id: String): Unit =
    send("DELETE", s"/organizations/${segment(id)}", None): Unit

  // ── Projects ──────────────────────────────────────────────────────────────

  def listProjects(organizationId: Option[String]): Vector[ProjectSummary] =
    val query = organizationId.fold("")(id => s"?organization=${encode(id)}")
    get[Vector[ProjectSummary]](s"/projects$query")

  def getProject(id: String): ProjectSummary = get[ProjectSummary](s"/projects/${segment(id)}")

  def createProject(id: String, name: String, organizationId: String): Unit =
    send(
      "POST",
      s"/projects/${segment(id)}",
      Some(writeToString(CreateProject(name, organizationId)))
    ): Unit

  def renameProject(id: String, name: String): Unit =
    send("PUT", s"/projects/${segment(id)}/name", Some(writeToString(Rename(name)))): Unit

  def deleteProject(id: String): Unit = send("DELETE", s"/projects/${segment(id)}", None): Unit

  // ── Services ──────────────────────────────────────────────────────────────

  def listServices(projectId: String): Vector[ServiceStatus] =
    get[Vector[ServiceStatus]](s"/services/${segment(projectId)}")

  def getService(projectId: String, name: String): ServiceStatus =
    get[ServiceStatus](s"/services/${segment(projectId)}/${segment(name)}")

  def applyService(projectId: String, descriptor: ServiceDescriptor): ServiceStatus =
    decode[ServiceStatus](
      send(
        "PUT",
        s"/services/${segment(projectId)}/${segment(descriptor.name)}",
        Some(writeToString(descriptor))
      )
    )

  def pauseService(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "pause"))

  def resumeService(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "resume"))

  def restartService(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "restart"))

  def deleteService(projectId: String, name: String): Unit =
    send("DELETE", s"/services/${segment(projectId)}/${segment(name)}", None): Unit

  private def action(projectId: String, name: String, verb: String): String =
    send("POST", s"/services/${segment(projectId)}/${segment(name)}/$verb", None)

  // ── Transport ─────────────────────────────────────────────────────────────

  private def get[A](path: String)(using JsonValueCodec[A]): A =
    decode[A](send("GET", path, None))

  private def decode[A](body: String)(using JsonValueCodec[A]): A =
    try readFromString[A](body)
    catch
      case error: Exception =>
        throw ApiError(
          0,
          s"could not read the control plane's response: ${error.getMessage}\n  body: $body"
        )

  private def send(method: String, path: String, body: Option[String]): String =
    val builder = HttpRequest
      .newBuilder(URI.create(settings.url + path))
      .timeout(Duration.ofSeconds(60))
    settings.token.foreach(token => builder.header("Authorization", s"Bearer $token"): Unit)
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
      case None =>
        builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit

    val response =
      try http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      catch
        case _: ConnectException =>
          throw ApiError(
            0,
            s"no control plane at ${settings.url}\n" +
              "  start one with `sbt controlPlane/run`, or set NAKKA_URL / `nakka config set url`"
          )
        case error: IOException =>
          throw ApiError(0, s"could not reach ${settings.url}: ${error.getMessage}")

    if response.statusCode >= 200 && response.statusCode < 300 then response.body
    else throw ApiError(response.statusCode, explain(response.statusCode, response.body))

  /**
   * Turns an error response into something an operator can act on.
   *
   * The control plane replies with a JSON problem carrying the entity's own message, so the useful
   * text is in there — but a 401 or 403 usually means a missing token rather than a missing
   * permission, and saying so saves a support round-trip.
   */
  private def explain(status: Int, body: String): String =
    val detail = Problem.message(body).getOrElse(body.take(500))
    status match
      case 401 | 403 if settings.token.isEmpty =>
        s"$detail\n  no token configured; set NAKKA_TOKEN or run `nakka config set token <value>`"
      case 401 | 403 => s"$detail\n  the configured token was rejected"
      case _         => detail

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** Path segments are encoded, then `+` is undone — `+` means a space only in a query. */
  private def segment(value: String): String = encode(value).replace("+", "%20")

/**
 * Reads the message out of the control plane's JSON problem response.
 *
 * Declared here rather than imported from `nakka-http`, because the CLI must not depend on the
 * server's HTTP module — the field names are the contract, and a test asserts the two agree.
 */
private[cli] object Problem:

  private final case class Body(status: Int, message: String)

  private given JsonValueCodec[Body] = nakka.core.Codecs.make[Body]

  def message(body: String): Option[String] =
    scala.util.Try(readFromString[Body](body)).toOption.map(_.message)
