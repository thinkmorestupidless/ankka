package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.telemetry.TelemetrySettings

import java.net.URI
import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The keeper's environment, as the model server's component writes it (contracts/keeper.md of
 * feature 053). Read once at start; a problem refuses the start, naming every one.
 */
final case class KeeperSettings(
    /** Where Ollama listens: the pod's loopback. */
    ollama: URI,
    /** The declaration file, re-read when it changes. */
    declaration: Path,
    /** The door's address, as services are told it, reported as the document's `server`. */
    address: String,
    port: Int,
    statusPort: Int,
    probePort: Int,
    /** How often the loop runs when nothing changed. */
    passInterval: FiniteDuration,
    /** What the server runs at once, `OLLAMA_NUM_PARALLEL`'s value: beyond it a call waits. */
    parallel: Int,
    otlpEndpoint: Option[URI],
    otlpHeaders: Vector[(String, String)],
    /** Which installation this is, for the metrics' resource. */
    installation: Option[String]
)

object KeeperSettings:

  object Variables:
    val Ollama       = "ANKKA_KEEPER_OLLAMA"
    val Declaration  = "ANKKA_KEEPER_DECLARATION"
    val Address      = "ANKKA_KEEPER_ADDRESS"
    val Port         = "ANKKA_KEEPER_PORT"
    val StatusPort   = "ANKKA_KEEPER_STATUS_PORT"
    val ProbePort    = "ANKKA_KEEPER_PROBE_PORT"
    val PassInterval = "ANKKA_KEEPER_PASS_INTERVAL"
    val Parallel     = "ANKKA_KEEPER_PARALLEL"
    val OtlpEndpoint = "ANKKA_OTLP_ENDPOINT"
    val OtlpHeaders  = "ANKKA_OTLP_HEADERS"
    val Installation = "ANKKA_INSTALLATION"

  val DefaultOllama: String       = "http://127.0.0.1:11434"
  val DefaultDeclaration: String  = "/etc/ankka/models/models.json"
  val DefaultPort: Int            = 8443
  val DefaultStatusPort: Int      = 8080
  val DefaultProbePort: Int       = 7627
  val DefaultPass: FiniteDuration = 30.seconds

  /** The settings, or every problem with them. */
  def fromEnvironment(env: String => Option[String]): Either[Vector[String], KeeperSettings] =
    import Variables.*
    def value(name: String): Option[String] = env(name).map(_.trim).filter(_.nonEmpty)
    def port(name: String, default: Int): Either[String, Int] =
      value(name) match
        case None       => Right(default)
        case Some(text) =>
          text.toIntOption
            .filter(p => p >= 0 && p <= 65535)
            .toRight(s"$name '$text' is not a port")

    val ollama = value(Ollama).getOrElse(DefaultOllama) match
      case text =>
        Try(URI(text)).toOption
          .filter(u => u.getScheme == "http" && u.getHost != null)
          .toRight(s"$Ollama '$text' is not an http:// address")
    val address  = value(Address).toRight(s"$Address is not set: the address services call")
    val parallel = value(Parallel) match
      case None       => Right(1)
      case Some(text) =>
        text.toIntOption.filter(_ >= 1).toRight(s"$Parallel '$text' is not a number of at least 1")
    val pass = value(PassInterval) match
      case None       => Right(DefaultPass)
      case Some(text) =>
        duration(text).toRight(s"$PassInterval '$text' is not a duration such as 30s")
    val otlp = value(OtlpEndpoint) match
      case None       => Right(None)
      case Some(text) =>
        Try(URI(text)).toOption
          .filter(u => Set("http", "https").contains(u.getScheme) && u.getHost != null)
          .map(Some(_))
          .toRight(s"$OtlpEndpoint '$text' is not an http:// or https:// address")
    val headers = TelemetrySettings.parseHeaders(env(OtlpHeaders).getOrElse(""))

    val results = Vector(
      ollama,
      address,
      port(Port, DefaultPort),
      port(StatusPort, DefaultStatusPort),
      port(ProbePort, DefaultProbePort),
      parallel,
      pass,
      otlp,
      headers
    )
    val problems = results.collect { case Left(problem) => problem }
    if problems.nonEmpty then Left(problems)
    else
      Right(
        KeeperSettings(
          ollama = ollama.toOption.get,
          declaration = Paths.get(value(Declaration).getOrElse(DefaultDeclaration)),
          address = address.toOption.get,
          port = port(Port, DefaultPort).toOption.get,
          statusPort = port(StatusPort, DefaultStatusPort).toOption.get,
          probePort = port(ProbePort, DefaultProbePort).toOption.get,
          passInterval = pass.toOption.get,
          parallel = parallel.toOption.get,
          otlpEndpoint = otlp.toOption.get,
          otlpHeaders = headers.toOption.get,
          installation = value(Installation)
        )
      )

  /** `500ms`, `30s`, `5m`, `1h`: a whole number and a unit. */
  def duration(text: String): Option[FiniteDuration] =
    val form = """(\d+)(ms|s|m|h)""".r
    text.trim match
      case form(n, unit) =>
        n.toLongOption.filter(_ > 0).map { v =>
          unit match
            case "ms" => v.millis
            case "s"  => v.seconds
            case "m"  => v.minutes
            case _    => v.hours
        }
      case _ => None
