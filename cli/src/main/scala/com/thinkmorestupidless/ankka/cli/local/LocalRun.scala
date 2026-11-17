package com.thinkmorestupidless.ankka.cli.local

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
                      val environment =
                        LocalPlan.environment(project, service, state, onHost = true)
                      foreground(chosen, environment)
                    )
                  )

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
  private def foreground(
      command: SourceCommands.RunCommand,
      environment: Iterable[(String, String)]
  ): Int =
    val builder = new ProcessBuilder(command.words.asJava)
      .directory(command.directory.toFile)
      .inheritIO()
    val env = builder.environment()
    environment.foreach((k, v) => env.put(k, v): Unit)
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
