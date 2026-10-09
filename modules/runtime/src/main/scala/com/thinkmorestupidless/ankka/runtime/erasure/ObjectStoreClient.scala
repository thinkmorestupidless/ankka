package com.thinkmorestupidless.ankka.runtime.erasure

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Duration, Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The few S3 calls the platform makes of a bucket, signed with AWS Signature Version 4 over the
 * JDK's HTTP client: list a prefix (every version where the store keeps them), delete one object or
 * one version, put and get an object. No S3 library joins `runtime` for this (R15).
 *
 * Path-style addressing (`<endpoint>/<bucket>/<key>`), which Garage requires and every store
 * accepts; no trailing checksums, which Garage refuses.
 */
final class ObjectStoreClient(
    endpoint: URI,
    region: String,
    bucket: String,
    accessKey: String,
    secretKey: String,
    http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
):
  import ObjectStoreClient.*

  /** Every object (and every version of one, where the store keeps them) under `prefix`. */
  def list(prefix: String): Vector[Listed] =
    listVersions(prefix).getOrElse(listCurrent(prefix))

  def delete(item: Listed): Unit =
    val query = item.versionId.fold(Vector.empty[(String, String)])(v => Vector("versionId" -> v))
    val response = send("DELETE", s"/$bucket/${path(item.key)}", query, Array.emptyByteArray)
    if response.statusCode() / 100 != 2 && response.statusCode() != 404 then
      throw IllegalStateException(
        s"deleting ${item.key} failed: ${response.statusCode()} ${body(response)}"
      )

  /** Writes an object; `ifAbsent` asks the store to refuse it when the key already holds one. */
  def put(key: String, content: Array[Byte], ifAbsent: Boolean = false): Boolean =
    val headers  = if ifAbsent then Vector("if-none-match" -> "*") else Vector.empty
    val response = send("PUT", s"/$bucket/${path(key)}", Vector.empty, content, headers)
    response.statusCode() match
      case ok if ok / 100 == 2 => true
      case 412                 => false
      case other => throw IllegalStateException(s"writing $key failed: $other ${body(response)}")

  def get(key: String): Option[Array[Byte]] =
    val response = send("GET", s"/$bucket/${path(key)}", Vector.empty, Array.emptyByteArray)
    response.statusCode() match
      case 200   => Some(response.body())
      case 404   => None
      case other => throw IllegalStateException(s"reading $key failed: $other ${body(response)}")

  private def listCurrent(prefix: String): Vector[Listed] =
    def page(token: Option[String]): (Vector[Listed], Option[String]) =
      val query =
        Vector("list-type" -> "2", "prefix" -> prefix) ++ token.map("continuation-token" -> _)
      val response = send("GET", s"/$bucket", query, Array.emptyByteArray)
      if response.statusCode() != 200 then
        throw IllegalStateException(
          s"listing $prefix failed: ${response.statusCode()} ${body(response)}"
        )
      val doc  = xml(response.body())
      val keys = elements(doc, "Contents").map(c => Listed(text(c, "Key"), None))
      (
        keys,
        Option.when(text(doc.getDocumentElement, "IsTruncated") == "true")(
          text(doc.getDocumentElement, "NextContinuationToken")
        )
      )
    Iterator
      .unfold(Option(Option.empty[String])) {
        case None => None
        case Some(token) =>
          val (keys, next) = page(token)
          Some((keys, next.map(Some(_))))
      }
      .flatten
      .toVector

  /**
   * `None` when the store keeps no versions (Garage answers the versions call as not implemented).
   */
  private def listVersions(prefix: String): Option[Vector[Listed]] =
    val response =
      send("GET", s"/$bucket", Vector("versions" -> "", "prefix" -> prefix), Array.emptyByteArray)
    if response.statusCode() != 200 then None
    else
      val doc = xml(response.body())
      val versions = (elements(doc, "Version") ++ elements(doc, "DeleteMarker")).map { v =>
        Listed(
          text(v, "Key"),
          Option(text(v, "VersionId")).filter(id => id.nonEmpty && id != "null")
        )
      }
      Some(versions)

  private def send(
      method: String,
      rawPath: String,
      query: Vector[(String, String)],
      payload: Array[Byte],
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): HttpResponse[Array[Byte]] =
    val now         = Instant.now()
    val amzDate     = AmzDate.format(now.atOffset(ZoneOffset.UTC))
    val day         = amzDate.take(8)
    val payloadHash = hex(sha256(payload))
    val host        = endpoint.getAuthority
    val canonicalQuery = query
      .map((k, v) => (encode(k), encode(v)))
      .sortBy(identity)
      .map((k, v) => s"$k=$v")
      .mkString("&")
    val headers = (Vector(
      "host"                 -> host,
      "x-amz-content-sha256" -> payloadHash,
      "x-amz-date"           -> amzDate
    ) ++ extraHeaders).sortBy(_._1)
    val canonicalHeaders = headers.map((k, v) => s"$k:${v.trim}\n").mkString
    val signedHeaders    = headers.map(_._1).mkString(";")
    val canonical =
      s"$method\n$rawPath\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
    val scope = s"$day/$region/s3/aws4_request"
    val stringToSign =
      s"AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonical.getBytes(UTF_8)))}"
    val signingKey =
      Vector(day, region, "s3", "aws4_request").foldLeft(s"AWS4$secretKey".getBytes(UTF_8))(hmac)
    val signature = hex(hmac(signingKey, stringToSign))
    val authorization =
      s"AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    val uri = URI.create(
      endpoint.toString.stripSuffix("/") + rawPath + (if canonicalQuery.isEmpty then ""
                                                      else s"?$canonicalQuery")
    )
    val builder = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofSeconds(30))
      .header("x-amz-content-sha256", payloadHash)
      .header("x-amz-date", amzDate)
      .header("Authorization", authorization)
      .method(method, HttpRequest.BodyPublishers.ofByteArray(payload))
    extraHeaders.foreach((k, v) => builder.header(k, v))
    http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

