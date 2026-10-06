package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.sdk.{
  ServiceClient,
  ServiceClients,
  ServiceIdentityMismatch,
  ServiceResponse,
  ServiceUnanswered,
  ServiceUnresolvable
}
import com.typesafe.config.Config

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.naming.directory.InitialDirContext
import javax.net.ssl.{SSLHandshakeException, SSLParameters}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * `ServiceClients` over the JDK's HTTP client (feature 014).
 *
 * In a cluster (`ankka.tls.service-directory` set): the callee is
 * `<name>.<namespace prefix>-<project>.svc.cluster.local`, its port is read from the DNS SRV record
 * Kubernetes publishes for its Service's port named `http` — the descriptor chooses the port, so a
 * client must not guess one — and the connection is mutual TLS presenting this service's
 * certificate, accepting only a callee whose certificate names the host *and* carries the
 * `ankka://` identity asked for. A mismatch fails the handshake, so no request body is ever sent to
 * the wrong workload.
 *
 * Outside a cluster: `ankka.local-services.<name>` if set, otherwise the entry the named service
 * announced to the local console's registry; plain HTTP.
 *
 * Every door to another service goes through here — an endpoint's, a workflow step's, a consumer's,
 * a timed action's, an agent's, and the sidecar's on behalf of a process — so what is true of one
 * call is true of all of them: one of four errors when no answer came or nothing was sent
 * (`ServiceUnresolvable`, `ServiceIdentityMismatch`, `ServiceUnanswered`, and `ServiceCallFailed`
 * from a typed helper), `ankka.service-client.timeout` for the answer, none of the platform's
 * headers (`OutboundHeaders`), and a span in the calling handler's trace.
 */
final class HttpServiceClients(
    config: Config,
    self: Option[RotatingTls.Identity],
    /** Where `project/name` answers in a cluster; Kubernetes DNS unless a test says otherwise. */
    locate: (String, String) => Option[(String, Int)] = HttpServiceClients.kubernetes,
    /** Where a call to another service is counted; none for a client outside a service. */
    observability: Option[Observability] = None
) extends ServiceClients:

  private val tls: Option[RotatingTls] =
    Option.when(
      config.hasPath(ServiceDirectoryKey) && config.getString(ServiceDirectoryKey).nonEmpty
    )(
      RotatingTls(
        Paths.get(config.getString(ServiceDirectoryKey)),
        FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, "ms")
      )
    )

  private val timeoutMillis: Long =
    if config.hasPath(HttpServiceClients.TimeoutKey) then
      config.getDuration(HttpServiceClients.TimeoutKey).toMillis
    else 30_000L
  private val timeout: Duration = Duration.ofMillis(timeoutMillis)

  private val ownProject: String =
    self.map(_.project).orElse(tls.flatMap(_.identity).map(_.project)).getOrElse("local")

  def apply(name: String): ServiceClient = apply(ownProject, name)

  def apply(project: String, name: String): ServiceClient =
    tls match
      case Some(identity) => ClusterClient(identity, project, name)
      case None           => LocalClient(project, name)

  private final class ClusterClient(identity: RotatingTls, project: String, name: String)
      extends ServiceClient:
    val target: String   = s"$project/$name"
    private val expected = s"ankka://$project/$name"

    def request(
        method: String,
        path: String,
        body: Option[Array[Byte]],
        contentType: Option[String],
        headers: Seq[(String, String)]
    ): ServiceResponse = counted(project, name, method) { traceparent =>
      val (host, port) = locate(project, name).getOrElse(
        throw ServiceUnresolvable(
          target,
          s"no SRV record _http._tcp.${HttpServiceClients.hostOf(project, name)}"
        )
      )
      val client = clientFor(identity)
      try
        send(
          client,
          URI(s"https://$host:$port$path"),
          method,
          body,
          contentType,
          headers,
          traceparent
        )
      catch
        case e: SSLHandshakeException if Option(e.getMessage).exists(_.contains("peer identity")) =>
          throw ServiceIdentityMismatch(target, e.getMessage)
    }

    /** One client per identity generation: a renewed certificate reaches new connections. */
    private def clientFor(identity: RotatingTls): HttpClient =
      val context = identity.contextRequiring(expected)
      clients.computeIfAbsent(
        (expected, System.identityHashCode(context)),
        _ =>
          val params = new SSLParameters()
          params.setEndpointIdentificationAlgorithm("HTTPS")
          params.setProtocols(Array("TLSv1.3"))
          HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(context)
            .sslParameters(params)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
      )

  private final class LocalClient(project: String, name: String) extends ServiceClient:
    val target: String = s"$project/$name"
    def request(
        method: String,
        path: String,
        body: Option[Array[Byte]],
        contentType: Option[String],
        headers: Seq[(String, String)]
    ): ServiceResponse = counted(project, name, method) { traceparent =>
      val base = localAddress(name).getOrElse(
        throw ServiceUnresolvable(
          target,
          s"not in ankka.local-services and not announced in ${ServiceRegistration.directory}"
        )
      )
      send(
        plain,
        URI(s"${base.stripSuffix("/")}$path"),
        method,
        body,
        contentType,
        headers,
        traceparent
      )
    }

  /**
   * A call to another service, recorded and counted where it is made: nothing in this service hosts
   * the callee, so the caller's side is the only one that can. The callee is the service, admitted
   * by name up to a limit, and its handler is the request's method and never its path, which may
   * carry an id. Who made it is the calling thread's own origin, taken before anything is sent. A
   * response is handled as its status says — refused when the callee said no, failed when it could
   * not answer — and a call that got no response at all is unanswered: timed out, or never
   * delivered, including one to a name that resolves to nothing.
   *
   * The call is a span of its own, under the calling handler's, and the request carries that span's
   * `traceparent`: the callee continues the trace under it. With no recorder the thread's own
   * context is carried, so a trace still joins across services.
   */
  private def counted(project: String, name: String, method: String)(
      request: Option[String] => ServiceResponse
  ): ServiceResponse =
    val target = s"$project/$name"
    def call: ServiceResponse =
      observability match
        case None => request(Trace.currentContext.map(Traceparent.render))
        case Some(o) =>
          o.calling(
            o.externalServices.nameFor(project, name),
            HttpServiceClients.methodName(method)
          )(HttpServiceClients.outcomeOf)(t => request(Some(t)))
    try call
    catch
      case e: (ServiceUnresolvable | ServiceIdentityMismatch) => throw e
      case e: HttpTimeoutException =>
        throw ServiceUnanswered(
          target,
          s"no answer within ${FiniteDuration(timeoutMillis, "ms").toCoarsest}",
          e
        )
      case NonFatal(e) =>
        throw ServiceUnanswered(target, Option(e.getMessage).getOrElse(e.getClass.getName), e)

  private lazy val plain: HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build()

  private val clients = new ConcurrentHashMap[(String, Int), HttpClient]()

  private def localAddress(name: String): Option[String] =
    val key = s"ankka.local-services.\"$name\""
    Option
      .when(config.hasPath(key))(config.getString(key))
      .orElse(ServiceRegistration.httpAddressOf(name))

  private def send(
      client: HttpClient,
      uri: URI,
      method: String,
      body: Option[Array[Byte]],
      contentType: Option[String],
      headers: Seq[(String, String)],
      traceparent: Option[String]
  ): ServiceResponse =
    val builder = HttpRequest
      .newBuilder(uri)
      .timeout(timeout)
      .method(
        method,
        body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofByteArray)
      )
    contentType.foreach(c => builder.header("Content-Type", c): Unit)
    // The platform's context replaces any a handler supplied: a handler forwarding the headers it
    // was given would otherwise put this call under whoever called it.
    OutboundHeaders
      .sent(headers)
      .filterNot((k, _) => k.equalsIgnoreCase(Traceparent.Name))
      .foreach((k, v) => builder.header(k, v): Unit)
    traceparent.foreach(t => builder.header(Traceparent.Name, t): Unit)
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    ServiceResponse(
      response.statusCode,
      response.headers.firstValue("Content-Type").orElse(""),
      response.body,
      response.headers.map.asScala.toVector.flatMap((k, vs) => vs.asScala.map(k -> _))
    )

