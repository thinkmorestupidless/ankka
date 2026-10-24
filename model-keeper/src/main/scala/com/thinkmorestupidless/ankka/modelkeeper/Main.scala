package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*

/**
 * The keeper's process (feature 053): the loop and the door, from the environment the model
 * server's component writes and the certificate cert-manager issued for `ankka://platform/models`.
 *
 * `run` returns the exit code, so a test can call it; `main` is the one-line wrapper. It refuses to
 * start naming every problem with the environment, and when the certificate is not the model
 * server's: a door speaking as anything else would be refused by every service that calls it.
 */
object Main:

  private val log = LoggerFactory.getLogger("ankka.model-keeper")

  /** Where the pod mounts the certificate; a test points this at a directory of its own. */
  val ServiceDirectoryProperty: String = "ankka.keeper.service-directory"
  val DefaultServiceDirectory: String  = "/var/run/secrets/ankka/service"

  /** The identity the certificate must carry. */
  val Identity: String = "ankka://platform/models"

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(
      args: Array[String],
      env: String => Option[String] = sys.env.get,
      report: String => Unit = message => log.error(message),
      started: Main.Started => Unit = _ => ()
  ): Int =
    val _ = args
    KeeperSettings.fromEnvironment(env) match
      case Left(problems) =>
        problems.foreach(problem => report(s"refusing to start: $problem"))
        1
      case Right(settings) =>
        val directory = serviceDirectory
        if !Files.isRegularFile(directory.resolve("tls.crt")) then
          report(s"refusing to start: there is no certificate at $directory (tls.crt)")
          1
        else
          try
            val tls = RotatingTls(directory, 1.minute)
            identityMismatch(tls, directory) match
              case Some(problem) =>
                report(problem)
                1
              case None => serve(settings, tls, started)
          catch
            case error: Throwable =>
              report(s"the model keeper failed to start: ${error.getMessage}")
              log.error("the model keeper failed to start", error)
              1

  /** What `run` has started, for a test that stops it. */
  final case class Started(door: Door, keeper: Keeper, stop: () => Unit)

  private def serve(settings: KeeperSettings, tls: RotatingTls, started: Started => Unit): Int =
    val keeper    = Keeper(settings, OllamaClient(settings.ollama))
    val telemetry = settings.otlpEndpoint match
      case Some(endpoint) =>
        KeeperTelemetry.otlp(
          endpoint,
          settings.otlpHeaders,
          settings.installation,
          settings.parallel,
          keeper.telemetrySource
        )
      case None => KeeperTelemetry.local(settings.parallel, keeper.telemetrySource)
    keeper.reportTo(telemetry)
    val door = Door(settings, tls, keeper.source, telemetry)
    door.start()
    keeper.start()
    val stopped = new CountDownLatch(1)
    val stop    = () =>
      if stopped.getCount > 0 then
        log.info("stopping")
        door.stop()
        keeper.stop()
        telemetry.stop()
        stopped.countDown()
    val hook = new Thread(() => stop())
    Runtime.getRuntime.addShutdownHook(hook)
    val ports = door.ports
    log.info(
      "the model server's door is on port {}, its status on {}, the probe on {}; Ollama at {}; " +
        "declaration {}",
      ports.door,
      ports.status,
      ports.probe,
      settings.ollama,
      settings.declaration
    )
    started(Started(door, keeper, stop))
    try stopped.await()
    catch case _: InterruptedException => stop()
    try Runtime.getRuntime.removeShutdownHook(hook): Unit
    catch case _: IllegalStateException => ()
    0

  def serviceDirectory: Path =
    Paths.get(sys.props.getOrElse(ServiceDirectoryProperty, DefaultServiceDirectory))

  /** Why the certificate is not the model server's, or nothing when it is. */
  def identityMismatch(tls: RotatingTls, directory: Path): Option[String] =
    val uris = RotatingTls.ankkaUris(tls.current)
    if uris.contains(Identity) then None
    else
      Some(
        s"refusing to start: the certificate at $directory names " +
          s"${
              if uris.isEmpty then "no ankka:// identity" else uris.mkString(", ")
            }, and the model " +
          s"server's is $Identity"
      )
