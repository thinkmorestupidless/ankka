package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.duration.*

/**
 * A service's buckets, reached by name (feature 055): `context.storage.default` is the bucket
 * `provisionObjectStorage` asks for, and `context.storage.bucket("photos")` one the descriptor's
 * `buckets` list names.
 *
 * Offered where a blocking call belongs, as the secret store is: endpoints (`clients.storage`),
 * workflow steps, consumers, timed actions and agents, and never an entity or a view. An entity
 * keeps an object's *name*; the bucket keeps the bytes.
 *
 * Every call blocks the calling thread until the store has answered (the components that hold a
 * client run on virtual threads) and throws `CommandError`: `BadRequest` for a name, a prefix, a
 * length or a span that breaks a rule, and for a signed URL or an address the bucket's exposure
 * does not allow; `Unavailable` when the store cannot be reached, when the service has no such
 * bucket, or when it has no storage credential to sign with; `Internal` for an answer the client
 * cannot read.
 */
trait ObjectStorage:

  /**
   * The service's unnamed bucket. Refuses every call, naming `provisionObjectStorage`, when it has
   * none.
   */
  def default: Bucket

  /**
   * A bucket the descriptor's `buckets` list names. Refused, naming the ones it does name,
   * otherwise.
   */
  def bucket(name: String): Bucket

  /** Every named bucket the service has, in no particular order. */
  def buckets: Vector[Bucket]

/**
 * One bucket. Its objects are named with `/` between segments, so a listing by prefix reads like a
 * directory; it is not one. There is no directory to make or remove, and no rename, copy, append or
 * partial write: a put replaces the whole object or leaves the old one.
 */
trait Bucket:

  /** The descriptor's name for it; `None` for the unnamed bucket. */
  def name: Option[String]

  def exposure: BucketExposure

  /**
   * Keep `length` bytes read from `body` under `name`, with `contentType`, replacing what was
   * there. A stream that ends before `length` fails and keeps nothing; the old object, if any,
   * stays. The stream is read once and not closed.
   */
  def put(name: String, body: InputStream, length: Long, contentType: String): Unit

  /** Keep `body` under `name`: `put` for an object already in memory. */
  def putBytes(name: String, body: Array[Byte], contentType: String): Unit =
    put(name, new ByteArrayInputStream(body), body.length.toLong, contentType)

  /**
   * The object kept under `name`, as a stream the caller must close, or `None` when there is none.
   */
  def get(name: String): Option[StoredObject]

  /** The object kept under `name` read whole, with its content type, or `None`. */
  def getBytes(name: String): Option[(String, Array[Byte])] =
    get(name).map(o => scala.util.Using.resource(o)(o => (o.contentType, o.body.readAllBytes())))

  /** Remove what is kept under `name`. Removing nothing is not an error. */
  def delete(name: String): Unit

  /**
   * Every object whose name begins with `prefix`, in the order the store keeps names. The client
   * pages behind the iterator, so a listing of a million objects is read as it is walked.
   */
  def list(prefix: String): Iterator[ObjectEntry]

  /** What is kept under `name`, without its bytes, or `None` when there is nothing. */
  def stat(name: String): Option[ObjectInfo]

  /**
   * A URL a browser may use, until `span` has passed, to read (`Read`) or keep (`Keep`) the object
   * under `name`, made by computation alone: no request reaches the store. A signed bucket's, or
   * the unnamed bucket's when the service sets `exposeObjectStorage`; at most seven days.
   */
  def signedUrl(
      name: String,
      access: SignedAccess,
      span: FiniteDuration = ObjectRules.DefaultSignedUrl
  ): URI

  /**
   * The address a browser reads the object under `name` at, with no signature: a public bucket's
   * only. Computed, never fetched, so it is answered for a name nothing is kept under yet.
   */
  def url(name: String): URI

