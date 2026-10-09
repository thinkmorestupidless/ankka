package com.thinkmorestupidless.ankka.operator

import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/**
 * The platform's own bucket (feature 042): the erasure log's second copy, which the control plane
 * writes and the keyring replays when its database was restored. Made by the operator at start when
 * the installation has an object store, with a credential per reader — the control plane and the
 * keyring — written create-only as `ankka-platform-erasure-log` in each one's namespace, each its
 * own key so that issuing one never deletes the other's. The bucket is in the reserved project
 * `platform`, so no service's bucket can be named the same.
 */
object PlatformBucket:

  val Name: String       = "platform.erasure-log"
  val SecretName: String = "ankka-platform-erasure-log"

  /** The namespaces that read the bucket: the control plane writes it, the keyring reads it. */
  val Readers: Vector[String] = Vector("ankka-controlplane", "ankka-keyring")

  private val log = LoggerFactory.getLogger("ankka.operator.platform-bucket")

  /** What a reader's Secret holds beside its key: where the bucket is, as a service is told. */
  def entries(settings: ObjectStoreSettings): Map[String, String] = Map(
    "ANKKA_S3_ENDPOINT" -> settings.endpoint,
    "ANKKA_S3_REGION"   -> settings.region,
    "ANKKA_S3_BUCKET"   -> Name
  )

  /**
   * Makes the bucket if it is not there, and each reader's credential if its Secret is not. A
   * reader whose namespace does not exist — an installation without the keyring — is passed over,
   * and the rest still done; anything else fails the pass, to be tried again.
   */
  def ensure(
      store: ObjectStore,
      credentials: StorageCredential,
      settings: ObjectStoreSettings,
      namespaceExists: String => Boolean
  ): Unit =
    if store.bucket(Name).isEmpty then
      store.createBucket(Name): Unit
      log.info("made the platform bucket {}", Name)
    Readers.filter(namespaceExists).foreach { namespace =>
      credentials.ensure(
        namespace,
        SecretName,
        Map("app.kubernetes.io/managed-by" -> "ankka"),
        Name,
        keyName = Some(s"$Name.$namespace"),
        extra = entries(settings)
      ) match
        case StorageCredential.Result.Created =>
          log.info("issued the platform bucket's credential {}/{}", namespace, SecretName)
        case StorageCredential.Result.Replaced =>
          log.warn(
            "the object store held no key for {}/{}; a new one was written",
            namespace,
            SecretName
          )
        case StorageCredential.Result.Unchanged => ()
    }

  /**
   * Tries `ensure` until it succeeds, every `every`, on a daemon thread of its own: the store may
   * come up after the operator, and nothing a service does waits on this bucket.
   */
  def keepTrying(attempt: () => Unit, every: java.time.Duration): Thread =
    Thread
      .ofPlatform()
      .daemon()
      .name("ankka-platform-bucket")
      .start(() =>
        var done = false
        while !done && !Thread.currentThread().isInterrupted do
          try
            attempt()
            done = true
          catch
            case NonFatal(e) =>
              log.warn("the platform bucket is not ready yet; trying again: {}", e.getMessage)
              try Thread.sleep(every.toMillis)
              catch case _: InterruptedException => Thread.currentThread().interrupt()
      )
