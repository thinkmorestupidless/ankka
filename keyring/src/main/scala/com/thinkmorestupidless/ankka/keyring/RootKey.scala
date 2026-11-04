package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.PersonalCipher
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.secrets.AccessTokens
import com.thinkmorestupidless.ankka.sdk.SecretStore

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/**
 * The key that wraps every project's key-encryption key (feature 042, R11, R18). On a Postgres
 * installation, 32 bytes in the keyring's own secret store (038's `postgres` backend), made at the
 * first start; on Google Cloud, the KMS key of the keyring's `wrapping-key` request, which never
 * leaves KMS. `ref` names which, and is kept beside each wrapped KEK.
 */
trait RootKey:
  def ref: String
  def wrap(plain: Array[Byte], ad: Array[Byte]): Array[Byte]

  /** The unwrapped bytes; fails when `wrapped` was not wrapped by this root under `ad`. */
  def unwrap(wrapped: Array[Byte], ad: Array[Byte]): Array[Byte]

object RootKey:

  /** The secret's name in the keyring's own store. */
  val SecretName = "keyring-root-key"

  /** A root key held in memory: what the Postgres root is once read. */
  final class Local(key: IArray[Byte], val ref: String) extends RootKey:
    def wrap(plain: Array[Byte], ad: Array[Byte]): Array[Byte] =
      PersonalCipher.seal(key, None, plain, ad)
    def unwrap(wrapped: Array[Byte], ad: Array[Byte]): Array[Byte] =
      PersonalCipher
        .open(key, wrapped, ad)
        .getOrElse(throw IllegalStateException(s"a key did not unwrap under the root key $ref"))

  /**
   * The root key in `secrets`, made when there is none. Two replicas starting at once agree on one:
   * the read and the make happen under an advisory lock of `database`, the store's own.
   */
  def postgres(secrets: SecretStore, database: Database, timeout: FiniteDuration = 30.seconds)(using
      ExecutionContext
  ): RootKey =
    def read(): Option[IArray[Byte]] =
      secrets.get(SecretName).map(text => IArray.unsafeFromArray(Base64.getDecoder.decode(text)))
    val key = Await.result(
      database.inTransaction { tx =>
        tx.query(SqlFragment.raw("SELECT pg_advisory_xact_lock(hashtext('ankka-keyring-root'))"))(
          _ => ()
        ).flatMap(_ =>
          Future(read().getOrElse {
            val made = new Array[Byte](32)
            SecureRandom().nextBytes(made)
            secrets.put(SecretName, Base64.getEncoder.encodeToString(made))
            read().getOrElse(throw IllegalStateException("the root key was not kept"))
          })(using com.thinkmorestupidless.ankka.runtime.AnkkaExecutors.virtual)
        )
      },
      timeout
    )
    Local(key, s"postgres:$SecretName")

  /** Encrypts and decrypts with one KMS key, the key never read. */
  trait Kms:
    def encrypt(plain: Array[Byte], ad: Array[Byte]): Array[Byte]
    def decrypt(ciphertext: Array[Byte], ad: Array[Byte]): Array[Byte]

  /** The root key that is the KMS key named `keyName`. */
  def kms(keyName: String, client: Kms): RootKey = new RootKey:
    val ref                                                        = s"kms:$keyName"
    def wrap(plain: Array[Byte], ad: Array[Byte]): Array[Byte]     = client.encrypt(plain, ad)
    def unwrap(wrapped: Array[Byte], ad: Array[Byte]): Array[Byte] = client.decrypt(wrapped, ad)

  private final case class EncryptBody(plaintext: String, additionalAuthenticatedData: String)
  private final case class EncryptReply(ciphertext: String)
  private final case class DecryptBody(ciphertext: String, additionalAuthenticatedData: String)
  private final case class DecryptReply(plaintext: String)
  private given JsonValueCodec[EncryptBody]  = JsonCodecMaker.make
  private given JsonValueCodec[EncryptReply] = JsonCodecMaker.make
  private given JsonValueCodec[DecryptBody]  = JsonCodecMaker.make
  private given JsonValueCodec[DecryptReply] = JsonCodecMaker.make

  /**
   * Cloud KMS's REST `encrypt` and `decrypt` on `keyName`
   * (`projects/…/locations/…/keyRings/…/cryptoKeys/…`), as the pod's own identity.
   */
  final class CloudKms(
      keyName: String,
      tokens: AccessTokens = AccessTokens.metadata(),
      base: String = "https://cloudkms.googleapis.com/v1",
      http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
  ) extends Kms:
    private val b64 = Base64.getEncoder
    private val unb = Base64.getDecoder

    private def call(verb: String, body: String): String =
      val request = HttpRequest
        .newBuilder(URI.create(s"$base/$keyName:$verb"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", s"Bearer ${tokens.token()}")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode / 100 != 2 then
        throw IllegalStateException(
          s"KMS refused to $verb with $keyName: ${response.statusCode} ${response.body.take(300)}"
        )
      response.body

    def encrypt(plain: Array[Byte], ad: Array[Byte]): Array[Byte] =
      val reply = call(
        "encrypt",
        writeToString(EncryptBody(b64.encodeToString(plain), b64.encodeToString(ad)))
      )
      unb.decode(readFromString[EncryptReply](reply).ciphertext)

    def decrypt(ciphertext: Array[Byte], ad: Array[Byte]): Array[Byte] =
      val reply =
        call(
          "decrypt",
          writeToString(DecryptBody(b64.encodeToString(ciphertext), b64.encodeToString(ad)))
        )
      unb.decode(readFromString[DecryptReply](reply).plaintext)
