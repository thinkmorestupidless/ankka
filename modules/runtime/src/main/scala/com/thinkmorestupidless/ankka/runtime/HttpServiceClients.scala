package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.sdk.{
  ServiceClient,
  ServiceClients,
  ServiceIdentityMismatch,
  ServiceResponse,
  ServiceUnresolvable
}
import com.typesafe.config.Config

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
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
 */
final class HttpServiceClients(
    config: Config,
    self: Option[RotatingTls.Identity],
    /** Where `project/name` answers in a cluster; Kubernetes DNS unless a test says otherwise. */
    locate: (String, String) => Option[(String, Int)] = HttpServiceClients.kubernetes
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
    ): ServiceResponse =
      val (host, port) = locate(project, name).getOrElse(
        throw ServiceUnresolvable(
          target,
          s"no SRV record _http._tcp.${HttpServiceClients.hostOf(project, name)}"
        )
      )
      val client = clientFor(identity)
      try send(client, URI(s"https://$host:$port$path"), method, body, contentType, headers)
      catch
        case e: SSLHandshakeException if Option(e.getMessage).exists(_.contains("peer identity")) =>
          throw ServiceIdentityMismatch(target, e.getMessage)

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
    ): ServiceResponse =
      val base = localAddress(name).getOrElse(
        throw ServiceUnresolvable(
          target,
          s"not in ankka.local-services and not announced in ${ServiceRegistration.directory}"
        )
      )
      send(plain, URI(s"${base.stripSuffix("/")}$path"), method, body, contentType, headers)

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
      headers: Seq[(String, String)]
  ): ServiceResponse =
    val builder = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofSeconds(30))
      .method(
        method,
        body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofByteArray)
      )
    contentType.foreach(c => builder.header("Content-Type", c): Unit)
    headers.foreach((k, v) => builder.header(k, v): Unit)
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    ServiceResponse(
      response.statusCode,
      response.headers.firstValue("Content-Type").orElse(""),
      response.body,
      response.headers.map.asScala.toVector.flatMap((k, vs) => vs.asScala.map(k -> _))
    )

object HttpServiceClients:

  /** How the operator names a project's namespace, which it tells every workload. */
  private def namespacePrefix: String = sys.env.getOrElse("ANKKA_NAMESPACE_PREFIX", "ankka")

  def hostOf(project: String, name: String): String =
    s"$name.$namespacePrefix-$project.svc.cluster.local"

  /** The Service's in-cluster name, and the port its SRV record publishes for `http`. */
  val kubernetes: (String, String) => Option[(String, Int)] = (project, name) =>
    val host = hostOf(project, name)
    srvPort(host).map(host -> _)

  /**
   * The port a Kubernetes Service publishes under the name `http`, from its SRV record. Cached for
   * a minute: a Service's port changes only when its descriptor does.
   */
  def srvPort(host: String): Option[Int] =
    val now = System.nanoTime()
    Option(ports.get(host)).filter((_, at) => now - at < 60_000_000_000L).map(_._1).orElse {
      try
        val context = new InitialDirContext(
          java.util.Hashtable(
            Map("java.naming.factory.initial" -> "com.sun.jndi.dns.DnsContextFactory").asJava
          )
        )
        val records = context.getAttributes(s"_http._tcp.$host", Array("SRV")).get("SRV")
        val port =
          Option(records).flatMap(r => Option(r.get(0))).map(_.toString.trim.split("\\s+")(2).toInt)
        port.foreach(p => ports.put(host, (p, now)))
        port
      catch case NonFatal(_) => None
    }

  private val ports = new ConcurrentHashMap[String, (Int, Long)]()

private val ServiceDirectoryKey = "ankka.tls.service-directory"
