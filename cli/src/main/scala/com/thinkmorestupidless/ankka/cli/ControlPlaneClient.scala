package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{ConnectException, URLEncoder}
import java.nio.file.Paths
import javax.net.ssl.SSLHandshakeException
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

  private val http =
    val builder = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
    // A configured root is added to the platform's, never substituted for them (see Trust).
    settings.ca.foreach(pem => builder.sslContext(Trust.sslContext(Paths.get(pem))): Unit)
    builder.build()

  // ── Identity ──────────────────────────────────────────────────────────────

  /** Where to log in. The one call that needs no credential. */
  def discovery(): AuthDiscovery =
    decode[AuthDiscovery](send("GET", "/auth", None, authenticated = false))

  def whoami(): Whoami = get[Whoami]("/auth/whoami")

  // ── Organizations ─────────────────────────────────────────────────────────

  def listOrganizations(): Vector[OrganizationSummary] =
    get[Vector[OrganizationSummary]]("/organizations")

  def getOrganization(id: String): OrganizationSummary =
    get[OrganizationSummary](s"/organizations/${segment(id)}")

  def createOrganization(id: String, name: String, owner: Option[Owner] = None): Unit =
    send(
      "POST",
      s"/organizations/${segment(id)}",
      Some(writeToString(CreateOrganization(name, owner)))
    ): Unit

  def renameOrganization(id: String, name: String): Unit =
    send("PUT", s"/organizations/${segment(id)}/name", Some(writeToString(Rename(name)))): Unit

  def deleteOrganization(id: String): Unit =
    send("DELETE", s"/organizations/${segment(id)}", None): Unit

  // ── Membership ────────────────────────────────────────────────────────────

  def listMembers(id: String): MembersResponse =
    get[MembersResponse](s"/organizations/${segment(id)}/members")

  def invite(id: String, email: String, role: Role): Unit =
    send(
      "POST",
      s"/organizations/${segment(id)}/members",
      Some(writeToString(Invite(email, role)))
    ): Unit

  def removeMember(id: String, subject: String): Unit =
    send("DELETE", s"/organizations/${segment(id)}/members/${segment(subject)}", None): Unit

  def changeRole(id: String, subject: String, role: Role): Unit =
    send(
      "PUT",
      s"/organizations/${segment(id)}/members/${segment(subject)}/role",
      Some(writeToString(RoleChange(role)))
    ): Unit

  def revokeInvitation(id: String, email: String): Unit =
    send("DELETE", s"/organizations/${segment(id)}/invitations/${segment(email)}", None): Unit

  def repairMember(id: String, subject: String, role: Role): Unit =
    send(
      "POST",
      s"/organizations/${segment(id)}/members/${segment(subject)}/repair",
      Some(writeToString(Repair(role)))
    ): Unit

  // ── Deploy tokens (feature 013) ───────────────────────────────────────────

  def listDeployTokens(id: String): Vector[DeployTokenSummary] =
    get[Vector[DeployTokenSummary]](s"/organizations/${segment(id)}/tokens")

  /** The reply carries the secret. It is returned, printed once, and never stored. */
  def createDeployToken(id: String, label: String, expiresIn: Option[Long]): DeployTokenCreated =
    decode[DeployTokenCreated](
      send(
        "POST",
        s"/organizations/${segment(id)}/tokens",
        Some(writeToString(CreateDeployToken(label, expiresIn)))
      )
    )

  def revokeDeployToken(id: String, tokenId: String): Unit =
    send("DELETE", s"/organizations/${segment(id)}/tokens/${segment(tokenId)}", None): Unit

  def disableOrganization(id: String): Unit =
    send("POST", s"/organizations/${segment(id)}/disable", None): Unit

  def enableOrganization(id: String): Unit =
    send("POST", s"/organizations/${segment(id)}/enable", None): Unit

  // ── Quotas (feature 015) ──────────────────────────────────────────────────

  def setQuota(id: String, quota: Quota): Unit =
    send("PUT", s"/organizations/${segment(id)}/quota", Some(writeToString(quota))): Unit

  def clearQuota(id: String): Unit =
    send("DELETE", s"/organizations/${segment(id)}/quota", None): Unit

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

  /**
   * Registers a registry credential for a project.
   *
   * The password is in the body, never the path or a query parameter: a URL reaches proxy logs and
   * shell history, and a body under TLS does not.
   */
  def setRegistry(id: String, server: String, username: String, password: String): Unit =
    send(
      "PUT",
      s"/projects/${segment(id)}/registry",
      Some(writeToString(SetRegistry(server, username, password)))
    ): Unit

  def clearRegistry(id: String): Unit =
    send("DELETE", s"/projects/${segment(id)}/registry", None): Unit

  /** Sets entries of a project secret. The values are in the body, never the path or the query. */
  def setProjectSecret(projectId: String, name: String, entries: Map[String, String]): Unit =
    send(
      "PUT",
      s"/projects/${segment(projectId)}/secrets/${segment(name)}",
      Some(writeToString(SetProjectSecret(entries)))
    ): Unit

  def unsetProjectSecretEntry(projectId: String, name: String, entry: String): Unit =
    send(
      "DELETE",
      s"/projects/${segment(projectId)}/secrets/${segment(name)}?entry=${segment(entry)}",
      None
    ): Unit

  def listProjectSecrets(projectId: String): Vector[ProjectSecretSummary] =
    get[Vector[ProjectSecretSummary]](s"/projects/${segment(projectId)}/secrets")

  /**
   * Declares a topic on a project, or raises its partitions (feature 027); its compaction and
   * contract (feature 037).
   */
  def declareTopic(projectId: String, name: String, request: TopicDeclarationRequest): Unit =
    send(
      "PUT",
      s"/projects/${segment(projectId)}/topics/${segment(name)}",
      Some(writeToString(request))
    ): Unit

  def declareTopic(projectId: String, name: String, partitions: Int): Unit =
    declareTopic(projectId, name, TopicDeclarationRequest(partitions))

  /** The schema document a topic's contract was declared with, as JSON text. */
  def topicSchema(projectId: String, name: String): String =
    send("GET", s"/projects/${segment(projectId)}/topics/${segment(name)}/schema", None)

  def removeTopic(projectId: String, name: String): Unit =
    send("DELETE", s"/projects/${segment(projectId)}/topics/${segment(name)}", None): Unit

  def listTopics(projectId: String): Vector[ProjectTopic] =
    get[Vector[ProjectTopic]](s"/projects/${segment(projectId)}/topics")

  /** Declares a broker on a project (feature 037). */
  def declareBroker(projectId: String, name: String, request: BrokerDeclarationRequest): Unit =
    send(
      "PUT",
      s"/projects/${segment(projectId)}/brokers/${segment(name)}",
      Some(writeToString(request))
    ): Unit

  def removeBroker(projectId: String, name: String): Unit =
    send("DELETE", s"/projects/${segment(projectId)}/brokers/${segment(name)}", None): Unit

  def listBrokers(projectId: String): Vector[ProjectBroker] =
    get[Vector[ProjectBroker]](s"/projects/${segment(projectId)}/brokers")

  /** Where the project's new buckets in Google Cloud Storage are made (feature 039). */
  def setProjectLocation(projectId: String, location: String): Unit =
    send(
      "PUT",
      s"/projects/${segment(projectId)}/location",
      Some(writeToString(SetProjectLocation(location)))
    ): Unit

  def clearProjectLocation(projectId: String): Unit =
    send("DELETE", s"/projects/${segment(projectId)}/location", None): Unit

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

  /** Issues the service's storage credential again (feature 039). */
  def reissueStorageCredential(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "storage-credential"))

  def exposeService(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "expose"))

  /**
   * A service's recent output, per instance.
   *
   * Bounded by default. A service that has been logging for a week cannot be returned whole, and a
   * developer asking for logs wants the recent ones — `--since` and `--tail` widen the window
   * deliberately rather than by accident.
   */
  def serviceLogs(
      projectId: String,
      name: String,
      instance: Option[String],
      previous: Boolean,
      tail: Option[Int],
      since: Option[Int],
      platform: Boolean = false
  ): LogsResponse =
    val params = Vector(
      instance.map(i => s"instance=${segment(i)}"),
      Option.when(previous)("previous=true"),
      Option.when(platform)("platform=true"),
      tail.map(t => s"tail=$t"),
      since.map(s => s"since=$s")
    ).flatten
    val query = if params.isEmpty then "" else params.mkString("?", "&", "")
    get[LogsResponse](s"/services/${segment(projectId)}/${segment(name)}/logs$query")

  /** Every instance's topology, merged by the control plane. */
  def serviceTopology(projectId: String, name: String): ServiceTopology =
    get[ServiceTopology](s"/services/${segment(projectId)}/${segment(name)}/topology")

  def serviceHistory(projectId: String, name: String): Vector[HistoryEntry] =
    get[Vector[HistoryEntry]](s"/services/${segment(projectId)}/${segment(name)}/history")

  /** The descriptor recorded at a generation (feature 033). */
  def serviceDescriptor(projectId: String, name: String, generation: Long): ServiceDescriptor =
    get[ServiceDescriptor](
      s"/services/${segment(projectId)}/${segment(name)}/descriptor?generation=$generation"
    )

  /** Rolls a service back; with no generation, the control plane chooses (feature 033). */
  /** Applies the installation's bucket settings to the service's bucket (feature 039). */
  def reapplyStorageSettings(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "storage/settings"))

  /** Moves the service's bucket from Garage to Google Cloud Storage (feature 039). */
  def moveStorage(projectId: String, name: String, writePauseBound: Option[String]): ServiceStatus =
    decode[ServiceStatus](
      send(
        "POST",
        s"/services/${segment(projectId)}/${segment(name)}/storage/move",
        Some(writeToString(StorageMoveRequest(writePauseBound)))
      )
    )

  def rollbackService(projectId: String, name: String, generation: Option[Long]): RolledBack =
    decode[RolledBack](
      send(
        "POST",
        s"/services/${segment(projectId)}/${segment(name)}/rollback",
        Some(writeToString(RollbackRequest(generation)))
      )
    )

  def unexposeService(projectId: String, name: String): ServiceStatus =
    decode[ServiceStatus](action(projectId, name, "unexpose"))

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

  // Resolved once per command, and only by a call that needs it: `discovery()` must work with no
  // login at all, and resolving eagerly would turn "log in" into "cannot even find out where".
  private lazy val bearer: String = Session.bearer(settings)

  private def send(
      method: String,
      path: String,
      body: Option[String],
      authenticated: Boolean = true
  ): String =
    val builder = HttpRequest
      .newBuilder(URI.create(settings.url + path))
      .timeout(Duration.ofSeconds(60))
    if authenticated then builder.header("Authorization", s"Bearer $bearer"): Unit
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
              "  start one with `sbt controlPlane/run`, or set ANKKA_URL / `ankka config set url`"
          )
        case error: SSLHandshakeException =>
          // The one hint that helps: a local cluster's root is trusted by naming it, never by
          // switching verification off — there is no switch.
          throw ApiError(
            0,
            s"could not verify ${settings.url}: ${error.getMessage}\n" +
              "  if this is a local ankka cluster, run: ankka config set ca ~/.ankka/local-ca.crt"
          )
        case error: IOException =>
          throw ApiError(0, s"could not reach ${settings.url}: ${error.getMessage}")

    if response.statusCode >= 200 && response.statusCode < 300 then response.body
    else throw ApiError(response.statusCode, explain(response.statusCode, response.body))

  /**
   * Turns an error response into something an operator can act on.
   *
   * The control plane replies with a JSON problem carrying the entity's own message, so the useful
   * text is in there. A 401 and a 403 are different answers and get different advice: the first is
   * "log in" (the credential was not accepted), the second "you may not" (it was, and the action is
   * not yours to take) — folding them together is how a CLI tells someone whose login merely
   * expired that they are forbidden.
   */
  private def explain(status: Int, body: String): String =
    val detail = Problem.message(body).getOrElse(body.take(500))
    status match
      case 401 if settings.token.isDefined => s"the token was rejected: $detail"
      case 401 => s"${settings.url} rejected the login; run 'ankka login'"
      case 403 => s"not permitted: $detail"
      case _   => detail

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** Path segments are encoded, then `+` is undone — `+` means a space only in a query. */
  private def segment(value: String): String = encode(value).replace("+", "%20")

/**
 * Reads the message out of the control plane's JSON problem response.
 *
 * Declared here rather than imported from `ankka-http`, because the CLI must not depend on the
 * server's HTTP module — the field names are the contract, and a test asserts the two agree.
 */
private[cli] object Problem:

  private final case class Body(status: Int, message: String)

  private given JsonValueCodec[Body] = com.thinkmorestupidless.ankka.core.Codecs.make[Body]

  def message(body: String): Option[String] =
    scala.util.Try(readFromString[Body](body)).toOption.map(_.message)
