package com.thinkmorestupidless.ankka.runtime.secrets

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * The part of Secret Manager's REST API the platform speaks, over the JDK's HTTP client.
 *
 * No Google library: the secret store lives in `runtime`, so the sidecar image carries it, and a
 * client library would bring gax, gRPC and a protobuf-java that the sidecar's ScalaPB cannot share
 * a classpath with — for seven calls. Each call is one request bounded by `timeout`, authorised by
 * a token from `tokens`.
 *
 * Every failure is a `SecretManagerException` of a kind the caller decides about; nothing here maps
 * a kind to a store's error code, because the store and the control plane's writer answer them
 * differently. A message names the secret's id and Google's own status, never a payload.
 */
private[ankka] final class SecretManager(
    endpoint: String,
    /** The Google Cloud project the installation's secrets are kept in. */
    val account: String,
    tokens: AccessTokens,
    timeout: FiniteDuration,
    http: HttpClient = SecretManager.defaultClient
):
  import SecretManager.{*, given}
  import SecretManagerException.Kind

  private val base = endpoint.stripSuffix("/") + "/v1"

  /** The secret's full resource name, as an IAM condition names it (by project id here). */
  def resourceName(id: String): String = s"projects/$account/secrets/$id"

  /**
   * Makes the secret `id`. `true` when this call made it, `false` when it already existed.
   * Replication is automatic unless `location` names one place; `kmsKey` is used when given.
   */
  def createSecret(
      id: String,
      annotations: Map[String, String],
      location: Option[String] = None,
      kmsKey: Option[String] = None
  ): Boolean =
    val cmek = kmsKey.map(Cmek(_))
    val replication = location match
      case None        => Replication(automatic = Some(Automatic(cmek)))
      case Some(place) => Replication(userManaged = Some(UserManaged(Vector(Replica(place, cmek)))))
    val body = writeToArray(SecretBody(replication, annotations))
    try
      call("POST", s"$base/projects/$account/secrets?secretId=${encoded(id)}", id, Some(body))
      true
    catch case e: SecretManagerException if e.kind == Kind.Conflict => false

  /** Adds a version holding `data`; its number. `NotFound` when the secret does not exist. */
  def addVersion(id: String, data: Array[Byte]): Long =
    val body = writeToArray(AddVersionBody(Payload(Base64.getEncoder.encodeToString(data))))
    val reply = readFromArray[VersionReply](
      call("POST", s"$base/projects/$account/secrets/$id:addVersion", id, Some(body))
    )
    versionNumber(reply.name)

  /**
   * The newest enabled version and its number, or none when the secret or every version is gone.
   */
  def accessLatest(id: String): Option[Accessed] =
    try
      val reply = readFromArray[AccessReply](
        call("GET", s"$base/projects/$account/secrets/$id/versions/latest:access", id, None)
      )
      Some(Accessed(versionNumber(reply.name), Base64.getDecoder.decode(reply.payload.data)))
    catch case e: SecretManagerException if e.kind == Kind.NotFound => None

  /** Deletes the secret and every version. `false` when there was none. */
  def deleteSecret(id: String): Boolean =
    try
      call("DELETE", s"$base/projects/$account/secrets/$id", id, None)
      true
    catch case e: SecretManagerException if e.kind == Kind.NotFound => false

  /** The numbers of the secret's enabled versions, newest first; none when there is no secret. */
  def listEnabledVersions(id: String): Vector[Long] =
    val filter = encoded("state:ENABLED")
    def page(token: Option[String], acc: Vector[Long]): Vector[Long] =
      val url = s"$base/projects/$account/secrets/$id/versions?filter=$filter&pageSize=100" +
        token.fold("")(t => s"&pageToken=${encoded(t)}")
      val reply = readFromArray[ListReply](call("GET", url, id, None))
      val all   = acc ++ reply.versions.getOrElse(Vector.empty).map(v => versionNumber(v.name))
      reply.nextPageToken.filter(_.nonEmpty) match
        case Some(next) => page(Some(next), all)
        case None       => all
    try page(None, Vector.empty).sortBy(n => -n)
    catch case e: SecretManagerException if e.kind == Kind.NotFound => Vector.empty

  /** Destroys version `number`: its data is gone for good. */
  def destroyVersion(id: String, number: Long): Unit =
    call(
      "POST",
      s"$base/projects/$account/secrets/$id/versions/$number:destroy",
      id,
      Some(Empty)
    ): Unit

  /** Disables version `number`: kept, and no longer what `latest` reads. */
  def disableVersion(id: String, number: Long): Unit =
    call(
      "POST",
      s"$base/projects/$account/secrets/$id/versions/$number:disable",
      id,
      Some(Empty)
    ): Unit

  private def call(
      method: String,
      url: String,
      id: String,
      body: Option[Array[Byte]]
  ): Array[Byte] =
    val token = tokens.token()
    val builder = HttpRequest
      .newBuilder(URI(url))
      .timeout(Duration.ofMillis(timeout.toMillis))
      .header("Authorization", s"Bearer $token")
    val request = body match
      case Some(bytes) =>
        builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes))
          .build()
      case None => builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
    val response =
      try http.send(request, HttpResponse.BodyHandlers.ofByteArray())
      catch
        case e: InterruptedException => throw e
        case NonFatal(e) =>
          throw SecretManagerException(
            Kind.Unavailable,
            0,
            "UNAVAILABLE",
            s"Secret Manager could not be reached for '$id' (${e.getClass.getSimpleName}" +
              Option(e.getMessage).fold("")(m => s": $m") + ")"
          )
    if response.statusCode / 100 == 2 then response.body
    else throw failure(id, response.statusCode, response.body)

