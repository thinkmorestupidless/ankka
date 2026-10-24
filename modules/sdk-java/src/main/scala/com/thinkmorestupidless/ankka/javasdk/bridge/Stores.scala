package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.ServiceIdentity
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.{
  Bucket,
  BucketExposure,
  ObjectEntry,
  ObjectInfo,
  ObjectStorage,
  SignedAccess,
  StoredObject
}
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk

import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.{List as JList, Optional}
import scala.jdk.CollectionConverters.*

/** The service's secret store and buckets, and who it is, as a Java service sees them. */
private[ankka] object Stores:

  def secrets(scala: sdk.SecretStore): SecretStore = new SecretStore:
    def put(name: String, value: String): Unit = javaFailures(scala.put(name, value))
    def get(name: String): Optional[String]    = optional(javaFailures(scala.get(name)))
    def delete(name: String): Unit             = javaFailures(scala.delete(name))

  def storage(scala: sdk.ObjectStorage): ObjectStorage = new ObjectStorage:
    def defaultBucket(): Bucket      = JavaBucket(javaFailures(scala.default))
    def bucket(name: String): Bucket = JavaBucket(javaFailures(scala.bucket(name)))
    def buckets(): JList[Bucket]     = scala.buckets.map(b => JavaBucket(b): Bucket).asJava

  val noSecrets: SecretStore   = secrets(sdk.SecretStore.unavailable)
  val noStorage: ObjectStorage = storage(sdk.ObjectStorage.unavailable)

  def identity(scala: Either[String, runtime.ServiceIdentity]): ServiceIdentity = scala match
    case Right(id) => ServiceIdentity(optional(id.project), optional(id.service))
    case Left(why) => throw IllegalStateException(s"the service's identity could not be read: $why")

  private[bridge] def optional[A](o: Option[A]): Optional[A] =
    o.fold(Optional.empty[A]())(Optional.of(_))

  /** A Scala rejection, as the `CommandException` a Java caller catches. */
  private[bridge] def javaFailures[A](call: => A): A =
    try call
    catch case e: com.thinkmorestupidless.ankka.core.CommandError => throw Clients.javaFailure(e)

private final class JavaBucket(scala: sdk.Bucket) extends Bucket:
  import Stores.{javaFailures, optional}

  def name(): Optional[String]   = optional(scala.name)
  def exposure(): BucketExposure = BucketExposure.valueOf(scala.exposure.toString.toUpperCase)

  def put(name: String, body: InputStream, length: Long, contentType: String): Unit =
    javaFailures(scala.put(name, body, length, contentType))
  def putBytes(name: String, body: Array[Byte], contentType: String): Unit =
    javaFailures(scala.putBytes(name, body, contentType))

  def get(name: String): Optional[StoredObject] =
    optional(
      javaFailures(scala.get(name)).map(o =>
        StoredObject(o.contentType, o.length, o.lastKept, o.body)
      )
    )
  def getBytes(name: String): Optional[Array[Byte]] =
    optional(javaFailures(scala.getBytes(name)).map(_._2))

  def delete(name: String): Unit = javaFailures(scala.delete(name))

  def list(prefix: String): JList[ObjectEntry] =
    javaFailures(scala.list(prefix))
      .map(e => ObjectEntry(e.name, e.length, e.lastKept))
      .toVector
      .asJava

  def stat(name: String): Optional[ObjectInfo] =
    optional(
      javaFailures(scala.stat(name)).map(i =>
        ObjectInfo(i.name, i.contentType, i.length, i.lastKept)
      )
    )

  def signedUrl(name: String, access: SignedAccess, span: Duration): URI =
    javaFailures(scala.signedUrl(name, scalaAccess(access), Effects.finite(span)))
  def signedUrl(name: String, access: SignedAccess): URI =
    javaFailures(scala.signedUrl(name, scalaAccess(access)))

  def url(name: String): URI = javaFailures(scala.url(name))

  private def scalaAccess(access: SignedAccess): sdk.SignedAccess = access match
    case SignedAccess.READ => sdk.SignedAccess.Read
    case SignedAccess.KEEP => sdk.SignedAccess.Keep