object HttpServiceClients:

  /** How long a call waits for its answer. */
  val TimeoutKey: String = "ankka.service-client.timeout"

  /**
   * The methods a call is counted under; any other is `(other)`, so the table of names is bounded.
   */
  private val Methods = Set("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

  /** How a call ended as its caller saw it, for its span and its count. */
  private[runtime] def outcomeOf(result: scala.util.Try[ServiceResponse]): SpanOutcome =
    result match
      case scala.util.Success(response) =>
        response.status / 100 match
          case 4 => SpanOutcome.Refused
          case 5 => SpanOutcome.Failed
          case _ => SpanOutcome.Ok
      case scala.util.Failure(_: HttpTimeoutException) => SpanOutcome.TimedOut
      case scala.util.Failure(_)                       => SpanOutcome.Failed

  /** A request's method as the handler its call is counted under. */
  def methodName(method: String): String =
    val upper = method.toUpperCase
    if Methods.contains(upper) then upper else CallCounts.OtherServices

  /** How the operator names a project's namespace, which it tells every workload. */
  private def namespacePrefix: String = sys.env.getOrElse("ANKKA_NAMESPACE_PREFIX", "ankka")

  def hostOf(project: String, name: String): String =
    s"$name.$namespacePrefix-$project.svc.cluster.local"

  /**
   * The Service's in-cluster name, and the port its SRV record publishes for `http`.
   *
   * A web-hosted service's proxy finds the services its process calls, and the services it mounts,
   * through this same function (feature 021), with the namespace prefix the operator gives every
   * workload as `ANKKA_NAMESPACE_PREFIX`. Where a service is found in a cluster is therefore one
   * rule: a change to it is a change to both callers.
   */
  val kubernetes: (String, String) => Option[(String, Int)] = (project, name) =>
    val host = hostOf(project, name)
    srvPort(host).map(host -> _)

  /**
   * The port a Kubernetes Service publishes under the name `http`, from its SRV record. Cached for
   * a minute: a Service's port changes only when its descriptor does.
   */
  def srvPort(host: String): Option[Int] = srvPort(host, "http")

  /**
   * The port a Kubernetes Service publishes under `portName` — `http` or `grpc` — from its SRV
   * record, which Kubernetes publishes for every named port.
   */
  def srvPort(host: String, portName: String): Option[Int] =
    val now = System.nanoTime()
    val key = s"$portName/$host"
    Option(ports.get(key)).filter((_, at) => now - at < 60_000_000_000L).map(_._1).orElse {
      try
        val context = new InitialDirContext(
          java.util.Hashtable(
            Map("java.naming.factory.initial" -> "com.sun.jndi.dns.DnsContextFactory").asJava
          )
        )
        val records = context.getAttributes(s"_$portName._tcp.$host", Array("SRV")).get("SRV")
        val port =
          Option(records).flatMap(r => Option(r.get(0))).map(_.toString.trim.split("\\s+")(2).toInt)
        port.foreach(p => ports.put(key, (p, now)))
        port
      catch case NonFatal(_) => None
    }

  private val ports = new ConcurrentHashMap[String, (Int, Long)]()

private val ServiceDirectoryKey = "ankka.tls.service-directory"
