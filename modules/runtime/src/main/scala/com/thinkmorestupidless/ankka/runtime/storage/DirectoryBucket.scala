package com.thinkmorestupidless.ankka.runtime.storage

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.*

import java.io.{IOException, InputStream}
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, NoSuchFileException, Path, StandardCopyOption}
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * A service's buckets in a directory on a developer's machine (feature 055, research R12): what the
 * storage client keeps when the service runs with no variable of an object store.
 *
 * Each bucket is a directory of its own under `root`, `_default` for the unnamed one (no bucket
 * name can begin with `_`). An object's name is encoded into one file name, so an object `a` and an
 * object `a/b` can both be kept, as a bucket allows and a directory tree would not; a name too long
 * for a file name is kept under its hash. Beside each object, under `meta/`, its name, content
 * type, length and when it was kept. A put writes a temporary file and moves it into place, so a
 * stream that ends short leaves the old object.
 *
 * A signed URL and a public address point at the service's own HTTP port, under `/_ankka/storage`,
 * which `DirectoryStorageEndpoint` serves.
 *
 * @param declared
 *   the buckets `service.json` declares; with none, the unnamed bucket and any name asked for, as
 *   private
 * @param addressBase
 *   the service's own address, known once its HTTP server has bound
 */
final class DirectoryObjectStorage(
    val root: Path,
    declared: Option[LocalBuckets.Declared],
    addressBase: () => String,
    clock: () => Instant = () => Instant.now()
) extends ObjectStorage:
  import DirectoryObjectStorage.{*, given}

  /** The key the store's URLs are signed with, kept in the root so a restart honours them. */
  val signer: DirectorySigner = DirectorySigner.at(root)

  def default: Bucket =
    if declared.forall(_.unnamed) then DirectoryBucket(None, BucketExposure.Signed)
    else throw StorageRefusals.noBucket()

  def bucket(name: String): Bucket =
    declared match
      case Some(d) =>
        d.buckets
          .get(name)
          .map(DirectoryBucket(Some(name), _))
          .getOrElse(throw StorageRefusals.unknown(name, d.buckets.keys))
      case None =>
        ObjectRules
          .bucketNameProblem(name)
          .foreach(p => throw CommandError(p, ErrorCode.BadRequest))
        DirectoryBucket(Some(name), BucketExposure.Private)

  def buckets: Vector[Bucket] =
    declared.toVector.flatMap(_.buckets.toVector.map((n, e) => DirectoryBucket(Some(n), e)))

  /**
   * The bucket a URL's segment names: `_default` or a bucket's name, as this store would answer it.
   */
  def bySegment(segment: String): Option[DirectoryBucket] =
    if segment == DefaultSegment then
      scala.util.Try(default).toOption.collect { case b: DirectoryBucket => b }
    else scala.util.Try(bucket(segment)).toOption.collect { case b: DirectoryBucket => b }

  final case class DirectoryBucket(name: Option[String], exposure: BucketExposure) extends Bucket:

    val segment: String       = name.getOrElse(DefaultSegment)
    private val objects: Path = root.resolve(segment).resolve("objects")
    private val metas: Path   = root.resolve(segment).resolve("meta")
    private def file(objectName: String): String =
      val encoded = SigV4.encode(objectName, encodeSlash = true)
      if encoded.length <= 200 then encoded
      else "h-" + SigV4.hex(SigV4.sha256(objectName.getBytes(UTF_8)))

    /** The object's file, where the endpoint streams it from. */
    private[storage] def objectFile(objectName: String): Path = objects.resolve(file(objectName))

    def put(objectName: String, body: InputStream, length: Long, contentType: String): Unit =
      ObjectRules.checkName(objectName)
      if length < 0 then throw StorageRefusals.negativeLength(length)
      val scratch = root.resolve(".tmp")
      io {
        Files.createDirectories(objects)
        Files.createDirectories(metas)
        Files.createDirectories(scratch)
      }: Unit
      val part = scratch.resolve(UUID.randomUUID().toString + ".part")
      try
        val written = io(
          Using.resource(Files.newOutputStream(part))(out => transfer(body, out, length))
        )
        if written < length then throw StorageRefusals.shortBody(length, written)
        val meta     = Meta(objectName, contentType, length, clock().toString)
        val metaPart = scratch.resolve(UUID.randomUUID().toString + ".meta")
        io {
          Files.write(metaPart, writeToArray(meta))
          Files.move(
            part,
            objectFile(objectName),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
          Files.move(
            metaPart,
            metas.resolve(file(objectName) + ".json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
        }: Unit
      finally Files.deleteIfExists(part): Unit

    def get(objectName: String): Option[StoredObject] =
      ObjectRules.checkName(objectName)
      meta(objectName).flatMap { m =>
        try
          Some(
            StoredObject(
              m.contentType,
              m.length,
              Instant.parse(m.lastKept),
              Files.newInputStream(objectFile(objectName))
            )
          )
        catch
          case _: NoSuchFileException => None
          case e: IOException         => throw failed(e)
      }

    def delete(objectName: String): Unit =
      ObjectRules.checkName(objectName)
      io {
        Files.deleteIfExists(metas.resolve(file(objectName) + ".json"))
        Files.deleteIfExists(objectFile(objectName))
      }: Unit

    def list(prefix: String): Iterator[ObjectEntry] =
      ObjectRules.checkPrefix(prefix)
      if !Files.isDirectory(metas) then Iterator.empty
      else
        val found = io(Using.resource(Files.list(metas))(_.iterator.asScala.toVector))
        found
          .flatMap(p => scala.util.Try(readFromArray[Meta](Files.readAllBytes(p))).toOption)
          .filter(_.name.startsWith(prefix))
          .sortBy(_.name)
          .map(m => ObjectEntry(m.name, m.length, Instant.parse(m.lastKept)))
          .iterator

    def stat(objectName: String): Option[ObjectInfo] =
      ObjectRules.checkName(objectName)
      meta(objectName).map(m =>
        ObjectInfo(m.name, m.contentType, m.length, Instant.parse(m.lastKept))
      )

    def signedUrl(objectName: String, access: SignedAccess, span: FiniteDuration): URI =
      ObjectRules.checkName(objectName)
      exposure match
        case BucketExposure.Signed | BucketExposure.Supplied => ()
        case other =>
          throw name.fold(StorageRefusals.notExposed())(b => StorageRefusals.notSigned(b, other))
      ObjectRules.checkSpan(span)
      val expires = clock().getEpochSecond + span.toSeconds
      val word    = accessWord(access)
      val token   = signer.token(segment, objectName, word, expires)
      URI.create(s"${address(objectName)}?access=$word&expires=$expires&token=$token")

    def url(objectName: String): URI =
      ObjectRules.checkName(objectName)
      if exposure == BucketExposure.Public && name.nonEmpty then URI.create(address(objectName))
      else throw StorageRefusals.notPublic(name, exposure)

    private def address(objectName: String): String =
      s"${addressBase().stripSuffix("/")}$PathPrefix/$segment/${SigV4.encode(objectName, encodeSlash = false)}"

    private def meta(objectName: String): Option[Meta] =
      val at = metas.resolve(file(objectName) + ".json")
      try Some(readFromArray[Meta](Files.readAllBytes(at)))
      catch
        case _: NoSuchFileException => None
        case e: IOException         => throw failed(e)

  private def transfer(in: InputStream, out: java.io.OutputStream, length: Long): Long =
    val buffer  = new Array[Byte](64 * 1024)
    var written = 0L
    var ended   = false
    while !ended && written < length do
      val n = in.read(buffer, 0, math.min(buffer.length.toLong, length - written).toInt)
      if n < 0 then ended = true
      else
        out.write(buffer, 0, n)
        written += n
    written

  private def io[A](body: => A): A =
    try body
    catch case e: IOException => throw failed(e)

  private def failed(e: IOException): CommandError =
    CommandError(
      s"the directory store at $root cannot be used: ${e.getMessage}",
      ErrorCode.Unavailable
    )

object DirectoryObjectStorage:

  /** Where the service serves its directory store's objects. */
  val PathPrefix: String = "/_ankka/storage"

  /** The unnamed bucket's directory and URL segment: no bucket name begins with `_`. */
  val DefaultSegment: String = "_default"

  def accessWord(access: SignedAccess): String = access.toString.toLowerCase(java.util.Locale.ROOT)

  /**
   * The store at `root`, made and proved writable, or why it cannot be: the reason a service on a
   * developer's machine does not start.
   */
  def open(
      root: Path,
      declared: Option[LocalBuckets.Declared],
      addressBase: () => String
  ): Either[String, DirectoryObjectStorage] =
    try
      Files.createDirectories(root)
      val probe = Files.createTempFile(root, ".probe", "")
      Files.delete(probe)
      Right(DirectoryObjectStorage(root, declared, addressBase))
    catch
      case e: Exception =>
        Left(
          s"the directory store $root cannot be written: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
        )

  private final case class Meta(name: String, contentType: String, length: Long, lastKept: String)
  private given JsonValueCodec[Meta] = JsonCodecMaker.make

/**
 * Signs and checks the directory store's URLs:
 * `HMAC-SHA256(key, "<bucket>/<name>|<access>|<expires>")` in hex, over a key made once and kept in
 * the store's root, readable by its owner only.
 */
final class DirectorySigner private (key: Array[Byte]):

  def token(segment: String, objectName: String, access: String, expires: Long): String =
    SigV4.hex(SigV4.hmac(key, s"$segment/$objectName|$access|$expires"))

  /** Whether `token` signs this access until `expires`, and `expires` is still to come. */
  def admits(
      segment: String,
      objectName: String,
      access: String,
      expires: Long,
      token: String,
      now: Instant
  ): Boolean =
    expires >= now.getEpochSecond &&
      MessageDigest.isEqual(
        token.getBytes(UTF_8),
        this.token(segment, objectName, access, expires).getBytes(UTF_8)
      )

object DirectorySigner:

  def at(root: Path): DirectorySigner =
    val file = root.resolve(".key")
    if Files.isRegularFile(file) then DirectorySigner(Files.readAllBytes(file))
    else
      val key = new Array[Byte](32)
      SecureRandom().nextBytes(key)
      Files.createDirectories(root)
      val part = root.resolve(s".key-${UUID.randomUUID()}")
      Files.write(part, key)
      scala.util.Try(
        Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rw-------"))
      ): Unit
      try Files.move(part, file, StandardCopyOption.ATOMIC_MOVE)
      catch case _: java.nio.file.FileAlreadyExistsException => Files.deleteIfExists(part): Unit
      DirectorySigner(Files.readAllBytes(file))
