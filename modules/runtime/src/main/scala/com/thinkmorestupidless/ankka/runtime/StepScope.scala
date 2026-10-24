package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.{
  Bucket,
  BucketExposure,
  ObjectEntry,
  ObjectInfo,
  ObjectStorage,
  SecretStore,
  ServiceClient,
  ServiceClients,
  ServiceResponse,
  SignedAccess,
  StorageRefusals,
  StoredObject
}

import java.io.InputStream
import java.net.URI
import scala.concurrent.duration.FiniteDuration

/**
 * Whether the calling thread is running a workflow's step.
 *
 * A workflow's command handlers and its steps share one context, so its type cannot keep the secret
 * store from a command. The mark can: the engine sets it around a step's body, on the step's own
 * virtual thread, and a command handler runs on the actor's thread, where it is never set. Work a
 * step hands to another thread does not carry the mark, as it does not carry any thread-local.
 */
private[ankka] object StepScope:

  private val inStep = ThreadLocal.withInitial[java.lang.Boolean](() => false)

  def within[A](body: => A): A =
    inStep.set(true)
    try body
    finally inStep.set(false)

  def active: Boolean = inStep.get

  /** A store that answers only from inside a step. */
  def stepsOnly(underlying: SecretStore): SecretStore = new SecretStore:
    private def require(): Unit =
      if !active then
        throw CommandError(
          "a workflow reads and keeps a service secret in a step, not in a command handler",
          ErrorCode.BadRequest
        )
    def put(name: String, value: String): Unit = { require(); underlying.put(name, value) }
    def get(name: String): Option[String]      = { require(); underlying.get(name) }
    def delete(name: String): Unit             = { require(); underlying.delete(name) }

  /**
   * Clients for other services that make a call only from inside a step. Obtaining one anywhere is
   * harmless; a request made outside a step is refused, for the reason a secret is.
   */
  def stepsOnly(underlying: ServiceClients): ServiceClients = new ServiceClients:
    def apply(name: String): ServiceClient                    = guarded(underlying(name))
    def apply(project: String, name: String): ServiceClient   = guarded(underlying(project, name))
    private def guarded(client: ServiceClient): ServiceClient = new ServiceClient:
      def target: String = client.target
      def request(
          method: String,
          path: String,
          body: Option[Array[Byte]],
          contentType: Option[String],
          headers: Seq[(String, String)]
      ): ServiceResponse =
        if !active then
          throw CommandError(
            "a workflow calls another service in a step, not in a command handler",
            ErrorCode.BadRequest
          )
        client.request(method, path, body, contentType, headers)

  /**
   * A storage client whose buckets answer only from inside a step (feature 055). Naming a bucket
   * anywhere is harmless; every operation outside a step is refused, a signed URL and an address
   * included, for the reason a secret is: a workflow's command handler is its single-writer path.
   */
  def stepsOnly(underlying: ObjectStorage): ObjectStorage = new ObjectStorage:
    def default: Bucket              = guarded(underlying.default)
    def bucket(name: String): Bucket = guarded(underlying.bucket(name))
    def buckets: Vector[Bucket]      = underlying.buckets.map(guarded)

    private def guarded(b: Bucket): Bucket = new Bucket:
      private def require(): Unit  = if !active then throw StorageRefusals.stepsOnly()
      def name: Option[String]     = b.name
      def exposure: BucketExposure = b.exposure
      def put(name: String, body: InputStream, length: Long, contentType: String): Unit =
        require()
        b.put(name, body, length, contentType)
      def get(name: String): Option[StoredObject]     = { require(); b.get(name) }
      def delete(name: String): Unit                  = { require(); b.delete(name) }
      def list(prefix: String): Iterator[ObjectEntry] = { require(); b.list(prefix) }
      def stat(name: String): Option[ObjectInfo]      = { require(); b.stat(name) }
      def signedUrl(name: String, access: SignedAccess, span: FiniteDuration): URI =
        require()
        b.signedUrl(name, access, span)
      def url(name: String): URI = { require(); b.url(name) }
