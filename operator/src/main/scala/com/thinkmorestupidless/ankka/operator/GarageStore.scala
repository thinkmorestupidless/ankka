package com.thinkmorestupidless.ankka.operator

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

/**
 * The object store's administration API, version 2, over the JDK's own HTTP client (feature 034).
 *
 * The operator's dependencies are a Kubernetes client and a logger, and a process whose job is to
 * keep working while other things are broken should depend on as little as possible; the API is
 * JSON over HTTP with a bearer token, so a client library would add only weight.
 *
 * The API can return a key's secret again (`GetKeyInfo` with `showSecretKey`). Nothing here sends
 * that parameter: the secret `createKey` returns is the only copy the operator ever sees.
 *
 * @param onRequest
 *   called with the address of every request made, for a test to read what was asked
 */
final class GarageStore(
    adminUrl: String,
    token: String,
    onRequest: URI => Unit = _ => ()
) extends ObjectStore:

  private val base   = adminUrl.stripSuffix("/")
  private val json   = new ObjectMapper()
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  def bucket(name: String): Option[BucketInfo] =
    send("GET", s"/v2/GetBucketInfo?globalAlias=${encode(name)}") match
      case (404, _)  => None
      case (_, body) => Some(bucketInfo(body))

  def createBucket(name: String): BucketInfo =
    bucketInfo(send("POST", "/v2/CreateBucket", Some(obj("globalAlias" -> name)))._2)

  def keysNamed(name: String): Vector[String] =
    send("GET", "/v2/ListKeys")._2.elements.asScala.toVector
      .filter(key => key.path("name").asText() == name)
      .map(_.path("id").asText())

  def createKey(name: String): IssuedKey =
    val body = send("POST", "/v2/CreateKey", Some(obj("name" -> name)))._2
    IssuedKey(body.path("accessKeyId").asText(), body.path("secretAccessKey").asText())

  def deleteKey(accessKeyId: String): Unit =
    send("POST", s"/v2/DeleteKey?id=${encode(accessKeyId)}", Some(json.createObjectNode())): Unit

  def allow(bucketId: String, accessKeyId: String, write: Boolean): Unit =
    val body = json.createObjectNode()
    body.put("bucketId", bucketId)
    body.put("accessKeyId", accessKeyId)
    val permissions = body.putObject("permissions")
    // A writing key also owns the bucket, so a lifecycle rule the service sets with its own client
    // stays its own (feature 034); CORS is the platform's since feature 039. `AllowBucketKey` only
    // adds, so a key allowed without write is one that was never given it.
    permissions.put("read", true)
    permissions.put("write", write)
    permissions.put("owner", write)
    send("POST", "/v2/AllowBucketKey", Some(body)): Unit

  def setCors(bucketId: String, origins: Seq[String]): Unit =
    val body  = json.createObjectNode()
    val rules = body.putArray("corsRules")
    if origins.nonEmpty then
      val rule = rules.addObject()
      rule.put("ID", "ankka-origins")
      val allowed = rule.putArray("AllowedOrigin")
      origins.foreach(allowed.add)
      val methods = rule.putArray("AllowedMethod")
      Seq("GET", "PUT", "HEAD").foreach(methods.add)
      rule.putArray("AllowedHeader").add("*")
      rule.putArray("ExposeHeader").add("ETag")
      rule.put("MaxAgeSeconds", 3600): Unit
    send("POST", s"/v2/UpdateBucket?id=${encode(bucketId)}", Some(body)): Unit

  def expire(accessKeyId: String, at: Instant): Unit =
    val body = json.createObjectNode()
    body.put("expiration", at.toString)
    send("POST", s"/v2/UpdateKey?id=${encode(accessKeyId)}", Some(body)): Unit

  def keyInfo(accessKeyId: String): Option[KeyInfo] =
    send("GET", "/v2/ListKeys")._2.elements.asScala
      .find(_.path("id").asText() == accessKeyId)
      .map(key => KeyInfo(accessKeyId, key.path("name").asText(), key.path("expired").asBoolean()))

  private def bucketInfo(body: JsonNode): BucketInfo =
    BucketInfo(
      id = body.path("id").asText(),
      created = Instant.parse(body.path("created").asText()),
      allowedKeys = body.path("keys").elements.asScala.map(_.path("accessKeyId").asText()).toSet
    )

  private def obj(fields: (String, String)*): JsonNode =
    val node = json.createObjectNode()
    fields.foreach((k, v) => node.put(k, v))
    node

  private def encode(value: String): String = URLEncoder.encode(value, UTF_8)

  /**
   * One request. A connection that fails, a timeout or a 5xx is the store being unavailable; any
   * other status but 2xx and a bucket lookup's 404 is the store saying no, which is not.
   */
  private def send(method: String, path: String, body: Option[JsonNode] = None): (Int, JsonNode) =
    val uri = URI.create(base + path)
    onRequest(uri)
    val publisher = body
      .map(b => HttpRequest.BodyPublishers.ofString(json.writeValueAsString(b)))
      .getOrElse(HttpRequest.BodyPublishers.noBody())
    val request = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .method(method, publisher)
      .build()
    val response =
      try client.send(request, HttpResponse.BodyHandlers.ofString())
      catch
        case e: IOException =>
          throw new ObjectStoreUnavailable(s"the object store at $base could not be reached: $e", e)
        case e: InterruptedException =>
          Thread.currentThread().interrupt()
          throw new ObjectStoreUnavailable(s"interrupted calling the object store at $base", e)
    val status = response.statusCode()
    val parsed =
      try Option(response.body()).filter(_.nonEmpty).map(json.readTree).getOrElse(json.nullNode())
      catch case _: IOException => json.nullNode()
    if status >= 500 then
      throw new ObjectStoreUnavailable(s"the object store answered $status to $method $path")
    else if status == 404 && path.startsWith("/v2/GetBucketInfo") then (status, parsed)
    else if status < 200 || status >= 300 then
      val message = Option(parsed.path("message").asText(null)).getOrElse(response.body())
      throw new IllegalStateException(
        s"the object store answered $status to $method $path: $message"
      )
    else (status, parsed)
