package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.sdk.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.net.URI
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

/**
 * A service's buckets in memory: the test kit's store (feature 055, research R13), so a test of a
 * handler that keeps or reads an object needs no object store and no Docker.
 *
 * It applies every rule the other stores apply, in the same words: names and prefixes, the declared
 * length, a signed URL only from a signed bucket and an address only from a public one, and a
 * bucket the service does not declare refused naming those it does. A signed URL is computed
 * (`http://memory/...`) and reaches nothing; a public address is `publicBase(bucket)/name`.
 *
 * @param declared
 *   the named buckets and their exposure; more may be declared while the service runs
 * @param unnamed
 *   whether the service has the unnamed bucket `provisionObjectStorage` asks for
 * @param unnamedExposure
 *   the unnamed bucket's exposure: signed, so a test may sign a URL for it as a service that sets
 *   `exposeObjectStorage` would
 */
final class MemoryObjectStorage(
    declared: Map[String, BucketExposure],
    unnamed: Boolean = true,
    publicBase: String => String = MemoryObjectStorage.DefaultPublicBase,
    unnamedExposure: BucketExposure = BucketExposure.Signed,
    clock: () => Instant = () => Instant.now()
) extends ObjectStorage:
  import MemoryObjectStorage.Stored

  private val exposures = TrieMap.from(declared)
  private val objects   = TrieMap.empty[(Option[String], String), Stored]

  def default: Bucket =
    if unnamed then MemoryBucket(None, unnamedExposure) else throw StorageRefusals.noBucket()

  def bucket(name: String): Bucket =
    exposures.get(name) match
      case Some(exposure) => MemoryBucket(Some(name), exposure)
      case None           => throw StorageRefusals.unknown(name, exposures.keys)

  def buckets: Vector[Bucket] = exposures.toVector.map((n, e) => MemoryBucket(Some(n), e))

  /** Declares a named bucket, before or after the service starts. */
  def declare(name: String, exposure: BucketExposure): Unit =
    ObjectRules.bucketNameProblem(name).foreach(p => throw IllegalArgumentException(p))
    exposures.update(name, exposure)

  /** Forgets every object; the declared buckets stay. */
  def clear(): Unit = objects.clear()

  private final case class MemoryBucket(name: Option[String], exposure: BucketExposure)
      extends Bucket:

    def put(objectName: String, body: InputStream, length: Long, contentType: String): Unit =
      ObjectRules.checkName(objectName)
      if length < 0 then throw StorageRefusals.negativeLength(length)
      val out    = new ByteArrayOutputStream(math.min(length, 1L << 20).toInt)
      val buffer = new Array[Byte](64 * 1024)
      var read   = 0L
      var ended  = false
      while !ended && read < length do
        val n = body.read(buffer, 0, math.min(buffer.length.toLong, length - read).toInt)
        if n < 0 then ended = true
        else
          out.write(buffer, 0, n)
          read += n
      if read < length then throw StorageRefusals.shortBody(length, read)
      objects.update((name, objectName), Stored(contentType, clock(), out.toByteArray))

    def get(objectName: String): Option[StoredObject] =
      ObjectRules.checkName(objectName)
      objects
        .get((name, objectName))
        .map(s =>
          StoredObject(
            s.contentType,
            s.bytes.length.toLong,
            s.lastKept,
            new ByteArrayInputStream(s.bytes)
          )
        )

    def delete(objectName: String): Unit =
      ObjectRules.checkName(objectName)
      objects.remove((name, objectName)): Unit

    def list(prefix: String): Iterator[ObjectEntry] =
      ObjectRules.checkPrefix(prefix)
      objects.iterator
        .collect {
          case ((b, n), s) if b == name && n.startsWith(prefix) =>
            ObjectEntry(n, s.bytes.length.toLong, s.lastKept)
        }
        .toVector
        .sortBy(_.name)
        .iterator

    def stat(objectName: String): Option[ObjectInfo] =
      ObjectRules.checkName(objectName)
      objects
        .get((name, objectName))
        .map(s => ObjectInfo(objectName, s.contentType, s.bytes.length.toLong, s.lastKept))

    def signedUrl(objectName: String, access: SignedAccess, span: FiniteDuration): URI =
      ObjectRules.checkName(objectName)
      exposure match
        case BucketExposure.Signed | BucketExposure.Supplied => ()
        case other                                           =>
          throw name.fold(StorageRefusals.notExposed())(b => StorageRefusals.notSigned(b, other))
      ObjectRules.checkSpan(span)
      val expires = clock().plusSeconds(span.toSeconds).getEpochSecond
      URI.create(
        s"http://memory/${name.getOrElse("default")}/${SigV4.encode(objectName, encodeSlash = false)}" +
          s"?access=${access.toString.toLowerCase(java.util.Locale.ROOT)}&expires=$expires"
      )

    def url(objectName: String): URI =
      ObjectRules.checkName(objectName)
      (name, exposure) match
        case (Some(b), BucketExposure.Public) =>
          URI.create(
            publicBase(b).stripSuffix("/") + "/" + SigV4.encode(objectName, encodeSlash = false)
          )
        case _ => throw StorageRefusals.notPublic(name, exposure)

object MemoryObjectStorage:

  /** Where a public bucket's objects are read in a test: computed, and reaching nothing. */
  val DefaultPublicBase: String => String = bucket => s"http://memory/public/$bucket"

  private final case class Stored(contentType: String, lastKept: Instant, bytes: Array[Byte])
