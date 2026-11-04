package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.sdk.*

import java.io.{ByteArrayInputStream, FilterInputStream, IOException, InputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZonedDateTime}
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*
import scala.jdk.OptionConverters.*

/**
 * One bucket in an S3 store, spoken to with the JDK's HTTP client and `SigV4` (feature 055,
 * research R3 and R5): path-style requests, a put streamed once with an unsigned payload, a listing
 * paged behind its iterator. No AWS library: the runtime and the sidecar image carry nothing for
 * this.
 *
 * @param target
 *   where the bucket is and the key that reaches it; with no key every call is refused naming
 *   `objectStorageCredential`
 * @param name
 *   the descriptor's name for it, `None` for the unnamed bucket
 * @param publicUrl
 *   a public bucket's base, which `url` appends a name to
 */
final class S3Bucket(
    target: S3Target,
    val name: Option[String],
    val exposure: BucketExposure,
    publicUrl: Option[String],
    timeout: FiniteDuration,
    maxSignedUrl: FiniteDuration,
    client: HttpClient,
    clock: () => Instant = () => Instant.now(),
    softDelete: Option[FiniteDuration] = None
) extends Bucket:

  private val endpoint = target.endpoint.toString
  private val Service  = "s3"

  def put(objectName: String, body: InputStream, length: Long, contentType: String): Unit =
    ObjectRules.checkName(objectName)
    if length < 0 then throw StorageRefusals.negativeLength(length)
    val bounded = new BoundedStream(body, length)
    val headers =
      Map("Content-Type" -> contentType, "x-amz-content-sha256" -> SigV4.UnsignedPayload)
    val request =
      signed("PUT", objectPath(objectName), Vector.empty, headers, SigV4.UnsignedPayload)
    // The JDK refuses a publisher of length zero; an empty object has no body to stream.
    val publisher =
      if length == 0 then HttpRequest.BodyPublishers.noBody()
      else
        HttpRequest.BodyPublishers.fromPublisher(
          HttpRequest.BodyPublishers.ofInputStream(() => bounded),
          length
        )
    val response =
      try client.send(request.PUT(publisher).build(), HttpResponse.BodyHandlers.ofString())
      catch
        case _: IOException if bounded.short =>
          throw StorageRefusals.shortBody(length, bounded.consumed)
        case e: IOException => throw unreachable(e)
    if bounded.short then throw StorageRefusals.shortBody(length, bounded.consumed)
    if response.statusCode != 200 then throw refused(response.statusCode, response.body)

  def get(objectName: String): Option[StoredObject] =
    ObjectRules.checkName(objectName)
    val response = send(
      signed("GET", objectPath(objectName)).GET().build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )
    response.statusCode match
      case 200 =>
        Some(
          StoredObject(
            contentType(response),
            response.headers.firstValueAsLong("Content-Length").orElse(-1L),
            lastModified(response),
            response.body
          )
        )
      case 404 =>
        response.body.close()
        None
      case status =>
        val detail =
          scala.util.Using.resource(response.body)(b => new String(b.readNBytes(4096), "UTF-8"))
        throw refused(status, detail)

  def stat(objectName: String): Option[ObjectInfo] =
    ObjectRules.checkName(objectName)
    val response = send(
      signed("HEAD", objectPath(objectName))
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.discarding()
    )
    response.statusCode match
      case 200 =>
        Some(
          ObjectInfo(
            objectName,
            contentType(response),
            response.headers.firstValueAsLong("Content-Length").orElse(0L),
            lastModified(response)
          )
        )
      case 404    => None
      case status => throw refused(status, "")

  def delete(objectName: String): Unit =
    ObjectRules.checkName(objectName)
    val response = send(
      signed("DELETE", objectPath(objectName)).DELETE().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if !Set(200, 204, 404).contains(response.statusCode) then
      throw refused(response.statusCode, response.body)

  def list(prefix: String): Iterator[ObjectEntry] =
    ObjectRules.checkPrefix(prefix)
    new Iterator[ObjectEntry]:
      private var page: Iterator[ObjectEntry] = Iterator.empty
      private var token: Option[String]       = None
      private var finished                    = false
      def hasNext: Boolean                    =
        while !page.hasNext && !finished do
          val (entries, next) = fetch(prefix, token)
          page = entries.iterator
          token = next
          finished = next.isEmpty
        page.hasNext
      def next(): ObjectEntry =
        if !hasNext then throw new NoSuchElementException("no more objects")
        page.next()

  /**
   * Every version of every object under the subject prefix, where the store keeps versions (Google
   * Cloud Storage), each deleted by its version id; where the store does not list versions (Garage
   * answers `NotImplemented`), each object as `delete` removes it. The count is of objects, not
   * versions; the finality is the store's soft-delete window, when it has one.
   */
  override def erase(subject: com.thinkmorestupidless.ankka.core.DataSubject): ErasedObjects =
    val prefix   = ObjectRules.subjectPrefix(subject)
    val finality = softDelete.fold(Finality.Immediate)(Finality.After(_))
    versionsUnder(prefix) match
      case None =>
        val names = list(prefix).map(_.name).toVector
        names.foreach(delete)
        ErasedObjects(names.size.toLong, finality)
      case Some(versions) =>
        versions.foreach((key, version) => deleteVersion(key, version))
        val objects = versions.filter(_._2.nonEmpty).map(_._1).distinct
        ErasedObjects(objects.size.toLong, finality)

  /** Every version and delete marker under `prefix`, or `None` when the store lists no versions. */
  private def versionsUnder(prefix: String): Option[Vector[(String, String)]] =
    val found                  = Vector.newBuilder[(String, String)]
    var marker: Option[String] = None
    var versionMarker          = Option.empty[String]
    var more                   = true
    var listed                 = true
    while more && listed do
      val query = Vector("prefix" -> prefix, "versions" -> "") ++
        marker.map("key-marker" -> _) ++ versionMarker.map("version-id-marker" -> _)
      val response = send(
        signed("GET", s"/${target.bucket}", query).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
      val body = new String(response.body, "UTF-8")
      if response.statusCode == 501 || body.contains("<Code>NotImplemented</Code>") then
        listed = false
      else if response.statusCode != 200 then throw refused(response.statusCode, body)
      else
        val page =
          try S3Bucket.parseVersions(response.body)
          catch case e: Exception => throw StorageRefusals.unreadable(endpoint, e.getMessage)
        found ++= page.versions
        marker = page.nextKey
        versionMarker = page.nextVersion
        more = page.truncated
    Option.when(listed)(found.result())

  /** One version, or a delete marker (`version` empty: the plain `delete`). */
  private def deleteVersion(objectName: String, version: String): Unit =
    val query    = if version.isEmpty then Vector.empty else Vector("versionId" -> version)
    val response = send(
      signed("DELETE", objectPath(objectName), query).DELETE().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if !Set(200, 204, 404).contains(response.statusCode) then
      throw refused(response.statusCode, response.body)

  def signedUrl(objectName: String, access: SignedAccess, span: FiniteDuration): URI =
    ObjectRules.checkName(objectName)
    val base = (name, exposure) match
      case (_, BucketExposure.Supplied) => target.endpoint
      case (None, _) => target.publicEndpoint.getOrElse(throw StorageRefusals.notExposed())
      case (Some(_), BucketExposure.Signed) =>
        target.publicEndpoint.getOrElse(throw StorageRefusals.notExposed())
      case (Some(b), other) => throw StorageRefusals.notSigned(b, other)
    ObjectRules.checkSpan(span)
    if span > maxSignedUrl then
      throw CommandError(
        s"a signed URL is made for at most ${maxSignedUrl.toSeconds} seconds here; this one asks for ${span.toSeconds}",
        com.thinkmorestupidless.ankka.core.ErrorCode.BadRequest
      )
    val method = access match
      case SignedAccess.Read => "GET"
      case SignedAccess.Keep => "PUT"
    SigV4.presign(
      SigV4.Request(method, base, objectPath(objectName)),
      credentials,
      target.region,
      Service,
      span.toSeconds,
      clock()
    )

  def url(objectName: String): URI =
    ObjectRules.checkName(objectName)
    (exposure, publicUrl) match
      case (BucketExposure.Public, Some(base)) =>
        URI.create(base.stripSuffix("/") + "/" + SigV4.encode(objectName, encodeSlash = false))
      case _ => throw StorageRefusals.notPublic(name, exposure)

  // ── requests ───────────────────────────────────────────────────────────────

  private def credentials: SigV4.Credentials =
    target.credentials.getOrElse(throw StorageRefusals.noCredential())

  private def objectPath(objectName: String): String = s"/${target.bucket}/$objectName"

  private def signed(
      method: String,
      path: String,
      query: Vector[(String, String)] = Vector.empty,
      headers: Map[String, String] = Map("x-amz-content-sha256" -> SigV4.EmptyPayload),
      payload: String = SigV4.EmptyPayload
  ): HttpRequest.Builder =
    val request = SigV4.Request(method, target.endpoint, path, query)
    val auth = SigV4.sign(request, headers, payload, credentials, target.region, Service, clock())
    val builder = HttpRequest.newBuilder(request.uri).timeout(timeout.toJava)
    (headers ++ auth).foreach((k, v) => builder.header(k, v))
    builder

  private def send[T](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
    try client.send(request, handler)
    catch
      case e: IOException          => throw unreachable(e)
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw unreachable(new IOException("interrupted", e))

  private def fetch(prefix: String, token: Option[String]): (Vector[ObjectEntry], Option[String]) =
    val query =
      Vector("list-type" -> "2", "prefix" -> prefix) ++ token.map("continuation-token" -> _)
    val response = send(
      signed("GET", s"/${target.bucket}", query).GET().build(),
      HttpResponse.BodyHandlers.ofByteArray()
    )
    if response.statusCode != 200 then
      throw refused(response.statusCode, new String(response.body, "UTF-8"))
    try S3Bucket.parseListing(response.body)
    catch case e: Exception => throw StorageRefusals.unreadable(endpoint, e.getMessage)

  private def contentType(response: HttpResponse[?]): String =
    response.headers.firstValue("Content-Type").toScala.getOrElse("application/octet-stream")

  private def lastModified(response: HttpResponse[?]): Instant =
    response.headers
      .firstValue("Last-Modified")
      .toScala
      .flatMap(v =>
        scala.util
          .Try(ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant)
          .toOption
      )
      .getOrElse(Instant.EPOCH)

  private def unreachable(e: IOException): CommandError =
    val cause = e match
      case _: HttpTimeoutException => s"no answer within ${timeout.toSeconds} seconds"
      case _                       => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    StorageRefusals.unreachable(endpoint, cause)

  private def refused(status: Int, body: String): CommandError =
    val code = "<Code>([^<]*)</Code>".r.findFirstMatchIn(body).map(_.group(1)).getOrElse("")
    StorageRefusals.refusedByStore(endpoint, status, code)

object S3Bucket:

  /** A page of `ListObjectVersions`. */
  private[storage] final case class Versions(
      versions: Vector[(String, String)],
      truncated: Boolean,
      nextKey: Option[String],
      nextVersion: Option[String]
  )

  /**
   * A `ListObjectVersions` answer: each `Version` and `DeleteMarker` as its key and version id. A
   * delete marker's id is kept too, since a marker is itself a version to remove. A `Version` with
   * an id of `null` (a store that has never kept versions) is read with an empty id.
   */
  private[storage] def parseVersions(xml: Array[Byte]): Versions =
    val factory = XMLInputFactory.newFactory()
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    val reader    = factory.createXMLStreamReader(new ByteArrayInputStream(xml))
    val versions  = Vector.newBuilder[(String, String)]
    var truncated = false
    var nextKey   = Option.empty[String]
    var nextVer   = Option.empty[String]
    var inEntry   = false
    var key       = ""
    var version   = ""
    try
      while reader.hasNext do
        reader.next() match
          case XMLStreamConstants.START_ELEMENT =>
            reader.getLocalName match
              case "Version" | "DeleteMarker" => inEntry = true; key = ""; version = ""
              case "Key" if inEntry           => key = reader.getElementText
              case "VersionId" if inEntry     =>
                version = reader.getElementText.trim match
                  case "null" => ""
                  case v      => v
              case "IsTruncated"         => truncated = reader.getElementText.trim == "true"
              case "NextKeyMarker"       => nextKey = Some(reader.getElementText)
              case "NextVersionIdMarker" => nextVer = Some(reader.getElementText)
              case _                     => ()
          case XMLStreamConstants.END_ELEMENT
              if reader.getLocalName == "Version" || reader.getLocalName == "DeleteMarker" =>
            inEntry = false
            versions += key -> version
          case _ => ()
    finally reader.close()
    Versions(versions.result(), truncated, nextKey, nextVer)

  /**
   * A `ListObjectsV2` answer: its entries, and the token for the next page while it is truncated.
   */
  private[storage] def parseListing(xml: Array[Byte]): (Vector[ObjectEntry], Option[String]) =
    val factory = XMLInputFactory.newFactory()
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    val reader     = factory.createXMLStreamReader(new ByteArrayInputStream(xml))
    val entries    = Vector.newBuilder[ObjectEntry]
    var token      = Option.empty[String]
    var truncated  = false
    var key        = ""
    var size       = 0L
    var modified   = Instant.EPOCH
    var inContents = false
    try
      while reader.hasNext do
        reader.next() match
          case XMLStreamConstants.START_ELEMENT =>
            reader.getLocalName match
              case "Contents" => inContents = true; key = ""; size = 0L; modified = Instant.EPOCH
              case "Key" if inContents          => key = reader.getElementText
              case "Size" if inContents         => size = reader.getElementText.trim.toLong
              case "LastModified" if inContents =>
                modified = Instant.parse(reader.getElementText.trim)
              case "NextContinuationToken" => token = Some(reader.getElementText)
              case "IsTruncated"           => truncated = reader.getElementText.trim == "true"
              case _                       => ()
          case XMLStreamConstants.END_ELEMENT if reader.getLocalName == "Contents" =>
            inContents = false
            entries += ObjectEntry(key, size, modified)
          case _ => ()
    finally reader.close()
    (entries.result(), if truncated then token else None)

/**
 * The body of a put: at most `length` bytes of `underlying`, and an `IOException` when it ends
 * before them, which aborts the request so the store keeps nothing.
 */
private final class BoundedStream(underlying: InputStream, length: Long)
    extends FilterInputStream(underlying):
  @volatile var consumed: Long = 0L
  @volatile var short: Boolean = false

  override def read(): Int =
    if consumed >= length then -1
    else
      val b = underlying.read()
      if b < 0 then ended() else { consumed += 1; b }

  override def read(buffer: Array[Byte], offset: Int, count: Int): Int =
    if consumed >= length then -1
    else
      val n = underlying.read(buffer, offset, math.min(count.toLong, length - consumed).toInt)
      if n < 0 then ended() else { consumed += n; n }

  // The caller's stream is theirs to close.
  override def close(): Unit = ()

  private def ended(): Nothing =
    short = true
    throw new IOException(s"the stream ended after $consumed of its declared $length bytes")
