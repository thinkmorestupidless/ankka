package com.thinkmorestupidless.ankka.runtime.storage

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4, as S3 and every store that speaks it reads it: a request signed in its
 * headers, and a URL signed in its query string (feature 055).
 *
 * Nothing in the tree signed an S3 request before this; the operator speaks Garage's admin API with
 * a bearer token, and the AWS SDK is a test dependency only. The signer works on a request's parts
 * — its method, its endpoint, its path and query *before* encoding, the headers it signs and the
 * hash of its payload — so the header form and the query form share one canonical request, and the
 * documentation's vectors test it with no client at all.
 *
 * Paths are encoded once, segment by segment, as S3 expects (other AWS services encode twice).
 */
object SigV4:

  /** A key pair. `toString` never shows the secret. */
  final case class Credentials(accessKey: String, secretKey: String):
    override def toString: String = s"Credentials($accessKey, ***)"

  /** The payload hash a put sends so its body is streamed once and never hashed. */
  val UnsignedPayload: String = "UNSIGNED-PAYLOAD"

  /** The hash of an empty payload. */
  val EmptyPayload: String = hex(sha256(Array.emptyByteArray))

  val Algorithm: String = "AWS4-HMAC-SHA256"

  private val AmzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
  private val ShortDate = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

  /** One request: the endpoint's scheme and authority, a decoded path and decoded query pairs. */
  final case class Request(
      method: String,
      endpoint: URI,
      path: String,
      query: Vector[(String, String)] = Vector.empty
  ):
    /** The `host` header's value: the host, with the port when the endpoint names one. */
    def host: String =
      val port = endpoint.getPort
      if port == -1 then endpoint.getHost else s"${endpoint.getHost}:$port"

    def canonicalPath: String =
      val base = Option(endpoint.getRawPath).filter(_.nonEmpty).getOrElse("").stripSuffix("/")
      val p    = if path.startsWith("/") then path else "/" + path
      base + encode(p, encodeSlash = false)

    def canonicalQuery: String =
      query
        .map((k, v) => (encode(k, encodeSlash = true), encode(v, encodeSlash = true)))
        .sorted
        .map((k, v) => s"$k=$v")
        .mkString("&")

    /** The URI the request is sent to: the same encoding as the one signed. */
    def uri: URI =
      val q = canonicalQuery
      URI.create(s"${endpoint.getScheme}://$host$canonicalPath${if q.isEmpty then "" else "?" + q}")

  /**
   * The headers to send with `request` so the store believes it: `x-amz-date` and `Authorization`.
   * `headers` are the ones to sign beside `host` and `x-amz-date` — an S3 request includes
   * `x-amz-content-sha256` among them — and are sent as given.
   */
  def sign(
      request: Request,
      headers: Map[String, String],
      payloadHash: String,
      credentials: Credentials,
      region: String,
      service: String,
      now: Instant
  ): Map[String, String] =
    val amzDate = AmzDate.format(now)
    val signed =
      headers.map((k, v) => k.toLowerCase(java.util.Locale.ROOT) -> v) +
        ("host" -> request.host) + ("x-amz-date" -> amzDate)
    val (names, canonical) = canonicalHeaders(signed)
    val scope              = s"${ShortDate.format(now)}/$region/$service/aws4_request"
    val creq =
      Vector(
        request.method,
        request.canonicalPath,
        request.canonicalQuery,
        canonical,
        names,
        payloadHash
      )
        .mkString("\n")
    val signature = hex(
      hmac(
        signingKey(credentials.secretKey, now, region, service),
        stringToSign(amzDate, scope, creq)
      )
    )
    Map(
      "x-amz-date" -> amzDate,
      "Authorization" -> s"$Algorithm Credential=${credentials.accessKey}/$scope, SignedHeaders=$names, Signature=$signature"
    )

  /**
   * A URL anyone may use, until it expires, for `request`'s method on its path. Signs `host` alone.
   */
  def presign(
      request: Request,
      credentials: Credentials,
      region: String,
      service: String,
      expiresSeconds: Long,
      now: Instant
  ): URI =
    val amzDate = AmzDate.format(now)
    val scope   = s"${ShortDate.format(now)}/$region/$service/aws4_request"
    val withAuth = request.copy(query =
      request.query ++ Vector(
        "X-Amz-Algorithm"     -> Algorithm,
        "X-Amz-Credential"    -> s"${credentials.accessKey}/$scope",
        "X-Amz-Date"          -> amzDate,
        "X-Amz-Expires"       -> expiresSeconds.toString,
        "X-Amz-SignedHeaders" -> "host"
      )
    )
    val creq =
      Vector(
        request.method,
        withAuth.canonicalPath,
        withAuth.canonicalQuery,
        s"host:${request.host}\n",
        "host",
        UnsignedPayload
      ).mkString("\n")
    val signature = hex(
      hmac(
        signingKey(credentials.secretKey, now, region, service),
        stringToSign(amzDate, scope, creq)
      )
    )
    withAuth.copy(query = withAuth.query :+ ("X-Amz-Signature" -> signature)).uri

  private def canonicalHeaders(headers: Map[String, String]): (String, String) =
    val sorted = headers.toVector.map((k, v) => k -> v.trim.replaceAll(" +", " ")).sortBy(_._1)
    (sorted.map(_._1).mkString(";"), sorted.map((k, v) => s"$k:$v\n").mkString)

  private def stringToSign(amzDate: String, scope: String, canonicalRequest: String): String =
    s"$Algorithm\n$amzDate\n$scope\n${hex(sha256(canonicalRequest.getBytes(UTF_8)))}"

  private def signingKey(
      secret: String,
      now: Instant,
      region: String,
      service: String
  ): Array[Byte] =
    val date = hmac(("AWS4" + secret).getBytes(UTF_8), ShortDate.format(now))
    hmac(hmac(hmac(date, region), service), "aws4_request")

  /** RFC 3986 encoding as SigV4 states it: unreserved characters kept, everything else `%XX`. */
  def encode(text: String, encodeSlash: Boolean): String =
    val out = new StringBuilder
    text.getBytes(UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
        c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)
      then out.append(c)
      else out.append(f"%%${b & 0xff}%02X")
    }
    out.toString

  def sha256(bytes: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes)

  def hmac(key: Array[Byte], data: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8))

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
