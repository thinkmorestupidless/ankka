package com.thinkmorestupidless.ankka.telemetry

import com.typesafe.config.Config

import java.net.URI
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

/**
 * Where an instance exports its telemetry, and how often: `ankka.telemetry.*`, which on the
 * platform the operator fills from the installation's settings.
 *
 * An empty address is no settings at all, which is how "an installation that names no collector"
 * becomes "nothing starts". A malformed one is an error that names what is wrong and never the
 * value of a header, since a header is a credential.
 */
final case class TelemetrySettings(
    endpoint: URI,
    headers: Vector[(String, String)],
    interval: FiniteDuration,
    batchSize: Int,
    exportTimeout: FiniteDuration,
    maxBackoff: FiniteDuration,
    metricInterval: FiniteDuration,
    shutdownTimeout: FiniteDuration,
    serviceName: Option[String],
    project: Option[String]
):
  def tracesUrl: String  = s"${base}/v1/traces"
  def metricsUrl: String = s"${base}/v1/metrics"
  private def base       = endpoint.toString.stripSuffix("/")

object TelemetrySettings:

  /** None when no collector is named; an error naming the problem when one is named badly. */
  def from(config: Config): Either[String, Option[TelemetrySettings]] =
    val raw = config.getString("ankka.telemetry.endpoint").trim
    if raw.isEmpty then Right(None)
    else
      for
        endpoint <- address(raw)
        headers  <- parseHeaders(config.getString("ankka.telemetry.headers"))
      yield Some(
        TelemetrySettings(
          endpoint = endpoint,
          headers = headers,
          interval = duration(config, "interval"),
          batchSize = config.getInt("ankka.telemetry.batch-size"),
          exportTimeout = duration(config, "export-timeout"),
          maxBackoff = duration(config, "max-backoff"),
          metricInterval = duration(config, "metric-interval"),
          shutdownTimeout = duration(config, "shutdown-timeout"),
          serviceName = nonEmpty(config.getString("ankka.telemetry.service-name")),
          project = nonEmpty(config.getString("ankka.telemetry.project"))
        )
      )

  private def address(raw: String): Either[String, URI] =
    Try(URI(raw)).toOption
      .filter(u => Set("http", "https").contains(u.getScheme) && u.getHost != null)
      .toRight(s"ANKKA_OTLP_ENDPOINT '$raw' is not an http:// or https:// address")

  /**
   * `name=value,name=value`, trimmed. An entry without `=`, or with an empty name, is refused by
   * its position: what it says may be a credential.
   */
  def parseHeaders(raw: String): Either[String, Vector[(String, String)]] =
    val entries = raw.split(',').toVector.map(_.trim).filter(_.nonEmpty)
    entries.zipWithIndex.foldLeft[Either[String, Vector[(String, String)]]](Right(Vector.empty)) {
      case (Left(problem), _) => Left(problem)
      case (Right(done), (entry, at)) =>
        entry.indexOf('=') match
          case i if i > 0 && entry.substring(0, i).trim.nonEmpty =>
            Right(done :+ (entry.substring(0, i).trim -> entry.substring(i + 1).trim))
          case _ =>
            Left(s"ANKKA_OTLP_HEADERS entry ${at + 1} is not name=value")
    }

  private def duration(config: Config, key: String): FiniteDuration =
    Duration.fromNanos(config.getDuration(s"ankka.telemetry.$key").toNanos)

  private def nonEmpty(s: String): Option[String] = Option(s.trim).filter(_.nonEmpty)
