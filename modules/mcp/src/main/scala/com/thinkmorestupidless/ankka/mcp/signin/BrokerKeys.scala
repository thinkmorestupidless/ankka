package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore}
import org.slf4j.LoggerFactory

import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, KeyPairGenerator, PrivateKey, PublicKey, SecureRandom}
import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * The sign-in broker's signing keys on one instance (feature 054, R11).
 *
 * The public halves are the `ankka-sign-in-keys` entity's; each private half is in the service's
 * secret store under `ankka-sign-in-key-<kid>`, written before its key is added and deleted after
 * it is removed. A key is minted when there is none and every `rotateEvery`, and removed `keepFor`
 * after it was minted, the newest never.
 *
 * Held in memory once read: a private key read from the store on every token would be a recorded
 * read per sign-in, and a public key looked up per request would put the entity on the request
 * path. A key id this instance has not seen is looked up again at most once a second, so a key
 * minted on another instance verifies here as soon as its event is read.
 */
final class BrokerKeys(
    client: ComponentClient,
    secrets: SecretStore,
    clock: Clock,
    rotateEvery: FiniteDuration,
    keepFor: FiniteDuration
):
  import BrokerKeys.*

  private val log         = LoggerFactory.getLogger(classOf[BrokerKeys])
  private val privates    = ConcurrentHashMap[String, PrivateKey]()
  private val publics     = AtomicReference[Map[String, PublicKey]](Map.empty)
  private val lastLooked  = AtomicLong(Long.MinValue)
  private val mintingLock = Object()

  private def entity = client.forEventSourcedEntity(EntityId(SignInKeysEntity.Id))

  private def held(): SignInKeys = entity.call(SignInKeysEntity.get).invoke()

  /** The newest key and its private half, minting one when there is none or the newest is due. */
  def signing(): (String, PrivateKey) =
    val now  = clock.instant()
    val keys = held()
    remember(keys)
    keys.newest.filter(k => k.createdAt.plusMillis(rotateEvery.toMillis).isAfter(now)) match
      case Some(key) => key.kid -> privateKey(key.kid)
      case None      => mintingLock.synchronized(mint(now))

  /**
   * The public key for `kid`, looked up again at most once a second when this instance lacks it.
   */
  def publicKey(kid: String): Option[PublicKey] =
    publics.get.get(kid).orElse {
      val now  = clock.millis()
      val last = lastLooked.get
      if (last == Long.MinValue || now - last >= 1000L) && lastLooked.compareAndSet(last, now) then
        try remember(held())
        catch
          case NonFatal(e) =>
            log.warn("the sign-in broker's keys could not be read: {}", e.toString)
      publics.get.get(kid)
    }

  /** The live public keys as a JWK set. */
  def jwks: Json =
    val keys = held()
    remember(keys)
    Json.obj("keys" -> Json.Arr(keys.keys.flatMap(k => Json.parse(k.publicKeyJwk).toOption)))

  /**
   * Mints a key when the newest is due, and removes every key minted more than `keepFor` ago but
   * the newest. What `McpRuntime` runs hourly on every instance; a race between two mints only adds
   * a key.
   */
  def rotate(): Unit =
    val now  = clock.instant()
    val keys = held()
    if keys.newest.forall(k => !k.createdAt.plusMillis(rotateEvery.toMillis).isAfter(now)) then
      mintingLock.synchronized(mint(now)): Unit
    val after = held()
    after.keys
      .dropRight(1)
      .filter(k => !k.createdAt.plusMillis(keepFor.toMillis).isAfter(now))
      .foreach { key =>
        entity.call(SignInKeysEntity.remove).invoke(key.kid)
        secrets.delete(SecretPrefix + key.kid)
        privates.remove(key.kid)
      }
    remember(held())

  private def mint(now: Instant): (String, PrivateKey) =
    // Another thread may have minted while this one waited.
    val again = held()
    again.newest.filter(k => k.createdAt.plusMillis(rotateEvery.toMillis).isAfter(now)) match
      case Some(key) => key.kid -> privateKey(key.kid)
      case None      =>
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val kid  = kidAt(now)
        // The private half first: a key the journal names whose private half is nowhere could
        // never sign, and every instance would keep choosing it.
        secrets.put(SecretPrefix + kid, pem(pair.getPrivate))
        val jwk = Jwt.jwk(kid, pair.getPublic.asInstanceOf[RSAPublicKey]).render
        entity.call(SignInKeysEntity.add).invoke(SignInKey(kid, jwk, now))
        privates.put(kid, pair.getPrivate)
        publics.updateAndGet(_ + (kid -> pair.getPublic))
        kid -> pair.getPrivate

  private def privateKey(kid: String): PrivateKey =
    privates.computeIfAbsent(
      kid,
      _ =>
        secrets
          .get(SecretPrefix + kid)
          .map(fromPem)
          .getOrElse(
            throw IllegalStateException(s"the sign-in broker's key $kid has no private half")
          )
    )

  private def remember(keys: SignInKeys): Unit =
    publics.set(
      keys.keys
        .flatMap(k => Json.parse(k.publicKeyJwk).toOption.flatMap(Jwt.publicKey).map(k.kid -> _))
        .toMap
    )

object BrokerKeys:

  /** Where a key's private half is kept: the platform's own name, never a service's. */
  val SecretPrefix: String = "ankka-sign-in-key-"

  private val stamp  = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
  private val random = SecureRandom()

  /** `yyyyMMddHHmmss` and four hex digits: ordered by when, and two minted at once differ. */
  def kidAt(at: Instant): String =
    f"${stamp.format(at)}${random.nextInt(0x10000)}%04x"

  def pem(key: PrivateKey): String =
    val body = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(key.getEncoded)
    s"-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"

  def fromPem(text: String): PrivateKey =
    val body = text.linesIterator.filterNot(_.startsWith("-----")).mkString
    KeyFactory
      .getInstance("RSA")
      .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(body)))
