package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{
  Answer,
  ProxyEngine,
  ProxySettings,
  Sender,
  Transport
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.slf4j.LoggerFactory

import java.nio.file.{Path, Paths}
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*

/**
 * The proxy's process: the engine over mutual TLS, from the environment the operator wrote and the
 * certificate cert-manager issued.
 *
 * `run` returns the exit code, so a test can call it; `main` is the one-line wrapper. It refuses to
 * start when the environment is incomplete, naming every problem, and when the certificate's
 * identity is not the one the settings name, naming both: a proxy speaking as one service while
 * configured as another would tell every process the wrong caller.
 */
object Main:

  private val log = LoggerFactory.getLogger("ankka.proxy")

  /** Where the pod mounts the service certificate; a test points this at a directory of its own. */
  val ServiceDirectoryProperty: String = "ankka.proxy.service-directory"
  val DefaultServiceDirectory: String  = "/var/run/secrets/ankka/service"
  val MountDirectoryProperty: String   = "ankka.proxy.mount-directory"
  val DefaultMountDirectory: String    = "/var/run/secrets/ankka/mount"

  /** `on` runs the proxy on a developer's machine (feature 066); the operator never sets it. */
  val LocalVariable: String = "ANKKA_PROXY_LOCAL"

  def main(args: Array[String]): Unit = sys.exit(run(args))

  def run(
      args: Array[String],
      env: String => Option[String] = sys.env.get,
      report: String => Unit = message => log.error(message),
      started: ProxyEngine => Unit = _ => (),
      /** A test's ports in place of the fixed probe and calling ports; the identity otherwise. */
      adjust: ProxySettings => ProxySettings = identity
  ): Int =
    val _ = args
    if env(LocalVariable).contains("on") then local(env, report, started, adjust)
    else cluster(env, report)

  /**
   * The local mode: plain HTTP, no certificate read, every caller the local machine, and services
   * where `ANKKA_PROXY_LOCAL_SERVICES` says. The project is `local` unless named; callers are not
   * read, since every caller is the machine. The engine `ankka local web` runs, in this image.
   */
  private def local(
      env: String => Option[String],
      report: String => Unit,
      started: ProxyEngine => Unit,
      adjust: ProxySettings => ProxySettings
  ): Int =
    val localEnv: String => Option[String] = name =>
      if name == ProxySettings.Variables.Callers then None
      else if name == ProxySettings.Variables.Project then
        env(name).filter(_.nonEmpty).orElse(Some("local"))
      else env(name)
    val settings = ProxySettings.fromEnvironment(localEnv).map(adjust)
    val locator  = LocalLocator.parse(env(LocalLocator.Variable).getOrElse(""))
    (settings, locator) match
      case (Right(settings), Right(locator)) =>
        try
          val engine = ProxyEngine(
            settings,
            Transport.plain(ProxyEngine.EveryAddress),
            events = logging,
            locator = locator
          )
          engine.start()
          started(engine)
          log.info(
            "serving {} on this machine on port {}; the process on {}; {} mount(s)",
            settings.service,
            engine.ports.public,
            settings.processPort,
            settings.mounts.size
          )
          awaitStop(engine)
        catch
          case error: Throwable =>
            report(s"the proxy failed to start: ${error.getMessage}")
            1
      case (s, l) =>
        (s.left.toOption.toVector.flatten ++ l.left.toOption.toVector.flatten)
          .foreach(problem => report(s"refusing to start: $problem"))
        1

  private def awaitStop(engine: ProxyEngine): Int =
    val stopped = new CountDownLatch(1)
    val hook    = new Thread(() =>
      log.info("stopping: letting requests in flight finish")
      engine.stop()
      stopped.countDown()
    )
    Runtime.getRuntime.addShutdownHook(hook)
    try
      stopped.await()
      0
    catch
      case _: InterruptedException =>
        engine.stop()
        try Runtime.getRuntime.removeShutdownHook(hook): Unit
        catch case _: IllegalStateException => ()
        0

  private def cluster(
      env: String => Option[String],
      report: String => Unit
  ): Int =
    ProxySettings.fromEnvironment(env) match
      case Left(problems) =>
        problems.foreach(problem => report(s"refusing to start: $problem"))
        1
      case Right(settings) =>
        val directory = serviceDirectory
        try
          val tls = RotatingTls(directory, 1.minute)
          identityMismatch(tls, settings, directory) match
            case Some(problem) =>
              report(problem)
              1
            case None =>
              // The mount certificate is mounted only while there are mounts, and is needed then.
              val mountTls = Option.when(settings.mounts.nonEmpty)(
                RotatingTls(
                  Paths.get(sys.props.getOrElse(MountDirectoryProperty, DefaultMountDirectory)),
                  1.minute
                )
              )
              val engine = ProxyEngine(
                settings,
                TlsTransport(tls, mountTls = mountTls),
                events = logging,
                locator = ClusterLocator
              )
              engine.start()
              val stopped = new CountDownLatch(1)
              Runtime.getRuntime.addShutdownHook(new Thread(() =>
                log.info("stopping: letting requests in flight finish")
                engine.stop()
                stopped.countDown()
              ))
              val ports = engine.ports
              log.info(
                "serving {}/{} on port {}; the process on {}; the probe on {}; {} mount(s), {} caller(s)",
                settings.project,
                settings.service,
                ports.public,
                settings.processPort,
                ports.probe,
                settings.mounts.size,
                settings.callers.size
              )
              stopped.await()
              0
        catch
          case error: Throwable =>
            report(s"the proxy failed to start: ${error.getMessage}")
            log.error("the proxy failed to start", error)
            1

  def serviceDirectory: Path =
    Paths.get(sys.props.getOrElse(ServiceDirectoryProperty, DefaultServiceDirectory))

  /** Why the certificate cannot be this proxy's, or nothing when it names the settings' service. */
  def identityMismatch(tls: RotatingTls, settings: ProxySettings, directory: Path): Option[String] =
    val named = tls.identity.map(id => s"${id.project}/${id.service}")
    if named.contains(s"${settings.project}/${settings.service}") then None
    else
      Some(
        s"refusing to start: the certificate at $directory names ${named.getOrElse("no service")}, " +
          s"and the settings name ${settings.project}/${settings.service} " +
          s"(${ProxySettings.Variables.Project}, ${ProxySettings.Variables.Service})"
      )

  /** One line per answer of the proxy's own; a request passed to the process logs nothing. */
  private val logging: ProxyEngine.Events = new ProxyEngine.Events:
    def answered(sender: Option[Sender], method: String, target: String, answer: Answer): Unit =
      log.info(
        "{} {}: {} {} from {}",
        answer.status,
        answer.reason,
        method,
        target,
        sender.map(Sender.describe).getOrElse("an unrecognised certificate")
      )
