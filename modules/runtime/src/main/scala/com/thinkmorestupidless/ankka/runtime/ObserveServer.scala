package com.thinkmorestupidless.ankka.runtime

import com.sun.net.httpserver.{HttpExchange, HttpsConfigurator, HttpsParameters, HttpsServer}
import org.apache.pekko.actor.typed.ActorSystem

import java.net.InetSocketAddress
import java.nio.file.Paths
import java.security.{KeyManagementException, SecureRandom}
import javax.net.ssl.{
  KeyManager,
  SSLContext,
  SSLContextSpi,
  SSLEngine,
  SSLServerSocketFactory,
  SSLSessionContext,
  SSLSocketFactory,
  TrustManager
}
import scala.concurrent.duration.*

/**
 * What a deployed instance exposes so that the installation's control plane, and only the control
 * plane, can read its topology on a member's behalf.
 *
 * The local console reads an instance over loopback, and loopback is the whole of that access
 * control. In a cluster the reader is another workload, so the listener is mutual TLS with the
 * service's own certificate, and the only peer it admits is the one carrying the control plane's
 * identity: a connection presenting anything else — the service's own certificate, the gateway's,
 * another service's — fails the handshake and never reaches a route. That rests on `platform` being
 * a project id nobody else may have.
 *
 * It serves the two documents the control plane merges and nothing else: no trace, no session, no
 * query. The JDK's server, as the local endpoint uses, so `runtime` gains no dependency. It is not
 * part of readiness: a service whose topology cannot be read is a service that works and cannot be
 * looked at, which is no reason to take it out of rotation.
 */
final class ObserveServer private (server: HttpsServer):

  /** The port bound: the configured one, or whichever was free when a test asked for any. */
  def port: Int = server.getAddress.getPort

  def stop(): Unit = server.stop(0)

object ObserveServer:

  /** The container port the operator renders, by name and number (contract `observe-port`). */
  val DefaultPort: Int = 7628
  val PortName: String = "observe"

  /** The one peer admitted: the control plane's identity. */
  val ControlPlane: String = "ankka://platform/controlplane"

  /** The documents the listener serves, rendered on each read. */
  trait Documents:
    def service(): String
    def topology(): String

    /** What the broker holds past a moment (feature 041), as `Divergence.json`. */
    def divergence(since: java.time.Instant): String =
      val _ = since
      "[]"

  /**
   * Starts the listener if the configuration says to, which the Kubernetes overlay does. Returns
   * none, after logging why, when it is off or cannot start — a failure here must never take a
   * service out of rotation.
   */
  def startIfEnabled(system: ActorSystem[?], documents: Documents): Option[ObserveServer] =
    val config = system.settings.config
    def string(key: String, otherwise: String) =
      if config.hasPath(key) then config.getString(key) else otherwise
    val enabled =
      config.hasPath("ankka.observability.observe.enabled") &&
        config.getBoolean("ankka.observability.observe.enabled")
    if !enabled then None
    else
      try
        val port      = config.getInt("ankka.observability.observe.port")
        val peer      = string("ankka.observability.observe.peer", ControlPlane)
        val directory = string("ankka.tls.service-directory", "")
        if directory.isEmpty then
          throw IllegalStateException(
            "ankka.observability.observe is on but ankka.tls.service-directory names no certificate"
          )
        val interval =
          if config.hasPath("ankka.tls.reload-interval") then
            FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, "ms")
          else 1.minute
        val tls     = RotatingTls(Paths.get(directory), interval, RotatingTls.Peers.Exactly(peer))
        val started = start(tls, port, documents)
        system.log.info("ankka observe listener on port {}, admitting {}", started.port, peer)
        Some(started)
      catch
        case failure: Throwable =>
          system.log.error("the observe listener did not start; the service is unaffected", failure)
          None

  /**
   * Starts the listener on every interface at `port` (0 for any free one), serving with `tls`:
   * every connection's engine comes from the current context, so a renewed certificate is presented
   * without a restart, and a client certificate is required of every connection.
   */
  def start(tls: RotatingTls, port: Int, documents: Documents): ObserveServer =
    val server = HttpsServer.create(InetSocketAddress("0.0.0.0", port), 0)
    server.setHttpsConfigurator(
      new HttpsConfigurator(Rotating(tls)):
        // Restated per connection: the default parameters the server would otherwise apply carry
        // no client-certificate requirement, whatever the engine was built with.
        override def configure(params: HttpsParameters): Unit =
          val parameters = getSSLContext.getDefaultSSLParameters
          parameters.setNeedClientAuth(true)
          parameters.setProtocols(Array("TLSv1.3"))
          params.setSSLParameters(parameters)
    )
    server.createContext(
      "/observability/service",
      exchange => serve(exchange, "/observability/service", documents.service())
    )
    server.createContext(
      "/observability/topology",
      exchange => serve(exchange, "/observability/topology", documents.topology())
    )
    // Feature 041: `?since=<instant>`, what a restore to that moment could not rewind.
    server.createContext(
      "/observability/divergence",
      exchange =>
        val since = Option(exchange.getRequestURI.getQuery).toVector
          .flatMap(_.split('&'))
          .collectFirst { case q if q.startsWith("since=") => q.stripPrefix("since=") }
          .flatMap(t =>
            scala.util.Try(java.time.Instant.parse(java.net.URLDecoder.decode(t, "UTF-8"))).toOption
          )
        since match
          case None =>
            ObservabilityEndpoint.respondError(exchange, 400, "since=<an instant> is required")
          case Some(at) =>
            serve(exchange, "/observability/divergence", documents.divergence(at))
    )
    server.setExecutor(null)
    server.start()
    new ObserveServer(server)

  /** Exactly the route, read with `GET`; a context's other paths are not routes. */
  private def serve(exchange: HttpExchange, path: String, body: => String): Unit =
    if exchange.getRequestURI.getPath.stripSuffix("/") != path then
      ObservabilityEndpoint.respondError(exchange, 404, "no such route")
    else if exchange.getRequestMethod != "GET" then
      ObservabilityEndpoint.respondError(exchange, 405, s"$path is read with GET")
    else ObservabilityEndpoint.respond(exchange, body)

  /**
   * An `SSLContext` the JDK's server can hold for its lifetime that answers from whatever
   * `RotatingTls` currently holds: the server asks it for an engine per connection, so a renewed
   * certificate reaches the next connection.
   */
  private final class Rotating(tls: RotatingTls)
      extends SSLContext(Spi(tls), tls.sslContext.getProvider, "TLS")

  private final class Spi(tls: RotatingTls) extends SSLContextSpi:
    override def engineInit(
        km: Array[KeyManager],
        tm: Array[TrustManager],
        sr: SecureRandom
    ): Unit = throw KeyManagementException("a rotating context is initialised by its files")
    override def engineGetSocketFactory: SSLSocketFactory = tls.sslContext.getSocketFactory
    override def engineGetServerSocketFactory: SSLServerSocketFactory =
      tls.sslContext.getServerSocketFactory
    override def engineCreateSSLEngine(): SSLEngine = tls.sslContext.createSSLEngine()
    override def engineCreateSSLEngine(host: String, port: Int): SSLEngine =
      tls.sslContext.createSSLEngine(host, port)
    override def engineGetServerSessionContext: SSLSessionContext =
      tls.sslContext.getServerSessionContext
    override def engineGetClientSessionContext: SSLSessionContext =
      tls.sslContext.getClientSessionContext
