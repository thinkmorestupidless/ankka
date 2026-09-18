package nakka.cli

import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.net.ssl.{SSLContext, TrustManagerFactory, X509TrustManager}
import scala.jdk.CollectionConverters.*

/**
 * The roots the CLI trusts: the JDK's own, plus every certificate in one PEM file.
 *
 * For a local cluster that is the root `deploy-local.sh` exported — a certificate authority that
 * exists only inside that cluster and that nothing else on the machine has any reason to trust. It
 * is *added*, never substituted, so a real domain keeps verifying against the platform roots; and
 * there is deliberately no way to trust everything: an `insecure` switch is the one thing this file
 * must never grow (feature 005, SC-010).
 *
 * JDK only, on purpose: the CLI depends on `controlplane-api` alone and carries no HTTP library.
 */
object Trust:

  /** An SSL context whose trust store is the platform's roots plus the certificates in `pem`. */
  def sslContext(pem: Path): SSLContext =
    val context = SSLContext.getInstance("TLS")
    context.init(null, Array(trustManager(pem)), null)
    context

  /** The trust manager behind `sslContext(pem)`, so a test can ask it directly. */
  def trustManager(pem: Path): X509TrustManager =
    val store = KeyStore.getInstance(KeyStore.getDefaultType)
    store.load(null, null)
    for (cert, i) <- platformRoots.zipWithIndex do store.setCertificateEntry(s"platform-$i", cert)
    for (cert, i) <- certificates(pem).zipWithIndex do store.setCertificateEntry(s"extra-$i", cert)
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    factory.init(store)
    factory.getTrustManagers.collectFirst { case tm: X509TrustManager => tm }.get

  def certificates(pem: Path): Vector[X509Certificate] =
    val in = Files.newInputStream(pem)
    try
      CertificateFactory
        .getInstance("X.509")
        .generateCertificates(in)
        .asScala
        .toVector
        .collect { case c: X509Certificate => c }
    finally in.close()

  private def platformRoots: Vector[X509Certificate] =
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    factory.init(null: KeyStore)
    factory.getTrustManagers
      .collect { case tm: X509TrustManager => tm.getAcceptedIssuers.toVector }
      .flatten
      .toVector
