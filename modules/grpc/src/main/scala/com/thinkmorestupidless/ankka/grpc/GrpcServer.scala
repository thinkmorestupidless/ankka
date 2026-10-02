package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.EndpointClients
import com.thinkmorestupidless.ankka.runtime.{
  DeclaredGrpc,
  AnkkaExecutors,
  AnkkaService,
  RotatingTls,
  RuntimeExtension
}
import com.typesafe.config.Config
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.{InsecureServerCredentials, Server, ServerCredentials, TlsServerCredentials}
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, MINUTES}

/**
 * Serves a set of gRPC endpoints, on a port of the service's own beside its HTTP port.
 *
 * Registered like any extension, and like `HttpServer` it takes factories rather than endpoints,
 * because an endpoint needs the clients only a started service has:
 *
 * {{{
 * Ankka.service
 *   .register(ShoppingCartEntity.descriptor)
 *   .withExtension(GrpcServer.of(clients => CartGrpcEndpoint(clients)))
 * }}}
 *
 * Every endpoint is checked when the service starts, and every problem is reported at once: a
 * method of a service definition with no handler, a handler declared as the wrong kind of method, a
 * method declared twice or not the definition's, two endpoints for one definition.
 */
final class GrpcServer private (
    factories: Seq[EndpointClients => GrpcEndpoint],
    interface: Option[String],
    port: Option[Int],
    tlsDirectory: Option[Path] = None
) extends RuntimeExtension:

  /**
   * This server alone serves mutual TLS from `directory`, whatever the configuration says — for a
   * suite that needs a caller read from a real certificate without a cluster.
   */
  private[ankka] def withTls(directory: Path): GrpcServer =
    new GrpcServer(factories, interface, port, Some(directory))

  private val log = LoggerFactory.getLogger(classOf[GrpcServer])

  @volatile private var server: Option[Server] = None
  @volatile private var grace: FiniteDuration  = FiniteDuration(5000, MILLISECONDS)

  def name: String = GrpcServer.Name

  def start(service: AnkkaService): Unit =
    val config  = service.system.settings.config
    val clients = EndpointClients(service.componentClient, service.viewClient, service.services)
    serve(
      factories.map(_(clients)).toVector,
      interface.getOrElse(config.getString("ankka.grpc.interface")),
      port.getOrElse(config.getInt("ankka.grpc.port")),
      config,
      Some(Materializer(service.system))
    )

  /** Validates, logs and binds `endpoints`: everything `start` does once the endpoints exist. */
  private[grpc] def serve(
      endpoints: Vector[GrpcEndpoint],
      host: String,
      bindPort: Int,
      config: Config,
      materializer: Option[Materializer] = None
  ): Unit =
    GrpcServer.validate(endpoints)
    grace = duration(config, "ankka.grpc.shutdown-grace")

    val tls       = serviceTls(config)
    val admission = tls.fold(Admission.local)(Admission.under)
    val credentials: ServerCredentials = tls match
      case None           => InsecureServerCredentials.create()
      case Some(identity) =>
        // The managers follow rotation: built once here, each handshake presents the certificate
        // cert-manager wrote most recently.
        TlsServerCredentials
          .newBuilder()
          .keyManager(identity.keyManager)
          .trustManager(identity.trustManager)
          .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
          .build()

    val builder = NettyServerBuilder
      .forAddress(InetSocketAddress(host, bindPort), credentials)
      .executor(AnkkaExecutors.virtual.execute(_))
      .maxInboundMessageSize(config.getBytes("ankka.grpc.max-message-size").toInt)
      .fallbackHandlerRegistry(Binding.fallback(endpoints, admission))
    endpoints.foreach(endpoint =>
      builder.addService(Binding.definition(endpoint, admission, materializer))
    )

    val started =
      try builder.build().start()
      catch
        case failure: IOException =>
          throw IllegalStateException(
            s"cannot serve gRPC on $host:$bindPort: ${failure.getMessage}",
            failure
          )
    server = Some(started)

    endpoints.foreach(
      _.methods.foreach(method =>
        log.info("grpc {} ({})", method.fullName, method.kind.declaration)
      )
    )
    log.info(
      "ankka grpc listening on {}:{}{}",
      host,
      started.getPort,
      if tls.isDefined then " with mutual TLS" else ""
    )
    if tls.isEmpty && admission.namesCallers(endpoints) then
      log.info("caller identity is not enforced outside a cluster: every call is Caller.Local")

  /**
   * The service certificate when this process serves mutual TLS — the Kubernetes overlay's setting,
   * shared with the HTTP server, never a local run's. A missing file fails startup naming it.
   */
  private def serviceTls(config: Config): Option[RotatingTls] =
    val enabled =
      config.hasPath("ankka.http.tls.enabled") && config.getBoolean("ankka.http.tls.enabled")
    tlsDirectory
      .map(RotatingTls(_, FiniteDuration(1, MINUTES)))
      .orElse(Option.when(enabled) {
        val directory = config.getString("ankka.tls.service-directory")
        if directory.isEmpty then
          throw IllegalStateException(
            "ankka.http.tls.enabled is on but ankka.tls.service-directory is empty"
          )
        RotatingTls(Paths.get(directory), duration(config, "ankka.tls.reload-interval"))
      })

  private def duration(config: Config, path: String): FiniteDuration =
    FiniteDuration(config.getDuration(path).toMillis, MILLISECONDS)

  /** Not ready until bound: a member that cannot yet answer a call must not receive one. */
  override def readiness: Option[() => Boolean] = Some(() => server.isDefined)

  /** The bound port, useful when the configured port was 0. */
  def boundPort: Option[Int] = server.map(_.getPort)

  /**
   * Where a service on this machine calls this one's gRPC: loopback, since a caller on the same
   * machine is the only one that reads it. Not `boundAddress`, which every reader takes for HTTP.
   */
  override def grpcAddress: Option[String] = boundPort.map(port => s"127.0.0.1:$port")

  /**
   * Stops accepting calls, gives the calls in progress the shutdown grace, then ends what is left
   * as unavailable.
   */
  override def stop(): Unit =
    server.foreach { s =>
      s.shutdown()
      if !s.awaitTermination(grace.toMillis, TimeUnit.MILLISECONDS) then
        s.shutdownNow()
        s.awaitTermination(5, TimeUnit.SECONDS): Unit
    }
    server = None

