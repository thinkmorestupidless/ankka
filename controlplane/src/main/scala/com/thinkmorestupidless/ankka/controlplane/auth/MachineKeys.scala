package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.controlplane.api.{JsonWebKey, JsonWebKeySet}
import org.slf4j.LoggerFactory

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec}
import java.security.{KeyFactory, KeyPairGenerator, PrivateKey, SecureRandom, Signature}
import java.time.format.DateTimeFormatter
import java.time.{Clock, Duration, Instant, LocalDateTime, ZoneOffset}
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Where a machine token's signing keys are written: the Secret the control plane creates in its own
 * namespace, one entry `<kid>.pem` per key. The control plane may `create` and `patch` it and never
 * `get` it; its own pods read it back as files.
 */
trait MachineKeyWriter:
  def addKey(kid: String, pem: String): Unit
  def removeKeys(kids: Seq[String]): Unit

/**
 * The keys machine tokens are signed with (feature 040). Each is RSA-2048 under a `kid` that is its
 * creation time, `yyyyMMddHHmmss`, and four random hex digits, so the newest sorts last. A token is
 * signed with the newest; the key set lists every key held, so a token signed before a rotation
 * still verifies while it lives.
 *
 * With a directory (the Secret, mounted), keys are read from it, again when it changes. With none
 * held, one is made and written, and used from memory until the mount shows it; until then it is in
 * this node's key set alone. Without a directory (a local control plane, a test) keys live in
 * memory. No key is ever in the journal.
 */
