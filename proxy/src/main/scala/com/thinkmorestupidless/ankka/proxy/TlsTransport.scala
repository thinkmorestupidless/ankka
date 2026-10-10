package com.thinkmorestupidless.ankka.proxy

import com.sun.net.httpserver.{HttpExchange, HttpServer, HttpsExchange, HttpsServer}
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.proxy.core.{
  Answer,
  Answers,
  CallingAddress,
  ProxyEngine,
  Sender,
  Transport
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls

import java.io.IOException
import java.net.http.HttpClient
import java.net.{InetAddress, InetSocketAddress}
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.{ConcurrentHashMap, Executors}
import javax.net.ssl.{SSLContext, SSLParameters, SSLPeerUnverifiedException}
import scala.util.Try

/**
 * The cluster's half of the proxy: mutual TLS on the public listener, with the platform's rotating
 * certificate, and the sender read from the peer's certificate exactly as every service reads a
 * caller. A connection without a certificate the authority issued never completes its handshake, so
 * `senderOf` only ever sees a certificate of the platform's; one that names nobody the platform
 * knows is refused.
 */
final class TlsTransport(
    tls: RotatingTls,
    bind: InetAddress = ProxyEngine.EveryAddress,
    /** The mount certificate, which a web-hosted service has only while it has mounts. */
    mountTls: Option[RotatingTls] = None
) extends Transport:

  def listener(port: Int): HttpServer =
    val server = HttpsServer.create(new InetSocketAddress(bind, port), 0)
    server.setHttpsConfigurator(RotatingServerTls.configurator(tls))
    server

  /**
   * The client a call to `target` is sent with: this service's certificate, and a check that the
   * service answering holds the identity asked for as well as a certificate naming its host, so a
   * call is never sent to the wrong workload. One per identity, until the certificate rotates.
   */
  override def client(target: CallingAddress.Target): Option[HttpClient] =
    val context = tls.contextRequiring(s"ankka://${target.project}/${target.service}")
    Some(clients.computeIfAbsent(context, TlsTransport.clientFor))

  /**
   * The client a request under a mount is sent with: the mount certificate, which the mounted
   * service reads as the internet, and the same check that the service answering is the one asked
   * for.
   */
  override def mountClient(target: CallingAddress.Target): Option[HttpClient] =
    mountTls.map { mount =>
      val context = mount.contextRequiring(s"ankka://${target.project}/${target.service}")
      clients.computeIfAbsent(context, TlsTransport.clientFor)
    }

  private val clients = new ConcurrentHashMap[SSLContext, HttpClient]()

  /** A handshake refused because the service answering is not the one asked for. */
  override def failure(target: CallingAddress.Target, error: IOException): Option[Answer] =
    Option.when(TlsTransport.wrongIdentity(error))(
      Answers.notTheServiceAskedFor(target.project, target.service)
    )

  def senderOf(exchange: HttpExchange): Either[String, Sender] = exchange match
    case https: HttpsExchange =>
      peerCertificate(https)
        .flatMap { certificate =>
          Caller.fromCertificate(certificate, tls.identity).map(caller => (certificate, caller))
        }
        .flatMap {
          // The gateway and a mount of this project both read as the internet. A mount is another
          // web-hosted service's proxy passing a browser's request on, and it states the address the
          // browser used, which only the platform's proxy can present this certificate to say.
          //
          // From the gateway, the address is the request's own `Host`: the gateway chose this
          // service by that name, and routes a name only to the service that holds it — the derived
          // hostname or one of its custom hostnames (feature 045). So it is the gateway's word, not
          // the request's; the forwarded headers a request carries are still never read.
          case (certificate, Caller.Gateway) =>
            val gateway = RotatingTls.ankkaUris(certificate).contains(RotatingTls.GatewayUri)
            val header  = if gateway then "Host" else "X-Forwarded-Host"
            Right(
              Sender.Internet(Option(https.getRequestHeaders.getFirst(header)).filter(_.nonEmpty))
            )
          case (_, Caller.Service(project, name)) => Right(Sender.Service(project, name))
          // `fromCertificate` never answers Local: a certificate always names someone or is refused.
          case (_, Caller.Local) => Left("unrecognised caller certificate")
        }
    case _ => Left("not a TLS connection")

  private def peerCertificate(exchange: HttpsExchange): Either[String, X509Certificate] =
    Try(exchange.getSSLSession.getPeerCertificates).toEither.left
      .map {
        case _: SSLPeerUnverifiedException => "no client certificate"
        case other                         => s"no client certificate: ${other.getMessage}"
      }
      .flatMap(_.headOption match
        case Some(leaf: X509Certificate) => Right(leaf)
        case _                           => Left("no client certificate"))

object TlsTransport:

  private val executor = Executors.newVirtualThreadPerTaskExecutor()

  private def clientFor(context: SSLContext): HttpClient =
    val params = new SSLParameters()
    params.setEndpointIdentificationAlgorithm("HTTPS")
    params.setProtocols(Array("TLSv1.3"))
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .sslContext(context)
      .sslParameters(params)
      .connectTimeout(Duration.ofSeconds(5))
      .executor(executor)
      .build()

  /**
   * Whether a failure was the identity check refusing the peer. The reason can sit several causes
   * down, beneath a handshake failure whose own message says nothing of it, so the chain is read.
   */
  def wrongIdentity(error: Throwable): Boolean =
    Iterator
      .iterate[Throwable](error)(_.getCause)
      .takeWhile(_ != null)
      .take(10)
      .exists(e => Option(e.getMessage).exists(_.contains("peer identity")))
