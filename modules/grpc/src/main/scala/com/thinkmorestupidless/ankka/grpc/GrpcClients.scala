package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.runtime.{
  AnkkaService,
  HttpServiceClients,
  RotatingTls,
  RuntimeExtension,
  ServiceRegistration
}
import com.thinkmorestupidless.ankka.sdk.{
  ServiceIdentityMismatch,
  ServiceServesNoGrpc,
  ServiceUnresolvable
}
import com.typesafe.config.Config
import io.grpc.*

import java.net.{InetAddress, InetSocketAddress, SocketAddress, URI}
import java.nio.file.Paths
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import javax.net.ssl.SSLHandshakeException
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Channels to other services' gRPC endpoints, for a service to call them as itself.
 *
 * Created once and handed to whatever needs it, like everything a component uses — ankka has no
 * container to find it in:
 *
 * {{{
 * val grpc = GrpcClients()
 * Ankka.service
 *   .register(…)
 *   .withExtension(grpc)
 *   .withExtension(HttpServer.of(clients => CheckoutEndpoint(clients.componentClient, grpc)))
 *
 * // in a handler:
 * val cart = CartServiceGrpc.blockingStub(grpc("cart")).getCart(GetCartRequest(cartId))
 * }}}
 *
 * In a cluster a call presents this service's own certificate, so the called service's ACL sees who
 * is calling, and it is only ever sent to a workload whose certificate names the service asked for.
 * Calls are balanced across the called service's ready instances one by one, not by connection.
 * Outside a cluster the called service is found where `ankka.local-grpc-services."<name>"` says, or
 * where it announced itself while running on this machine, and is called in plaintext.
 *
 * A channel is resolved on its first call and kept. A call that cannot be made says why:
 * `ServiceUnresolvable`, `ServiceServesNoGrpc`, or — the called workload's certificate naming
 * another service — a call ended `UNAVAILABLE` whose cause is `ServiceIdentityMismatch`. A refusal
 * by the called service arrives as a `StatusRuntimeException` whose cause is the matching
 * `CommandError`, so a handler that lets it pass answers its own caller with the same refusal.
 */
final class GrpcClients private (locate: Option[GrpcClients.Locate]) extends RuntimeExtension:

  import GrpcClients.*

  def name: String = "grpc-clients"

  @volatile private var setting: Option[Setting] = None
  private val channels = ConcurrentHashMap[(String, String), ManagedChannel]()

  def start(service: AnkkaService): Unit = configure(service.system.settings.config): Unit

  /** For suites that call a server of their own, with no service around them. */
  private[ankka] def configure(config: Config): GrpcClients =
    val directory = config.getString("ankka.tls.service-directory")
    val tls = Option.when(directory.nonEmpty)(
      RotatingTls(
        Paths.get(directory),
        FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, MILLISECONDS)
      )
    )
    setting = Some(Setting(config, tls))
    this

  /** A service of this service's own project. */
  def apply(name: String): Channel = apply(ownProject, name)

  /** A service of another project; whether it admits this one is its own ACL's decision. */
  def apply(project: String, name: String): Channel = Lazy(project, name)

  override def stop(): Unit =
    channels.values.asScala.foreach(c => Try(c.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)))
    channels.clear()

  private def current: Setting =
    setting.getOrElse(
      throw IllegalStateException("GrpcClients is used before the service has started")
    )

  private def ownProject: String =
    current.tls.flatMap(_.identity).map(_.project).getOrElse("local")

  /** A channel that resolves the service it names on its first call, and keeps what it found. */
  private final class Lazy(project: String, name: String) extends Channel:
    def authority(): String = channelFor(project, name).authority()
    def newCall[Q, R](method: MethodDescriptor[Q, R], options: CallOptions): ClientCall[Q, R] =
      channelFor(project, name).newCall(method, options)

  private def channelFor(project: String, name: String): ManagedChannel =
    Option(channels.get((project, name))).getOrElse {
      val built = build(project, name)
      Option(channels.putIfAbsent((project, name), built)).fold(built) { other =>
        built.shutdownNow()
        other
      }
    }

  private def build(project: String, name: String): ManagedChannel =
    val setting = current
    val target  = s"$project/$name"
    val located = locate.fold(defaultLocate(setting))(identity)(project, name) match
      case Left(failure) => throw failure
      case Right(found)  => found
    val credentials: ChannelCredentials = setting.tls match
      case None => InsecureChannelCredentials.create()
      case Some(tls) =>
        TlsChannelCredentials
          .newBuilder()
          .keyManager(tls.keyManager)
          .trustManager(tls.trustManagerRequiring(s"ankka://$project/$name"))
          .build()
    val builder = located match
      case Located.Dns(dns, authority) =>
        Grpc.newChannelBuilder(dns, credentials).overrideAuthority(authority)
      case Located.Addresses(addresses, authority) =>
        withAddresses(Grpc.newChannelBuilder(s"$StaticScheme:///$name", credentials), addresses)
          .overrideAuthority(authority)
    builder
      .defaultLoadBalancingPolicy("round_robin")
      .intercept(Outcomes(target))
      .build()

  /** Kubernetes DNS in a cluster; the developer's setting, then the running service, locally. */
  private def defaultLocate(setting: Setting): Locate = (project, name) =>
    setting.tls match
      case Some(_) =>
        val host = HttpServiceClients.hostOf(project, name)
        HttpServiceClients.srvPort(host, "grpc") match
          case Some(port) =>
            val peers = host.replaceFirst(s"^$name\\.", s"$name-grpc-peers.")
            Right(Located.Dns(s"dns:///$peers:$port", host))
          case None =>
            if Try(InetAddress.getByName(host)).isSuccess then
              Left(ServiceServesNoGrpc(s"$project/$name"))
            else Left(ServiceUnresolvable(s"$project/$name", s"no service at $host"))
      case None =>
        val key = s"ankka.local-grpc-services.\"$name\""
        Option
          .when(setting.config.hasPath(key))(setting.config.getString(key))
          .orElse(ServiceRegistration.grpcAddressOf(name)) match
          case Some(address) =>
            addressOf(address).map(a => Located.Addresses(Vector(a), "localhost"))
          case None =>
            if ServiceRegistration.isAnnounced(name) then Left(ServiceServesNoGrpc(name))
            else
              Left(
                ServiceUnresolvable(
                  name,
                  s"not in ankka.local-grpc-services and not announced in ${ServiceRegistration.directory}"
                )
              )