object ObjectStoreClient:

  /** A key, and the version of it where the store keeps versions. */
  final case class Listed(key: String, versionId: Option[String])

  /** The bucket `ankka.erasure.bucket` names; none when any of its four values is empty. */
  def fromConfig(config: com.typesafe.config.Config): Option[ObjectStoreClient] =
    val at = "ankka.erasure.bucket."
    def value(key: String): Option[String] =
      Option.when(config.hasPath(at + key))(config.getString(at + key)).filter(_.nonEmpty)
    fromEnvironment {
      case "ANKKA_S3_ENDPOINT"   => value("endpoint")
      case "ANKKA_S3_REGION"     => value("region")
      case "ANKKA_S3_BUCKET"     => value("name")
      case "ANKKA_S3_ACCESS_KEY" => value("access-key")
      case "ANKKA_S3_SECRET_KEY" => value("secret-key")
      case _                     => None
    }

  /** The bucket the platform gave a service, from its `ANKKA_S3_*` variables; none without them. */
  def fromEnvironment(env: String => Option[String]): Option[ObjectStoreClient] =
    for
      endpoint <- env("ANKKA_S3_ENDPOINT").filter(_.nonEmpty)
      bucket   <- env("ANKKA_S3_BUCKET").filter(_.nonEmpty)
      access   <- env("ANKKA_S3_ACCESS_KEY").filter(_.nonEmpty)
      secret   <- env("ANKKA_S3_SECRET_KEY").filter(_.nonEmpty)
    yield ObjectStoreClient(
      URI.create(endpoint),
      env("ANKKA_S3_REGION").filter(_.nonEmpty).getOrElse("garage"),
      bucket,
      access,
      secret
    )

  private val AmzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

  private def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)
  private def hex(bytes: Array[Byte]): String = HexFormat.of().formatHex(bytes)

  private def hmac(key: Array[Byte], data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8))

  /** RFC 3986 unreserved characters kept, everything else percent-encoded, as SigV4 requires. */
  private def encode(text: String): String =
    text
      .getBytes(UTF_8)
      .map { b =>
        val c = (b & 0xff).toChar
        if c.isLetterOrDigit && c < 128 || "-_.~".contains(c) then c.toString
        else f"%%${b & 0xff}%02X"
      }
      .mkString

  /** A key as a path: each segment encoded, the slashes kept. */
  private def path(key: String): String = key.split("/", -1).map(encode).mkString("/")

  private def body(response: HttpResponse[Array[Byte]]): String =
    String(response.body(), UTF_8).take(300)

  private def xml(bytes: Array[Byte]): org.w3c.dom.Document =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.newDocumentBuilder().parse(java.io.ByteArrayInputStream(bytes))

  private def elements(doc: org.w3c.dom.Document, name: String): Vector[org.w3c.dom.Element] =
    val nodes = doc.getElementsByTagName(name)
    (0 until nodes.getLength).map(i => nodes.item(i).asInstanceOf[org.w3c.dom.Element]).toVector

  private def text(parent: org.w3c.dom.Element, name: String): String =
    val nodes = parent.getElementsByTagName(name)
    if nodes.getLength == 0 then "" else nodes.item(0).getTextContent
