package com.thinkmorestupidless.ankka.runtime.secrets

import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import javax.net.ssl.SSLParameters
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Where the record of each read, keep and removal of a secret goes.
 *
 * `write` returns only once the record is kept, and throws `CommandError(Unavailable)` when it
 * cannot say that it is: the secret store calls it before it returns a value, so no value is ever
 * returned without its record.
 */
private[ankka] trait ReadRecorder:
  def write(record: ReadRecord): Unit

  /** What this recorder kept in this process, newest last: only a local recorder keeps any. */
  def kept: Vector[ReadRecord] = Vector.empty

private[ankka] object ReadRecorder:

  val UrlKey: String = "ankka.secrets.records-url"

  /** The control plane at `ankka.secrets.records-url`, or a local recorder when it is empty. */
  def from(config: Config): ReadRecorder =
    val url = config.getString(UrlKey).trim
    if url.isEmpty then LocalRecorder()
    else
      val timeout =
        FiniteDuration(config.getDuration("ankka.secrets.record-timeout").toMillis, "ms")
      ControlPlaneRecorder(url, tls(config, url), timeout)

  private def tls(config: Config, url: String): Option[RotatingTls] =
    val key = "ankka.tls.service-directory"
    val directory =
      if config.hasPath(key) then config.getString(key).trim else ""
    if url.startsWith("https://") && directory.nonEmpty then
      Some(
        RotatingTls(
          Paths.get(directory),
          FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, "ms")
        )
      )
    else None

/**
 * The control plane, over the service's own certificate: the identity the control plane checks the
 * record's project and service against, so a service can record only its own reads. The control
 * plane must present `ankka://platform/controlplane`, so a record is never handed to anything else.
 */
private[ankka] final class ControlPlaneRecorder(
    url: String,
    tls: Option[RotatingTls],
    timeout: FiniteDuration
) extends ReadRecorder:

  private val log      = LoggerFactory.getLogger(classOf[ControlPlaneRecorder])
  private val endpoint = URI(url.stripSuffix("/") + "/secret-reads")

  private val plain: HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofMillis(timeout.toMillis))
      .build()

  @volatile private var secured: Option[(Int, HttpClient)] = None

  /** One client per certificate generation, as the service client keeps: a renewed one is used. */
  private def client: HttpClient = tls match
    case None => plain
    case Some(identity) =>
      val context = identity.contextRequiring(ControlPlaneRecorder.ControlPlaneUri)
      val stamp   = System.identityHashCode(context)
      secured match
        case Some((s, c)) if s == stamp => c
        case _ =>
          val params = new SSLParameters()
          params.setEndpointIdentificationAlgorithm("HTTPS")
          params.setProtocols(Array("TLSv1.3"))
          val c = HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(context)
            .sslParameters(params)
            .connectTimeout(Duration.ofMillis(timeout.toMillis))
            .build()
          secured = Some((stamp, c))
          c

  def write(record: ReadRecord): Unit =
    val request = HttpRequest
      .newBuilder(endpoint)
      .timeout(Duration.ofMillis(timeout.toMillis))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(record)))
      .build()
    val status =
      try client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode
      catch
        case e: InterruptedException => throw e
        case NonFatal(e) =>
          log.warn(
            "the record of '{}' of the secret '{}' could not be written: {}",
            record.operation,
            record.name,
            e.toString
          )
          throw ControlPlaneRecorder.notAcknowledged(record, e.getClass.getSimpleName)
    if status / 100 != 2 then
      log.warn(
        "the record of '{}' of the secret '{}' was answered {}",
        record.operation,
        record.name,
        status
      )
      throw ControlPlaneRecorder.notAcknowledged(record, s"answered $status")

private[ankka] object ControlPlaneRecorder:
  val ControlPlaneUri: String = "ankka://platform/controlplane"

  def notAcknowledged(record: ReadRecord, why: String): CommandError =
    CommandError(
      s"the secret store is unavailable: the record of this ${record.operation} of " +
        s"'${record.name}' was not acknowledged ($why), and no secret is used without one",
      ErrorCode.Unavailable
    )

/**
 * A service with no control plane — on a developer's machine, or in a test — logs each record at
 * info and keeps the last `capacity` of them in memory, where the test kit reads them.
 */
private[ankka] final class LocalRecorder(capacity: Int = 10_000) extends ReadRecorder:
  private val log    = LoggerFactory.getLogger(classOf[LocalRecorder])
  private val buffer = ConcurrentLinkedDeque[ReadRecord]()

  def write(record: ReadRecord): Unit =
    buffer.addLast(record)
    while buffer.size > capacity do buffer.pollFirst(): Unit
    log.info(
      "secret-read name={} operation={} outcome={} service={}/{} component={}",
      record.name,
      record.operation,
      record.outcome,
      record.project,
      record.service,
      record.component.getOrElse("-")
    )

  override def kept: Vector[ReadRecord] = buffer.asScala.toVector
