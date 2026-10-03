package com.thinkmorestupidless.ankka.proxy

import com.sun.net.httpserver.{HttpExchange, HttpServer, HttpsExchange, HttpsServer}
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.proxy.core.{ProxyEngine, Sender, Transport}
import com.thinkmorestupidless.ankka.runtime.RotatingTls

import java.net.{InetAddress, InetSocketAddress}
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import scala.util.Try

/**
 * The cluster's half of the proxy: mutual TLS on the public listener, with the platform's rotating
 * certificate, and the sender read from the peer's certificate exactly as every service reads a
 * caller. A connection without a certificate the authority issued never completes its handshake, so
 * `senderOf` only ever sees a certificate of the platform's; one that names nobody the platform
 * knows is refused.
 */
final class TlsTransport(tls: RotatingTls, bind: InetAddress = ProxyEngine.EveryAddress)
    extends Transport:

  def listener(port: Int): HttpServer =
    val server = HttpsServer.create(new InetSocketAddress(bind, port), 0)
    server.setHttpsConfigurator(RotatingServerTls.configurator(tls))
    server

  def senderOf(exchange: HttpExchange): Either[String, Sender] = exchange match
    case https: HttpsExchange =>
      peerCertificate(https).flatMap(Caller.fromCertificate).flatMap {
        case Caller.Gateway                => Right(Sender.Internet(None))
        case Caller.Service(project, name) => Right(Sender.Service(project, name))
        // `fromCertificate` never answers Local: a certificate always names someone or is refused.
        case Caller.Local => Left("unrecognised caller certificate")
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