final class MachineKeys private (
    directory: Option[Path],
    writer: Option[MachineKeyWriter],
    clock: Clock
):
  import MachineKeys.*

  /** Keys made here and not yet seen in the directory, and every key when there is none. */
  private val own     = AtomicReference[Map[String, Key]](Map.empty)
  private val read    = AtomicReference[(Long, Map[String, Key])]((-1L, Map.empty))
  private val minting = new Object

  /** Every key held, newest last. */
  def keys: Vector[Key] =
    (fromDirectory ++ own.get).values.toVector.sortBy(_.kid)

  /** The key set a verifier fetches: every key held. */
  def jwks: JsonWebKeySet = JsonWebKeySet(keys.map(_.jwk))

  /** A JWS of `claims` (a JSON object's text), signed RS256 with the newest key. */
  def sign(claims: String): String =
    val key = newest
    val header = b64(
      s"""{"alg":"RS256","kid":"${key.kid}","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8)
    )
    val payload = b64(claims.getBytes(StandardCharsets.UTF_8))
    val signer  = Signature.getInstance("SHA256withRSA")
    signer.initSign(key.privateKey)
    signer.update(s"$header.$payload".getBytes(StandardCharsets.US_ASCII))
    s"$header.$payload.${b64(signer.sign())}"

  /** Adds a key, which signs from now on: what a rotation is. Answers its `kid`. */
  def rotate(): String = minting.synchronized(mint().kid)

  /**
   * Drops every key over `KeepFor` old that is not the newest: a key is replaced every thirty days,
   * and a token lives fifteen minutes, so a key that old signed nothing still alive. Safe on every
   * node at once, since each drops the same keys. Answers what it dropped.
   */
  def sweep(): Vector[String] =
    val held   = keys
    val cutoff = clock.instant().minus(KeepFor)
    val old = held.dropRight(1).filter(k => createdAt(k.kid).exists(_.isBefore(cutoff))).map(_.kid)
    if old.nonEmpty then
      writer.foreach(_.removeKeys(old))
      own.updateAndGet(_ -- old): Unit
    old

  /** Whether the newest key is old enough to be replaced. */
  def due: Boolean =
    keys.lastOption
      .flatMap(k => createdAt(k.kid))
      .forall(_.isBefore(clock.instant().minus(RotateEvery)))

  private def newest: Key =
    keys.lastOption.getOrElse(minting.synchronized(keys.lastOption.getOrElse(mint())))

  private def mint(): Key =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    val kid  = kidAt(clock.instant())
    val key  = Key(kid, pair.getPrivate, pair.getPublic.asInstanceOf[RSAPublicKey])
    // Written first: a key the Secret does not hold would be lost with this process.
    writer.foreach(_.addKey(kid, pem(pair.getPrivate)))
    own.updateAndGet(_ + (kid -> key)): Unit
    key

  private def fromDirectory: Map[String, Key] =
    directory match
      case None => Map.empty
      case Some(dir) =>
        val stamp = Try(
          Files
            .list(dir)
            .iterator()
            .asScala
            .map(p => Files.getLastModifiedTime(p).toMillis)
            .maxOption
        ).toOption.flatten.getOrElse(0L)
        val (seen, held) = read.get
        if stamp == seen then held
        else
          val found = Try(
            Files
              .list(dir)
              .iterator()
              .asScala
              .filter(_.getFileName.toString.endsWith(".pem"))
              .flatMap(p =>
                try Some(load(p.getFileName.toString.stripSuffix(".pem"), Files.readString(p)))
                catch
                  case NonFatal(e) =>
                    log.warn("machine key {} cannot be read: {}", p.getFileName, e.toString)
                    None
              )
              .map(k => k.kid -> k)
              .toMap
          ).getOrElse(held)
          // An unreadable directory keeps the keys last read.
          if found.nonEmpty || held.isEmpty then
            read.set((stamp, found))
            own.updateAndGet(_ -- found.keySet): Unit
            found
          else held

object MachineKeys:

  private val log = LoggerFactory.getLogger(classOf[MachineKeys])

  /** A signing key: its id, its private half, and its public half as a key set lists it. */
  final case class Key(kid: String, privateKey: PrivateKey, publicKey: RSAPublicKey):
    def jwk: JsonWebKey =
      JsonWebKey(
        "RSA",
        "sig",
        "RS256",
        kid,
        b64(unsigned(publicKey.getModulus)),
        b64(unsigned(publicKey.getPublicExponent))
      )

  /** How often the signing key is replaced. */
  val RotateEvery: Duration = Duration.ofDays(30)

  /** How long a key that is no longer the newest is kept. */
  val KeepFor: Duration = Duration.ofDays(31)

  private val random    = new SecureRandom()
  private val KidFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  /** Keys in memory alone: a local control plane, a test. */
  def inMemory(clock: Clock = Clock.systemUTC()): MachineKeys = new MachineKeys(None, None, clock)

  /** Keys read from `directory`, the mounted Secret, and written through `writer`. */
  def mounted(
      directory: Path,
      writer: MachineKeyWriter,
      clock: Clock = Clock.systemUTC()
  ): MachineKeys = new MachineKeys(Some(directory), Some(writer), clock)

  def kidAt(at: Instant): String =
    LocalDateTime
      .ofInstant(at, ZoneOffset.UTC)
      .format(KidFormat) + f"${random.nextInt(0x10000)}%04x"

  def createdAt(kid: String): Option[Instant] =
    Try(LocalDateTime.parse(kid.take(14), KidFormat).toInstant(ZoneOffset.UTC)).toOption

  def pem(key: PrivateKey): String =
    val body = Base64
      .getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
      .encodeToString(key.getEncoded)
    s"-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"

  def load(kid: String, pem: String): Key =
    val body    = pem.linesIterator.filterNot(_.startsWith("-----")).mkString
    val factory = KeyFactory.getInstance("RSA")
    val priv    = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(body)))
    val crt     = priv.asInstanceOf[RSAPrivateCrtKey]
    val pub = factory
      .generatePublic(RSAPublicKeySpec(crt.getModulus, crt.getPublicExponent))
      .asInstanceOf[RSAPublicKey]
    Key(kid, priv, pub)

  private def b64(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def unsigned(value: BigInteger): Array[Byte] =
    val bytes = value.toByteArray
    if bytes.length > 1 && bytes(0) == 0 then bytes.drop(1) else bytes
