package com.thinkmorestupidless.ankka.proxy

import com.sun.net.httpserver.{HttpsConfigurator, HttpsParameters}
import com.thinkmorestupidless.ankka.runtime.RotatingTls

import java.security.SecureRandom
import javax.net.ssl.*

/**
 * The JDK's HTTPS server, given the platform's rotating certificate.
 *
 * `HttpsServer` takes one `SSLContext` and keeps it, so a context built once would serve the
 * certificate it started with until the process ended, while cert-manager renews the files under it
 * every eight hours. This context holds nothing: every engine it makes is `RotatingTls`'s own
 * server engine, made from the files as they are when the connection arrives, so a renewal reaches
 * the next connection as it does everywhere else in the platform.
 */
object RotatingServerTls:

  /** What the server is configured with: TLS 1.3 and a client certificate, on every connection. */
  def configurator(tls: RotatingTls): HttpsConfigurator =
    new HttpsConfigurator(context(tls)):
      override def configure(parameters: HttpsParameters): Unit =
        // The default would replace the engine's parameters with the context's defaults, which do
        // not require a client certificate: say again what `serverEngine` says.
        val ssl = tls.sslContext.getDefaultSSLParameters
        ssl.setNeedClientAuth(true)
        ssl.setProtocols(Array("TLSv1.3"))
        parameters.setSSLParameters(ssl)

  def context(tls: RotatingTls): SSLContext = Rotating(tls)

  private final class Rotating(tls: RotatingTls) extends SSLContext(Spi(tls), null, "TLS")

  private final class Spi(tls: RotatingTls) extends SSLContextSpi:
    override protected def engineInit(
        km: Array[KeyManager],
        tm: Array[TrustManager],
        random: SecureRandom
    ): Unit = ()
    override protected def engineGetSocketFactory(): SSLSocketFactory =
      tls.sslContext.getSocketFactory
    override protected def engineGetServerSocketFactory(): SSLServerSocketFactory =
      tls.sslContext.getServerSocketFactory
    override protected def engineCreateSSLEngine(): SSLEngine = tls.serverEngine()
    override protected def engineCreateSSLEngine(host: String, port: Int): SSLEngine =
      tls.serverEngine()
    override protected def engineGetServerSessionContext(): SSLSessionContext =
      tls.sslContext.getServerSessionContext
    override protected def engineGetClientSessionContext(): SSLSessionContext =
      tls.sslContext.getClientSessionContext
    override protected def engineGetDefaultSSLParameters(): SSLParameters =
      tls.sslContext.getDefaultSSLParameters
    override protected def engineGetSupportedSSLParameters(): SSLParameters =
      tls.sslContext.getSupportedSSLParameters
