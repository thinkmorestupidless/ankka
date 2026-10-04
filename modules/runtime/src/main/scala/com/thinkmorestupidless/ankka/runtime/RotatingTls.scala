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
import java.security.{Principal, PrivateKey}
import javax.net.ssl.{
  KeyManagerFactory,
  SSLContext,
  SSLEngine,
  TrustManagerFactory,
  X509ExtendedKeyManager,
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

  /**
   * A client context that accepts only a server carrying `uri` among its `ankka://` identities —
   * for calling one named service and nobody else. The same object until the files change, so a
   * caller can key a connection pool on it and a renewal reaches the next pool.
   */
  def contextRequiring(uri: String): SSLContext =
    val current = refreshed()
    Option(requiring.get((uri, current.certificate))).getOrElse {
      val context = SSLContext.getInstance("TLS")
      context.init(
        current.keyManagers,
        current.trustManagers.map {
          case x: X509ExtendedTrustManager => RequiredIdentityTrustManager(x, uri)
          case other                       => other
        },
        new SecureRandom()
      )
      requiring.put((uri, current.certificate), context)
      context
    }

  private val requiring =
    new java.util.concurrent.ConcurrentHashMap[(String, X509Certificate), SSLContext]()

  /**
   * This identity's key and certificate as a key manager, for a TLS stack that takes managers
   * rather than an `SSLContext` — grpc-java's credentials API is one. One object for the life of
   * this `RotatingTls`: every call asks the material loaded at that moment, so a stack that built
   * its credentials once still presents a renewed certificate on its next handshake.
   */
  lazy val keyManager: X509ExtendedKeyManager = RotatingKeyManager(() => refreshed().keyManagers)

  /**
   * The authority in `ca.crt`, as a trust manager that follows rotation as `keyManager` does. It
   * trusts any certificate the authority issued — whatever `peers` says, which governs only
   * `sslContext` — and leaves who the peer is to whoever reads its certificate.
   */
  lazy val trustManager: X509ExtendedTrustManager =
    RotatingTrustManager(() => refreshed().trustManagers)

  /**
   * `trustManager`, and the server must also carry `uri` among its `ankka://` identities: the
   * manager form of `contextRequiring`, for calling one named service and nobody else.
   */
  def trustManagerRequiring(uri: String): X509ExtendedTrustManager =
    RequiredIdentityTrustManager(trustManager, uri)

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
      if next.certificate ne current.certificate then requiring.clear()
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
      case Peers.Exactly(uri) =>
        trustManagers.getTrustManagers.map {
          case x: X509ExtendedTrustManager => SameIdentityTrustManager(x, Set(uri))
          case other                       => other
        }

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagers.getKeyManagers, trust, new SecureRandom())
    Loaded(
      context,
      chain.head,
      mtimes,
      System.nanoTime(),
      keyManagers.getKeyManagers,
      trustManagers.getTrustManagers
    )

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
     * Only a certificate carrying exactly this `ankka://` identity, in both directions. For a port
     * one named peer may read and nobody else — a workload's observe listener admits the control
     * plane, and the control plane reading it expects the very service it asked for — where the
     * peer is reached by pod IP, so its name is nothing a certificate could be checked against.
     */
    case Exactly(uri: String)

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

  /** Delegates every call to whichever key manager is loaded when it is made. */
  private final class RotatingKeyManager(current: () => Array[javax.net.ssl.KeyManager])
      extends X509ExtendedKeyManager:
    private def delegate: X509ExtendedKeyManager =
      current()
        .collectFirst { case k: X509ExtendedKeyManager => k }
        .getOrElse(
          throw IllegalStateException("the loaded identity has no X.509 key manager")
        )
    override def getClientAliases(t: String, i: Array[Principal]): Array[String] =
      delegate.getClientAliases(t, i)
    override def chooseClientAlias(t: Array[String], i: Array[Principal], s: Socket): String =
      delegate.chooseClientAlias(t, i, s)
    override def getServerAliases(t: String, i: Array[Principal]): Array[String] =
      delegate.getServerAliases(t, i)
    override def chooseServerAlias(t: String, i: Array[Principal], s: Socket): String =
      delegate.chooseServerAlias(t, i, s)
    override def getCertificateChain(alias: String): Array[X509Certificate] =
      delegate.getCertificateChain(alias)
    override def getPrivateKey(alias: String): PrivateKey = delegate.getPrivateKey(alias)
    override def chooseEngineClientAlias(
        t: Array[String],
        i: Array[Principal],
        e: SSLEngine
    ): String =
      delegate.chooseEngineClientAlias(t, i, e)
    override def chooseEngineServerAlias(t: String, i: Array[Principal], e: SSLEngine): String =
      delegate.chooseEngineServerAlias(t, i, e)

  /** Delegates every call to whichever trust manager is loaded when it is made. */
  private final class RotatingTrustManager(current: () => Array[javax.net.ssl.TrustManager])
      extends X509ExtendedTrustManager:
    private def delegate: X509ExtendedTrustManager =
      current()
        .collectFirst { case t: X509ExtendedTrustManager => t }
        .getOrElse(
          throw IllegalStateException("the loaded authority has no X.509 trust manager")
        )
    override def checkClientTrusted(c: Array[X509Certificate], a: String): Unit =
      delegate.checkClientTrusted(c, a)
    override def checkServerTrusted(c: Array[X509Certificate], a: String): Unit =
      delegate.checkServerTrusted(c, a)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      delegate.checkClientTrusted(c, a, s)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      delegate.checkServerTrusted(c, a, s)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      delegate.checkClientTrusted(c, a, e)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      delegate.checkServerTrusted(c, a, e)
    override def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers

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
      checkedAt: Long,
      keyManagers: Array[javax.net.ssl.KeyManager],
      trustManagers: Array[javax.net.ssl.TrustManager]
  )

  /**
   * The authority's verdict — hostname included, since the caller asked for a named host — and then
   * the server must carry exactly the identity asked for. A service reached at the right name but
   * holding another's certificate fails the handshake, before any request is written.
   */
  private final class RequiredIdentityTrustManager(delegate: X509ExtendedTrustManager, uri: String)
      extends X509ExtendedTrustManager:
    private def named(chain: Array[X509Certificate]): Unit =
      val presented = chain.headOption.map(ankkaUris).getOrElse(Vector.empty)
      if !presented.contains(uri) then
        throw CertificateException(
          s"peer identity ${presented.mkString(", ").ifEmpty("(none)")} is not $uri"
        )
    override def checkServerTrusted(c: Array[X509Certificate], a: String): Unit =
      delegate.checkServerTrusted(c, a); named(c)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      delegate.checkServerTrusted(c, a, s); named(c)
    override def checkServerTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      delegate.checkServerTrusted(c, a, e); named(c)
    override def checkClientTrusted(c: Array[X509Certificate], a: String): Unit =
      delegate.checkClientTrusted(c, a)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, s: Socket): Unit =
      delegate.checkClientTrusted(c, a, s)
    override def checkClientTrusted(c: Array[X509Certificate], a: String, e: SSLEngine): Unit =
      delegate.checkClientTrusted(c, a, e)
    override def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers

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

  /** The last segment of a mount's identity: `ankka://<project>/<service>/mount` (feature 021). */
  val MountSegment: String = "mount"

  /**
   * `ankka://<project>/<service>/mount` → the web-hosted service whose proxy passes requests under
   * its mounts on with this certificate; anything else → none. Exactly that shape: no other extra
   * segment, and nothing after it.
   */
  def parseMountUri(text: String): Option[Identity] =
    try
      val uri = URI(text)
      Option(uri.getPath).getOrElse("").stripPrefix("/").split('/') match
        case Array(service, MountSegment)
            if uri.getScheme == "ankka" && Option(uri.getHost).exists(_.nonEmpty) &&
              service.nonEmpty && uri.getQuery == null && uri.getFragment == null =>
          Some(Identity(uri.getHost, service))
        case _ => None
    catch case _: Exception => None

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
