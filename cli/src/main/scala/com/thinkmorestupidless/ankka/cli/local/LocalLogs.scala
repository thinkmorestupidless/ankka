package com.thinkmorestupidless.ankka.cli.local

import java.io.PrintStream
import java.nio.file.Path

/**
 * `ankka local logs <name>` (feature 066, contracts/cli.md): the log of a service or companion the
 * command started, as Docker kept it. A service hosted in two containers (a process beside its
 * sidecar, a program beside the proxy) shows both, the platform's first.
 */
object LocalLogs:

  /** The containers a service's own name stands for, in the order their logs are shown. */
  private val Suffixes = Vector("", "-app", "-module")

  def run(
      dir: Path,
      name: String,
      tail: Option[Int],
      follow: Boolean,
      engine: Engine,
      out: PrintStream,
      err: PrintStream
  ): Int =
    LocalProject.read(dir) match
      case Left(problems) =>
        problems.foreach(err.println)
        1
      case Right(project) =>
        val known =
          project.services.contains(name) || project.local.companions.exists(_.name == name)
        if !known then
          err.println(s"\"$name\" is not a service or companion of ${project.name}")
          1
        else
          val composeId = LocalPlan.composeProject(project.name)
          val held      = engine.ps(composeId).map(_.service).toSet
          val mine      = Suffixes.map(name + _).filter(held)
          if mine.isEmpty then
            err.println(s"\"$name\" was not started by ankka local up")
            1
          else
            // Following two logs at once would show only the first; follow the platform's, the
            // container named for the service in every hosting, after the others' logs so far.
            val ordered = mine.filterNot(_ == name) ++ mine.filter(_ == name)
            ordered.zipWithIndex
              .map((container, i) =>
                engine.logs(composeId, container, tail, follow && i == ordered.size - 1, out)
              )
              .find(_ != 0)
              .getOrElse(0)
