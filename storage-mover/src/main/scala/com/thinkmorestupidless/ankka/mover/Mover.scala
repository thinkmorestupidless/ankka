package com.thinkmorestupidless.ankka.mover

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{
  RequestChecksumCalculation,
  ResponseChecksumValidation
}
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  ListObjectsV2Request,
  NoSuchKeyException,
  PutObjectRequest,
  S3Exception
}

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/** One side of a move: a bucket, where it is, and the credential that reaches it. */
final case class Store(
    endpoint: URI,
    region: String,
    bucket: String,
    accessKey: String,
    secretKey: String
):
  override def toString: String = s"Store($endpoint, $region, $bucket)"

  /** The client every service is told to build: path-style, checksums only where required. */
  def client(): S3Client =
    S3Client
      .builder()
      .endpointOverride(endpoint)
      .region(Region.of(region))
      .forcePathStyle(true)
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
      )
      .build()

/** What a run did, written as one JSON line for the operator to read. */
final case class Report(
    mode: String,
    counted: Int,
    copied: Int,
    verified: Int,
    failed: Option[String],
    reason: Option[String],
    seconds: Long
):
  def json: String =
    def str(v: Option[String]): String = v.fold("null")(s => "\"" + Report.escape(s) + "\"")
    s"""{"mode":"$mode","counted":$counted,"copied":$copied,"verified":$verified,""" +
      s""""failed":${str(failed)},"reason":${str(reason)},"seconds":$seconds}"""

object Report:
  private[mover] def escape(s: String): String =
    s.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }

/**
 * A run stopped at one object. `exitCode` is 1 when the object could not be moved or did not
 * verify, and 2 when the run could not proceed at all.
 */
final class MoveFailure(val key: Option[String], val reason: String, val exitCode: Int)
    extends RuntimeException(key.fold(reason)(k => s"$k: $reason"))

/**
 * Copies every object of one bucket into another and checks them (feature 039, contracts/mover.md).
 *
 * Every run is idempotent: an object already on the target with the same size and the same SHA-256
 * (recorded on it as `x-amz-meta-sha256` when this program wrote it) is not copied again, so a run
 * that stopped part way is finished by running it again. Nothing is deleted on either side, and the
 * source is only read.
 *
 * @param bufferLimit
 *   objects up to this size are read once into memory, hashed and uploaded from there; larger ones
 *   are read twice, once to hash and once to upload, so memory stays bounded
 */