/** How a bucket is reached from outside the installation. */
enum BucketExposure:
  /** Inside the installation only. */
  case Private

  /** From the internet, through URLs the service signs. */
  case Signed

  /** Every object readable from the internet without a signature. */
  case Public

  /** A bucket the developer made in a store of their own; reached as that store allows. */
  case Supplied

  /** The word the descriptor and the variables use. */
  def word: String = toString.toLowerCase(java.util.Locale.ROOT)

object BucketExposure:
  def fromWord(word: String): Option[BucketExposure] = values.find(_.word == word)

/** What a signed URL lets its holder do with one object. */
enum SignedAccess:
  case Read, Keep

/**
 * An object read from a bucket. Its `body` must be closed; closing it early releases the
 * connection.
 */
final case class StoredObject(
    contentType: String,
    length: Long,
    lastKept: Instant,
    body: InputStream
) extends AutoCloseable:
  def close(): Unit = body.close()

/** What a bucket keeps under a name, without the bytes. */
final case class ObjectInfo(name: String, contentType: String, length: Long, lastKept: Instant)

/** One object in a listing. */
final case class ObjectEntry(name: String, length: Long, lastKept: Instant)

object ObjectStorage:

  /**
   * A client that refuses every call, naming `provisionObjectStorage`: a service with no bucket.
   */
  val unavailable: ObjectStorage = new ObjectStorage:
    def default: Bucket              = throw StorageRefusals.noBucket()
    def bucket(name: String): Bucket = throw StorageRefusals.unknown(name, Vector.empty)
    def buckets: Vector[Bucket]      = Vector.empty

/**
 * What the storage client says when it refuses, said once so the runtime's stores, the test doubles
 * and the directory store's endpoint use the same words.
 */
object StorageRefusals:

  def noBucket(): CommandError =
    CommandError(
      "this service has no bucket: it asks for one with provisionObjectStorage, or names one in buckets",
      ErrorCode.Unavailable
    )

  def unknown(name: String, known: Iterable[String]): CommandError =
    val names = known.toVector.sorted
    val named =
      if names.isEmpty then "names none" else names.map(n => s"'$n'").mkString("names ", ", ", "")
    CommandError(
      s"this service has no bucket named '$name'; its descriptor $named",
      ErrorCode.Unavailable
    )

  def noCredential(): CommandError =
    CommandError(
      "this service has a bucket and no storage credential to sign with: it sets " +
        "objectStorageCredential to false, so it reaches its bucket as its workload identity, with " +
        "its store's own client",
      ErrorCode.Unavailable
    )

  def notExposed(): CommandError =
    CommandError(
      "the service's bucket is not reachable from the internet, so it signs no URL: the descriptor " +
        "sets exposeObjectStorage, or names a signed bucket",
      ErrorCode.BadRequest
    )

  def notSigned(bucket: String, exposure: BucketExposure): CommandError =
    CommandError(
      s"bucket '$bucket' is ${exposure.word}; a signed URL is made for a signed bucket",
      ErrorCode.BadRequest
    )

  def notPublic(bucket: Option[String], exposure: BucketExposure): CommandError =
    val label = bucket.fold("the service's unnamed bucket")(b => s"bucket '$b'")
    CommandError(
      s"$label is ${exposure.word}; an address with no signature is a public bucket's",
      ErrorCode.BadRequest
    )

  def unreachable(endpoint: String, cause: String): CommandError =
    CommandError(s"the object store at $endpoint cannot be reached: $cause", ErrorCode.Unavailable)

  def refusedByStore(endpoint: String, status: Int, detail: String): CommandError =
    CommandError(
      s"the object store at $endpoint refused the request ($status)${
          if detail.isEmpty then "" else ": " + detail
        }",
      ErrorCode.Unavailable
    )

  def unreadable(endpoint: String, cause: String): CommandError =
    CommandError(
      s"the object store at $endpoint answered with something unreadable: $cause",
      ErrorCode.Internal
    )

  def shortBody(declared: Long, read: Long): CommandError =
    CommandError(
      s"the stream ended after $read of its declared $declared bytes; nothing was kept",
      ErrorCode.BadRequest
    )

  def negativeLength(length: Long): CommandError =
    CommandError(s"an object's length is not negative; this one is $length", ErrorCode.BadRequest)

  def stepsOnly(): CommandError =
    CommandError(
      "a workflow keeps and reads an object in a step, not in a command handler",
      ErrorCode.BadRequest
    )

