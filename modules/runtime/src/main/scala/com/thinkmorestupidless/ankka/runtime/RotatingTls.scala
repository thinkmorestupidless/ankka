package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.pki.pem.{DERPrivateKeyLoader, PEMDecoder}

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, SecureRandom}
import java.net.Socket
import java.security.cert.CertificateException
import javax.net.ssl.{
  KeyManagerFactory,
  SSLContext,
  SSLEngine,
  TrustManagerFactory,
  X509ExtendedTrustManager
}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * One TLS identity, read from the three files cert-manager writes into a CA-issued Secret —
 * `tls.key`, `tls.crt`, `ca.crt` — and re-read when they change.
 *
 * Every mutual-TLS path in ankka goes through this: the HTTP server, the management server and the
 * bootstrap client that probes it, the service client, the control plane's key fetch. Four paths
 * that each parsed PEM and cached a context would disagree eventually, which is the same reason the
 * runtime and the testkit share one effect interpreter. Cluster remoting is the one exception, and
 * only because Pekko ships an engine that does exactly this job over the same three files.
 *
 * Rotation is by modification time, checked at most once per `reloadInterval` and only when a
 * context is asked for — never on a timer thread, so an idle process does no work and a busy one
 * checks as often as the interval allows. A `WatchService` is not used: the kubelet updates a
 * projected Secret by swapping a symlinked directory, not by rewriting the files, and watch events
 * for that are unreliable.
 *
 * A reload that fails — a half-rotated directory, a key that does not match — keeps the previous
 * context and tries again at the next interval. Serving with yesterday's still-valid certificate is
 * strictly better than refusing every connection because a file was caught mid-swap.
 */
final class RotatingTls(
    val directory: Path,
    reloadInterval: FiniteDuration,
    peers: RotatingTls.Peers = RotatingTls.Peers.Authority
):

  import RotatingTls.*

  @volatile private var loaded: Loaded = load()

  /** The current context, reloaded first if the files changed and the interval has passed. */
  def sslContext: SSLContext = refreshed().context

  /** The certificate this process presents. */
  def current: X509Certificate = refreshed().certificate

  /** This process's own identity, from its certificate's `ankka://` URI, if it has one. */
  def identity: Option[Identity] = identityOf(current)

  /** An engine for accepting a connection: TLS 1.3, a client certificate required. */
  def serverEngine(): SSLEngine =
    val engine = sslContext.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setNeedClientAuth(true)
    engine.setEnabledProtocols(Protocols)
    engine

  /**
   * An engine for opening a connection to `host`, presenting this identity. Endpoint identification
   * is on, so the server's certificate must name `host`.
   */
  def clientEngine(host: String, port: Int): SSLEngine =
    val engine = sslContext.createSSLEngine(host, port)
    engine.setUseClientMode(true)
    engine.setEnabledProtocols(Protocols)
    val params = engine.getSSLParameters
    params.setEndpointIdentificationAlgorithm("HTTPS")
    engine.setSSLParameters(params)
    engine

  private def refreshed(): Loaded =
    val current = loaded
    val now     = System.nanoTime()
    if now - current.checkedAt < reloadInterval.toNanos then current
    else
      val changed =
        try modificationTimes(directory) != current.mtimes
        catch case _: Exception => false
      val next =
        if !changed then current.copy(checkedAt = now)
        else
          try load()
          catch case _: Exception => current.copy(checkedAt = now)
      loaded = next
      next

  private def load(): Loaded =
    val key         = DERPrivateKeyLoader.load(PEMDecoder.decode(read("tls.key")))
    val chain       = certificates(read("tls.crt"))
    val authorities = certificates(read("ca.crt"))
    if chain.isEmpty then
      throw IllegalStateException(s"no certificate in ${directory.resolve("tls.crt")}")
    if authorities.isEmpty then
      throw IllegalStateException(s"no certificate in ${directory.resolve("ca.crt")}")
    // After the reads, so a missing file is reported by name rather than as a bare NoSuchFileException.
    val mtimes = modificationTimes(directory)

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry("ankka", key, Password, chain.toArray)
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagers.init(keyStore, Password)

    val trustStore = KeyStore.getInstance("PKCS12")
    trustStore.load(null, null)
    authorities.zipWithIndex.foreach((ca, i) => trustStore.setCertificateEntry(s"ca-$i", ca))
    val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagers.init(trustStore)
    val trust = peers match
      case Peers.Authority => trustManagers.getTrustManagers
      case Peers.SameIdentity =>
        val own = ankkaUris(chain.head)
        if own.isEmpty then
          throw IllegalStateException(
            s"${directory.resolve("tls.crt")} names no ankka:// identity to require of its peers"
          )
        trustManagers.getTrustManagers.map {
          case x: X509ExtendedTrustManager => SameIdentityTrustManager(x, own.toSet)
          case other                       => other
        }

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagers.getKeyManagers, trust, new SecureRandom())
    Loaded(context, chain.head, mtimes, System.nanoTime())

  private def read(name: String): String =
    val file = directory.resolve(name)
    try Files.readString(file, StandardCharsets.US_ASCII)
    catch
      case _: NoSuchFileException =>
        throw IllegalStateException(
          s"TLS file missing: $file (expected tls.key, tls.crt and ca.crt)"
        )

