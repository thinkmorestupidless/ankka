package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.controlplane.api.ServiceSpec
import com.thinkmorestupidless.ankka.core.PlatformVariables

import java.io.PrintStream
import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * `ankka local run <service>` (feature 066, research R12): one service from its source, in the
 * foreground, among whatever of the project is up. What it needs is brought up as `up` brings it
 * (the database server, the broker, its database, the topics, its companions) and left running when
 * the service ends; the command's exit code is the service's.
 */
object LocalRun:

  def run(
      dir: Path,
      service: String,
      command: Vector[String],
      timeout: FiniteDuration,
      engine: Engine,
      out: PrintStream,
      err: PrintStream,
      probe: LocalUp.Probe = Probes.host,
      isFree: Int => Boolean = LocalState.isFree,
      cliVersion: String = com.thinkmorestupidless.ankka.core.BuildInfo.version
  ): Int =
    engine.available match
      case Left(refusal) =>
        err.println(refusal)
        1
      case Right(()) =>
        LocalProject.read(dir) match
          case Left(problems) =>
            problems.foreach(err.println)
            1
          case Right(project) if !project.services.contains(service) =>
            // The plan says so beside every other problem it finds.
            LocalUp.run(
              dir,
              LocalPlan.Selection.ForRun(service),
              timeout,
              engine,
              out,
              err,
              probe,
              isFree,
              cliVersion
            )
          case Right(project) =>
            val running = engine
              .ps(LocalPlan.composeProject(project.name))
              .exists(c => c.service == service && c.running)
            val chosen = commandFor(project, service, command)
            if running then
              err.println(
                s"\"$service\" is running from its image; leave it out with: " +
                  s"ankka local up --without $service"
              )
              1
            else
              chosen match
                case None =>
                  err.println(
                    s"cannot tell how to run \"$service\" from its source; give the command " +
                      "after --, or name it under \"run\" in local.json"
                  )
                  1
                case Some(chosen) =>
                  val built = buildModule(project, service, chosen, err)
                  if built != 0 then built
                  else
                    LocalUp.run(
                      dir,
                      LocalPlan.Selection.ForRun(service),
                      timeout,
                      engine,
                      out,
                      err,
                      probe,
                      isFree,
                      cliVersion,
                      finish = Some((project, _, state) =>
                        inForeground(project, service, chosen, state, engine, out, err)
                      )
                    )

  /** A module service's command builds the module before its runtime starts; 0 for any other. */
  private def buildModule(
      project: LocalProject,
      service: String,
      chosen: SourceCommands.RunCommand,
      err: PrintStream
  ): Int =
    if project.descriptor(service).service.hosting != ServiceSpec.Wasm then 0
    else
      val code   = foreground(chosen, Vector.empty)
      val module = LocalPlan.builtModule(project, service)
      if code != 0 then code
      else if !java.nio.file.Files.isRegularFile(module) then
        err.println(
          s"\"$service\" has no module at ${project.root.relativize(module)}; " +
            "its command did not build one"
        )
        1
      else 0

  /** The service's own code in the foreground, as its hosting runs it (research R12). */
  private def inForeground(
      project: LocalProject,
      service: String,
      chosen: SourceCommands.RunCommand,
      state: LocalState,
      engine: Engine,
      out: PrintStream,
      err: PrintStream
  ): Int =
    val descriptor = project.descriptor(service)
    descriptor.service.hosting match
      case ServiceSpec.Process =>
        // The process speaks to the sidecar started for it, over the ports chosen for them.
        val broker = LocalPlan
          .environment(project, service, state, onHost = true)
          .filter((k, _) => PlatformVariables.shared(k))
        val own = broker ++ LocalPlan
          .declaredValues(project, service, state, onHost = true)
          .filter((k, _) => !PlatformVariables.runtimeOnly(k))
        foreground(
          chosen,
          own ++ Vector(
            "ANKKA_PROCESS_PORT"    -> state.processPorts(service).toString,
            "ANKKA_SIDECAR_ADDRESS" ->
              s"127.0.0.1:${state.processPorts(LocalPlan.sidecarKey(service))}"
          )
        )
      case ServiceSpec.Wasm =>
        // The runtime is the container; what is in the foreground is its log, until interrupted.
        try engine.logs(LocalPlan.composeProject(project.name), service, None, follow = true, out)
        catch case _: InterruptedException => 130
      case ServiceSpec.Web =>
        // Behind the proxy's engine on this machine, the project's services found by their entries.
        LocalWeb.run(
          descriptor,
          LocalWeb.Options(
            file = project.root.resolve(s"services/$service/service.json").toString,
            port = descriptor.service.port,
            services = Map.empty,
            command = chosen.words,
            directory = Some(chosen.directory)
          ),
          out,
          err
        )
      case _ =>
        foreground(chosen, LocalPlan.environment(project, service, state, onHost = true))

  /** The command, in the contract's order: after `--`, `local.json`'s `run`, the language's own. */
  def commandFor(
      project: LocalProject,
      service: String,
      afterDashes: Vector[String]
  ): Option[SourceCommands.RunCommand] =
    val serviceDir = project.root.resolve("services").resolve(service)
    // Scala's one build is the project's; every other language runs in its service's directory.
    val language  = SourceCommands.language(serviceDir)
    val directory =
      if language.contains(SourceCommands.Language.Scala) then project.root else serviceDir
    if afterDashes.nonEmpty then Some(SourceCommands.RunCommand(afterDashes, directory))
    else
      project.local.run
        .get(service)
        .filter(_.nonEmpty)
        .map(SourceCommands.RunCommand(_, directory))
        .orElse(language.flatMap(SourceCommands.default(project.root, service, _)))

  /**
   * Runs `command` with the terminal, and answers its exit code; stopped from outside, it and
   * whatever it started go too (`npm start` and `sbt` run the service as a child), and the answer
   * is 130.
   */
  /**
   * A service's runtime announces itself to the local console in `-Dankka.running.dir`, and a JVM
   * reads only its own properties: when this command was given another directory than the default,
   * every JVM the service's command starts is given it too, or the service would announce itself
   * where this command does not look.
   */
  private[cli] def runningDirectory(existing: Option[String]): Option[String] =
    sys.props.get("ankka.running.dir").map { dir =>
      (existing.toVector :+ s"-Dankka.running.dir=$dir").mkString(" ")
    }

  private def foreground(
      command: SourceCommands.RunCommand,
      environment: Iterable[(String, String)]
  ): Int =
    val builder = new ProcessBuilder(command.words.asJava)
      .directory(command.directory.toFile)
      .inheritIO()
    val env = builder.environment()
    environment.foreach((k, v) => env.put(k, v): Unit)
    runningDirectory(Option(env.get("JAVA_TOOL_OPTIONS"))).foreach(env.put("JAVA_TOOL_OPTIONS", _))
    val process         = builder.start()
    def stopAll(): Unit =
      process.descendants().forEach(_.destroy(): Unit)
      process.destroy()
    val stop = new Thread(() => stopAll())
    Runtime.getRuntime.addShutdownHook(stop)
    try process.waitFor()
    catch
      case _: InterruptedException =>
        stopAll()
        130
    finally
      try Runtime.getRuntime.removeShutdownHook(stop): Unit
      catch case _: IllegalStateException => ()