object GrpcClients:

  /** Channels to other services, resolved as the service's own configuration says. */
  def apply(): GrpcClients = new GrpcClients(None)

  /** Where `project/name` is, or why it cannot be called. Overridden only by suites. */
  private[ankka] type Locate = (String, String) => Either[Throwable, Located]

  private[ankka] def locatedBy(locate: Locate): GrpcClients = new GrpcClients(Some(locate))

  private[ankka] enum Located:
    /**
     * A DNS target, resolved and re-resolved by grpc-java, and the name a certificate must carry.
     */
    case Dns(target: String, authority: String)

    /** Fixed addresses: a service on this machine, or a suite's servers. */
    case Addresses(addresses: Vector[SocketAddress], authority: String)

  private final case class Setting(config: Config, tls: Option[RotatingTls])

  private val StaticScheme = "ankka-static"

  private def addressOf(hostPort: String): Either[Throwable, SocketAddress] =
    Try {
      val uri = URI(s"tcp://$hostPort")
      InetSocketAddress(uri.getHost, uri.getPort): SocketAddress
    }.toEither.left.map(e => IllegalArgumentException(s"not a host and port: '$hostPort'", e))

  @annotation.nowarn("cat=deprecation")
  private def withAddresses(
      builder: ManagedChannelBuilder[?],
      addresses: Vector[SocketAddress]
  ): ManagedChannelBuilder[?] =
    // A resolver that answers the same addresses for ever. grpc-java's registry has no such thing,
    // and a fixed list is what a service on this machine, or a suite's servers, are.
    builder.nameResolverFactory(new NameResolver.Factory:
      def getDefaultScheme: String = StaticScheme
      def newNameResolver(uri: URI, args: NameResolver.Args): NameResolver =
        new NameResolver:
          def getServiceAuthority: String = "localhost"
          override def start(listener: NameResolver.Listener2): Unit =
            listener.onResult(
              NameResolver.ResolutionResult
                .newBuilder()
                .setAddresses(addresses.map(a => EquivalentAddressGroup(a)).asJava)
                .build()
            )
          def shutdown(): Unit = ())

  /**
   * How a call to another service ended, said in ankka's terms: a refusal carries its
   * `CommandError` as the status's cause, and a handshake refused because the workload is not the
   * service asked for carries `ServiceIdentityMismatch`.
   */
  private final class Outcomes(target: String) extends ClientInterceptor:
    def interceptCall[Q, R](
        method: MethodDescriptor[Q, R],
        options: CallOptions,
        next: Channel
    ): ClientCall[Q, R] =
      new ForwardingClientCall.SimpleForwardingClientCall[Q, R](next.newCall(method, options)):
        override def start(listener: ClientCall.Listener[R], headers: Metadata): Unit =
          super.start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener[R](listener):
              override def onClose(status: Status, trailers: Metadata): Unit =
                super.onClose(said(status), trailers)
            ,
            headers
          )

    /**
     * A status the called service sent arrives with no cause; one raised here — a handshake that
     * failed, a connection that broke — carries the local failure. Only the first is a refusal: an
     * `UNAVAILABLE` from a broken connection is not the service saying it is unavailable, and must
     * not reach a handler as if it were.
     */
    private def said(status: Status): Status =
      // The trust manager's reason, wherever in the chain the TLS engine put it: BoringSSL wraps it in
      // a handshake failure whose own message says only "General OpenSslEngine problem".
      val identity =
        if !causes(status.getCause).exists(_.isInstanceOf[SSLHandshakeException]) then None
        else
          causes(status.getCause).collectFirst {
            case failure if Option(failure.getMessage).exists(_.contains("peer identity")) =>
              failure
          }
      identity match
        case Some(handshake) =>
          status.withCause(ServiceIdentityMismatch(target, handshake.getMessage))
        case None if status.getCause == null =>
          GrpcStatus.fromStatus(status).fold(status)(status.withCause)
        case None => status

    private def causes(failure: Throwable): Iterator[Throwable] =
      Iterator.iterate(failure)(_.getCause).takeWhile(_ != null).take(10)