object SecretManager:

  /** One client for every store in the process: HTTP/1.1, as `HttpServiceClients` speaks. */
  private[ankka] lazy val defaultClient: HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5))
      .build()

  /** What `accessLatest` answers: the version read and its data. */
  final case class Accessed(version: Long, data: Array[Byte])

  private[secrets] final case class Cmek(kmsKeyName: String)
  private[secrets] final case class Automatic(customerManagedEncryption: Option[Cmek] = None)
  private[secrets] final case class Replica(
      location: String,
      customerManagedEncryption: Option[Cmek] = None
  )
  private[secrets] final case class UserManaged(replicas: Vector[Replica])
  private[secrets] final case class Replication(
      automatic: Option[Automatic] = None,
      userManaged: Option[UserManaged] = None
  )
  private[secrets] final case class SecretBody(
      replication: Replication,
      annotations: Map[String, String]
  )
  private[secrets] final case class Payload(data: String)
  private[secrets] final case class AddVersionBody(payload: Payload)
  private[secrets] final case class VersionReply(name: String)
  private[secrets] final case class AccessReply(name: String, payload: Payload)
  private[secrets] final case class VersionEntry(name: String, state: Option[String] = None)
  private[secrets] final case class ListReply(
      versions: Option[Vector[VersionEntry]] = None,
      nextPageToken: Option[String] = None
  )
  private[secrets] final case class ErrorDetail(
      code: Option[Int] = None,
      message: Option[String] = None,
      status: Option[String] = None
  )
  private[secrets] final case class ErrorReply(error: Option[ErrorDetail] = None)

  private[secrets] given JsonValueCodec[SecretBody]     = JsonCodecMaker.make
  private[secrets] given JsonValueCodec[AddVersionBody] = JsonCodecMaker.make
  private[secrets] given JsonValueCodec[VersionReply]   = JsonCodecMaker.make
  private[secrets] given JsonValueCodec[AccessReply]    = JsonCodecMaker.make
  private[secrets] given JsonValueCodec[ListReply]      = JsonCodecMaker.make
  private[secrets] given JsonValueCodec[ErrorReply]     = JsonCodecMaker.make

  private val Empty: Array[Byte] = "{}".getBytes(StandardCharsets.UTF_8)

  private def encoded(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

  /** The number at the end of `projects/…/secrets/…/versions/<n>`. */
  private[ankka] def versionNumber(name: String): Long =
    name.substring(name.lastIndexOf('/') + 1).toLong

  private def failure(id: String, statusCode: Int, body: Array[Byte]): SecretManagerException =
    import SecretManagerException.Kind
    val detail =
      try readFromArray[ErrorReply](body).error.getOrElse(ErrorDetail())
      catch case NonFatal(_) => ErrorDetail()
    val status  = detail.status.getOrElse(s"HTTP $statusCode")
    val message = detail.message.getOrElse("")
    val kind = statusCode match
      case 401 | 403                   => Kind.Denied
      case 404                         => Kind.NotFound
      case 409                         => Kind.Conflict
      case 400                         => Kind.Invalid
      case 429 | 500 | 502 | 503 | 504 => Kind.Unavailable
      case _                           => Kind.Failed
    SecretManagerException(
      kind,
      statusCode,
      status,
      s"Secret Manager answered $status for '$id'" + (if message.isEmpty then "" else s": $message")
    )

/** A call Secret Manager refused or could not answer, of a kind the caller decides about. */
private[ankka] final class SecretManagerException(
    val kind: SecretManagerException.Kind,
    val httpStatus: Int,
    val status: String,
    message: String
) extends RuntimeException(message)

private[ankka] object SecretManagerException:
  enum Kind:
    /**
     * The caller's identity may not do this: a missing grant, or one that does not cover the id.
     */
    case Denied

    /** No such secret, or no such version. */
    case NotFound

    /** A secret of that id already exists. */
    case Conflict

    /**
     * Unreachable, timed out, over quota, or failing on Google's side: worth trying again later.
     */
    case Unavailable

    /** The request itself was malformed: a platform bug, never the caller's. */
    case Invalid

    /** Anything else. */
    case Failed