final class Mover(
    source: Store,
    target: Store,
    concurrency: Int = 8,
    bufferLimit: Long = 64L * 1024 * 1024
):
  import Mover.*

  private val from = source.client()
  private val to   = target.client()

  /** Copies what is missing or different on the target. */
  def copy(): Report = timed("copy") { (counted, copied, _) =>
    forEach(listKeys(from, source.bucket, "source")) { key =>
      counted.incrementAndGet(): Unit
      if copyOne(key) then copied.incrementAndGet(): Unit
    }
  }

  /**
   * Copies what changed, then requires the two buckets to hold the same keys with the same bytes,
   * read and hashed from both sides. ETags are never compared: a multipart upload's differs per
   * store.
   */
  def verify(): Report = timed("verify") { (counted, copied, verified) =>
    forEach(listKeys(from, source.bucket, "source")) { key =>
      if copyOne(key) then copied.incrementAndGet(): Unit
    }
    val sourceKeys = listKeys(from, source.bucket, "source").toSet
    val targetKeys = listKeys(to, target.bucket, "target").toSet
    counted.set(sourceKeys.size)
    (sourceKeys -- targetKeys).toVector.sorted.headOption.foreach { k =>
      throw new MoveFailure(Some(k), "missing on target", 1)
    }
    (targetKeys -- sourceKeys).toVector.sorted.headOption.foreach { k =>
      throw new MoveFailure(Some(k), "missing on source", 1)
    }
    forEach(sourceKeys.toVector.sorted) { key =>
      val a = hashOf(from, source.bucket, key, "source")
      val b = hashOf(to, target.bucket, key, "target")
      if a != b then throw new MoveFailure(Some(key), "differs", 1)
      verified.incrementAndGet(): Unit
    }
  }

  def close(): Unit =
    from.close()
    to.close()

  /** Copies one object when the target lacks it or holds other bytes. True when it uploaded. */
  private def copyOne(key: String): Boolean =
    val head = headOf(from, source.bucket, key, "source")
      .getOrElse(throw new MoveFailure(Some(key), "missing on source", 1))
    val size = head.contentLength().longValue()
    if size > MaxObject then throw new MoveFailure(Some(key), "over 5 GiB", 1)
    val existing = headOf(to, target.bucket, key, "target")
    if size <= bufferLimit then
      val (bytes, got) = readAll(key)
      val sha          = hex(sha256(bytes))
      if same(existing, size, sha) then false
      else
        guard(Some(key), "target") {
          to.putObject(request(key, got, sha), RequestBody.fromBytes(bytes))
        }: Unit
        true
    else
      val sha = hashOf(from, source.bucket, key, "source")
      if same(existing, size, sha) then false
      else
        guard(Some(key), "source") {
          val stream = from.getObject(get(source.bucket, key))
          try
            guard(Some(key), "target") {
              to.putObject(
                request(key, stream.response(), sha),
                RequestBody.fromInputStream(stream, size)
              )
            }: Unit
          finally stream.close()
        }
        true

  private def same(existing: Option[HeadObjectResponse], size: Long, sha: String): Boolean =
    existing.exists { h =>
      h.contentLength().longValue() == size &&
      Option(h.metadata().get(ShaKey)).contains(sha)
    }

  private def readAll(key: String): (Array[Byte], GetObjectResponse) =
    guard(Some(key), "source") {
      val r = from.getObjectAsBytes(get(source.bucket, key))
      (r.asByteArray(), r.response())
    }

  private def request(key: String, got: GetObjectResponse, sha: String): PutObjectRequest =
    val b = PutObjectRequest
      .builder()
      .bucket(target.bucket)
      .key(key)
      .metadata((got.metadata().asScala.toMap + (ShaKey -> sha)).asJava)
    Option(got.contentType()).foreach(b.contentType)
    Option(got.cacheControl()).foreach(b.cacheControl)
    Option(got.contentDisposition()).foreach(b.contentDisposition)
    Option(got.contentEncoding()).foreach(b.contentEncoding)
    b.build()

  private def headOf(
      client: S3Client,
      bucket: String,
      key: String,
      side: String
  ): Option[HeadObjectResponse] =
    try Some(guard(Some(key), side)(client.headObject(head(bucket, key))))
    catch
      case _: NoSuchKeyException               => None
      case e: MoveFailure if e.reason == "404" => None

  private def hashOf(client: S3Client, bucket: String, key: String, side: String): String =
    guard(Some(key), side) {
      val stream = client.getObject(get(bucket, key))
      try hex(sha256(stream))
      finally stream.close()
    }

  private def listKeys(client: S3Client, bucket: String, side: String): Vector[String] =
    guard(None, side) {
      client
        .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
        .contents()
        .asScala
        .map(_.key())
        .toVector
    }

  /** Runs `work` for every key on `concurrency` threads; the first failure stops the run. */
  private def forEach(keys: Iterable[String])(work: String => Unit): Unit =
    val pool  = Executors.newFixedThreadPool(concurrency.max(1))
    val first = new AtomicReference[Throwable]()
    try
      keys.foreach { key =>
        pool.execute { () =>
          if first.get() == null then
            try work(key)
            catch case e: Throwable => first.compareAndSet(null, e): Unit
        }
      }
    finally
      pool.shutdown()
      pool.awaitTermination(365, TimeUnit.DAYS): Unit
    Option(first.get()).foreach(e => throw e)

  private def timed(mode: String)(
      body: (AtomicInteger, AtomicInteger, AtomicInteger) => Unit
  ): Report =
    val started                     = System.nanoTime()
    val (counted, copied, verified) = (AtomicInteger(), AtomicInteger(), AtomicInteger())
    def seconds                     = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
    try
      body(counted, copied, verified)
      Report(mode, counted.get, copied.get, verified.get, None, None, seconds)
    catch
      case e: MoveFailure =>
        throw new MoveRun(
          Report(mode, counted.get, copied.get, verified.get, e.key, Some(e.reason), seconds),
          e.exitCode
        )

/** A run that ended early, with what it had done and how the process should exit. */
final class MoveRun(val report: Report, val exitCode: Int) extends RuntimeException(report.json)

object Mover:
  /** One `PutObject` is what both stores accept up to this size. */
  val MaxObject: Long = 5L * 1024 * 1024 * 1024

  /** The metadata entry this program writes: the hex SHA-256 of the body. */
  val ShaKey: String = "sha256"

  private def get(bucket: String, key: String): GetObjectRequest =
    GetObjectRequest.builder().bucket(bucket).key(key).build()

  private def head(bucket: String, key: String): HeadObjectRequest =
    HeadObjectRequest.builder().bucket(bucket).key(key).build()

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  private def sha256(in: InputStream): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](1 << 16)
    var n      = in.read(buffer)
    while n >= 0 do
      digest.update(buffer, 0, n)
      n = in.read(buffer)
    digest.digest()

  private def hex(bytes: Array[Byte]): String = HexFormat.of().formatHex(bytes)

  /**
   * A store's refusal or absence, in the words the operator shows a member. A 404 on a single
   * object is passed up as `"404"` for `headOf` to read as absent.
   */
  private def guard[A](key: Option[String], side: String)(body: => A): A =
    try body
    catch
      case e: NoSuchKeyException => throw e
      case e: S3Exception if e.statusCode() == 404 && key.isDefined =>
        throw new MoveFailure(key, "404", 1)
      case e: S3Exception if e.statusCode() == 403 || e.statusCode() == 401 =>
        throw new MoveFailure(key, s"refused by $side", 2)
      case e: S3Exception =>
        throw new MoveFailure(key, s"$side answered ${e.statusCode()}: ${e.getMessage}", 2)
      case e: SdkClientException =>
        throw new MoveFailure(key, s"$side unreachable: ${e.getMessage}", 2)
