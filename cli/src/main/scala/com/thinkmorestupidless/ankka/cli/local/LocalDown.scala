package com.thinkmorestupidless.ankka.cli.local

import java.io.PrintStream
import java.nio.file.{Files, Path}
import java.util.Comparator

/**
 * `ankka local down` (feature 066, contracts/cli.md): every container of the project stopped and
 * removed, the volumes kept unless asked for, and with them `.ankka/local/`.
 */
object LocalDown:

  def run(dir: Path, volumes: Boolean, engine: Engine, out: PrintStream, err: PrintStream): Int =
    engine.available match
      case Left(refusal) =>
        err.println(refusal)
        1
      case Right(()) =>
        LocalProject.read(dir) match
          case Left(problems) =>
            problems.foreach(err.println)
            1
          case Right(project) =>
            val composeId = LocalPlan.composeProject(project.name)
            val local     = project.root.resolve(LocalState.Directory)
            ConsoleEntries.remove(project.name)
            if engine.ps(composeId).isEmpty then
              // Nothing runs, and still the developer asked for what was recorded to go.
              if volumes then
                engine.down(composeId, volumes = true): Unit
                remove(local)
              out.println(s"nothing of ${project.name} is running")
              0
            else
              val stopped = engine.down(composeId, volumes)
              if !stopped.ok then
                err.println(s"${project.name} could not be brought down: ${stopped.err.trim}")
                1
              else
                if volumes then remove(local)
                val kept =
                  if volumes then "what it recorded is removed" else "what it recorded is kept"
                out.println(s"${project.name} is down; $kept")
                0

  private def remove(dir: Path): Unit =
    if Files.exists(dir) then
      val paths = Files.walk(dir)
      try paths.sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p): Unit)
      finally paths.close()