object RotatingTls:

  /** Which peers a context accepts, beyond "issued by the authority in `ca.crt`". */
  enum Peers:
    /** Any certificate the authority issued; the client also checks the server names its host. */
    case Authority

    /**
     * Only a certificate carrying this process's own `ankka://` identity, in both directions. For
     * cluster traffic, where every node of a service holds the same certificate and a node of any
     * other service is not a peer — the rule Pekko's remoting applies to itself.
     */
    case SameIdentity

  /**
   * The authority's verdict, then one more: the peer's leaf must carry one of `required`'s URIs.
   * Refusing in the trust manager means a foreign peer fails the handshake itself, before any byte
   * of a request is read — which is what "refused" has to mean for a port that answers cluster
   * questions.
   */
  private final class SameIdentityTrustManager(
      delegate: X509ExtendedTrustManager,
      required: Set[String]
  ) extends X509ExtendedTrustManager:
    private def same(chain: Array[X509Certificate]): Unit =
      val presented = chain.headOption.map(ankkaUris).getOrElse(Vector.empty)
      if !presented.exists(required.contains) then
        throw CertificateException(
          s"peer identity ${presented.mkString(", ").ifEmpty("(none)")} is not ${required.mkString(", ")}"
        )

    // Every overload delegates to the two-argument check, which validates the chain and nothing
    // else. The socket and engine overloads would also check the peer's name against the host it was
    // reached at — and Pekko's TLS stage turns that on for a client after the engine is built — but a
    // cluster peer is reached by pod IP, which no certificate names. The identity check below is what
    // replaces it, and it is the stronger of the two.
    override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
      delegate.checkClientTrusted(chain, authType); same(chain)
    override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
      delegate.checkServerTrusted(chain, authType); same(chain)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      checkClientTrusted(c, a)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      checkServerTrusted(c, a)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      checkClientTrusted(c, a)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      checkServerTrusted(c, a)
    override def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers

  extension (s: String) private def ifEmpty(other: String): String = if s.isEmpty then other else s

  /** Where a certificate's `ankka://<project>/<service>` URI says it belongs. */
  final case class Identity(project: String, service: String)

  /** The one identity that is not a service: the installation's gateway. */
  val GatewayUri: String = "ankka://gateway"

  private val Protocols = Array("TLSv1.3")
  private val Password  = Array.emptyCharArray

  private final case class Loaded(
      context: SSLContext,
      certificate: X509Certificate,
      mtimes: Vector[Long],
      checkedAt: Long
  )

  /** Fails naming the directory and the missing file, so a pod that cannot start says why. */
  def apply(
      directory: Path,
      reloadInterval: FiniteDuration,
      peers: Peers = Peers.Authority
  ): RotatingTls =
    new RotatingTls(directory, reloadInterval, peers)

  /** The `ankka://` URIs among a certificate's subject alternative names. */
  def ankkaUris(certificate: X509Certificate): Vector[String] =
    Option(certificate.getSubjectAlternativeNames).toVector
      .flatMap(_.asScala)
      .collect {
        case entry if entry.get(0) == 6 && entry.get(1).toString.startsWith("ankka://") =>
          entry.get(1).toString
      }

  /** `ankka://<project>/<service>` → an identity; anything else, including the gateway, → none. */
  def identityOf(certificate: X509Certificate): Option[Identity] =
    ankkaUris(certificate).flatMap(parseServiceUri).headOption

  def parseServiceUri(text: String): Option[Identity] =
    try
      val uri  = URI(text)
      val path = Option(uri.getPath).getOrElse("").stripPrefix("/")
      if uri.getScheme == "ankka" && Option(uri.getHost).exists(_.nonEmpty) && path.nonEmpty &&
        !path.contains('/')
      then Some(Identity(uri.getHost, path))
      else None
    catch case _: Exception => None

  private def certificates(pem: String): Vector[X509Certificate] =
    val factory = CertificateFactory.getInstance("X.509")
    factory
      .generateCertificates(ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
      .asScala
      .collect { case c: X509Certificate => c }
      .toVector

  private def modificationTimes(directory: Path): Vector[Long] =
    Vector("tls.key", "tls.crt", "ca.crt").map { name =>
      val file = directory.resolve(name)
      // toRealPath: a projected Secret's files are symlinks into a timestamped directory that the
      // kubelet swaps, so the link's own time never changes and the target's does.
      Files.getLastModifiedTime(file.toRealPath()).toMillis
    }