/**
 * What an object's name, a prefix, a bucket's name and a signed URL's span may be. Every store and
 * every test double applies these, and `protocol/fixtures/storage/rules.json` holds them to the
 * other SDKs.
 */
object ObjectRules:

  val MaxNameBytes: Int                = 1024
  val MaxBucketName: Int               = 32
  val MaxSignedUrl: FiniteDuration     = 7.days
  val DefaultSignedUrl: FiniteDuration = 15.minutes

  val BucketNameRule: String  = "lower-case letters, digits and '-', 1 to 32 characters"
  private val ValidBucketName = "[a-z0-9-]{1,32}".r

  /** What is wrong with an object's `name`, if anything. */
  def nameProblem(name: String): Option[String] =
    problem("an object's name", name, trailingSlash = false)

  /** What is wrong with a listing's `prefix`, if anything. Empty is everything. */
  def prefixProblem(prefix: String): Option[String] =
    if prefix.isEmpty then None else problem("a prefix", prefix, trailingSlash = true)

  /** What is wrong with a bucket's name, if anything, in the descriptor's words. */
  def bucketNameProblem(name: String): Option[String] =
    Option.when(!ValidBucketName.matches(name))(
      s"'${shown(name)}' is not a bucket name: $BucketNameRule"
    )

  /** What is wrong with a signed URL's span, if anything. */
  def spanProblem(span: FiniteDuration): Option[String] =
    if span <= Duration.Zero then
      Some(s"a signed URL's span is positive; this one is ${span.toSeconds} seconds")
    else
      Option.when(span > MaxSignedUrl)(
        s"a signed URL is made for at most 7 days (${MaxSignedUrl.toSeconds} seconds); " +
          s"this one asks for ${span.toSeconds}"
      )

  def checkName(name: String): Unit =
    nameProblem(name).foreach(p => throw CommandError(p, ErrorCode.BadRequest))
  def checkPrefix(prefix: String): Unit =
    prefixProblem(prefix).foreach(p => throw CommandError(p, ErrorCode.BadRequest))
  def checkSpan(span: FiniteDuration): Unit =
    spanProblem(span).foreach(p => throw CommandError(p, ErrorCode.BadRequest))

  private def problem(subject: String, text: String, trailingSlash: Boolean): Option[String] =
    val bytes    = text.getBytes(UTF_8).length
    val body     = if trailingSlash && text.endsWith("/") then text.dropRight(1) else text
    val segments = body.split("/", -1)
    val found    =
      if text.isEmpty then Some(s"$subject must not be empty")
      else if bytes > MaxNameBytes then
        Some(s"$subject is at most $MaxNameBytes bytes as UTF-8; this one is $bytes")
      else if text.startsWith("/") then Some(s"$subject must not begin with '/'")
      else if text.exists(c => c < ' ' || c == '\u007f') then
        Some(s"$subject must not hold a control character")
      else if segments.exists(_.isEmpty) then Some(s"$subject must not have an empty segment")
      else if segments.exists(s => s == "." || s == "..") then
        Some(s"$subject must not have a segment '.' or '..'")
      else None
    found.map(p => s"$p (\"${shown(text)}\")")

  private def shown(text: String): String =
    val visible = text.map(c => if c < ' ' || c == '\u007f' then '?' else c)
    if visible.length > 60 then visible.take(60) + "…" else visible