object GrpcServer:

  /**
   * The extension's name, which the runtime's check for a declared but unserved gRPC port reads.
   */
  val Name: String = DeclaredGrpc.ServerName

  /** Serves `factories` on the configured interface and port. */
  def of(factories: (EndpointClients => GrpcEndpoint)*): GrpcServer =
    new GrpcServer(factories, None, None)

  /** Serves on an explicit interface and port; port 0 picks a free one. */
  def at(interface: String, port: Int)(factories: (EndpointClients => GrpcEndpoint)*): GrpcServer =
    new GrpcServer(factories, Some(interface), Some(port))

  /**
   * Every problem with `endpoints`, reported together, so a service that cannot be served says
   * everything wrong with it at once rather than one thing per restart.
   */
  private[grpc] def validate(endpoints: Vector[GrpcEndpoint]): Unit =
    val problems = Vector.newBuilder[String]

    endpoints.groupBy(_.service.getName).foreach { (name, sharing) =>
      if sharing.sizeIs > 1 then
        problems += s"${sharing.size} endpoints implement the service definition '$name'"
    }

    endpoints.foreach { endpoint =>
      val definition = endpoint.service.getName
      if endpoint.acl == null then problems += s"the endpoint for '$definition' states no acl"
      val offered = endpoint.service.getMethods.toArray.toVector.collect {
        case m: io.grpc.MethodDescriptor[?, ?] => m
      }
      val declared = endpoint.methods

      declared.groupBy(_.fullName).foreach { (name, twice) =>
        if twice.sizeIs > 1 then problems += s"'$name' is declared ${twice.size} times"
      }
      declared.foreach { method =>
        if !offered.exists(_.getFullMethodName == method.fullName) then
          problems += s"'${method.fullName}' is not a method of the service definition '$definition'"
        val kind = MethodKind.of(method.descriptor)
        if kind != method.kind then
          problems += s"'${method.fullName}' is declared with ${method.kind.declaration} but is a " +
            s"${kind.declaration} method; declare it with ${kind.declaration}"
      }
      offered.foreach { method =>
        if !declared.exists(_.fullName == method.getFullMethodName) then
          problems += s"the service definition '$definition' has a method '${method.getFullMethodName}' " +
            s"with no handler; declare it with ${MethodKind.of(method).declaration}"
      }
    }

    val found = problems.result()
    if found.nonEmpty then
      throw IllegalArgumentException(
        found.mkString("invalid ankka grpc configuration:\n  - ", "\n  - ", "")
      )
