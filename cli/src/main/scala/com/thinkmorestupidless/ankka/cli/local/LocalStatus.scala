package com.thinkmorestupidless.ankka.cli.local

import java.io.PrintStream
import java.nio.file.Path

/**
 * `ankka local status` (feature 066, contracts/cli.md): one line for each service the context map
 * names and each companion, saying whether it is ready, where it answers, and who started it. It
 * rewrites the console's entries of the containers that are ready (research R8), so a console
 * started after `up` finds them. Exit 0 whatever the lines say; 1 only for a project it cannot
 * read.
 */
object LocalStatus:

  def run(
      dir: Path,
      engine: Engine,
      out: PrintStream,
      err: PrintStream,
      probe: LocalUp.Probe = Probes.host
  ): Int =
    LocalProject.read(dir) match
      case Left(problems) =>
        problems.foreach(err.println)
        1
      case Right(project) =>
        val containers = engine
          .ps(LocalPlan.composeProject(project.name))
          .map(c => c.service -> c)
          .toMap
        val state                       = LocalState.read(project.root)
        def observability(name: String) =
          state.observability.get(name).map(p => s"http://127.0.0.1:$p")
        def answers(address: String) =
          probe(LocalPlan.ReadyCheck.Observability(s"$address/observability/service", false))
        val fromSource = ConsoleEntries.sourceRun(project.services, answers)
        val services   = project.services.map { name =>
          val spec    = project.descriptor(name).service
          val address = if spec.http then s"http://127.0.0.1:${spec.port}" else ""
          if containers.get(name).exists(_.running) then
            val ready = LocalPlan.readyCheck(project, name, state).exists(probe)
            Row(name, if ready then "ready" else "running, not ready", address, Some(Started))
          else if fromSource(name) then Row(name, "ready", address, Some(FromSource))
          else Row(name, "not running", "", None)
        }
        val companions = project.local.companions.distinctBy(_.name).map { c =>
          val address = c.ports.headOption
            .flatMap(p => Companions.hostPort(state, c, p.port))
            .map(p => s"127.0.0.1:$p")
            .getOrElse("")
          if containers.get(c.name).exists(_.running) then
            val ready = Companions.ready(c, state).exists(probe)
            Row(c.name, if ready then "ready" else "running, not ready", address, Some(Started))
          else Row(c.name, "not running", "", None)
        }
        val rows  = services ++ companions
        val width = rows.map(_.name.length).maxOption.getOrElse(0)
        val shown = rows.map(_.status.length).maxOption.getOrElse(0)
        val at    = rows.map(_.address.length).maxOption.getOrElse(0)
        rows.foreach { r =>
          val line = s"  ${r.name.padTo(width, ' ')}  ${r.status.padTo(shown, ' ')}  " +
            s"${r.address.padTo(at, ' ')}  ${r.by.getOrElse("")}"
          out.println(line.replaceAll("\\s+$", ""))
        }
        ConsoleEntries.write(
          project.name,
          services
            // A web-hosted service has no observability endpoint, so the console lists it not.
            .filter(r => r.status == "ready" && r.by.contains(Started))
            .filterNot(r => project.descriptor(r.name).service.isWebHosted)
            .flatMap(r => observability(r.name).map(LocalPlan.ConsoleEntry(r.name, _)))
        )
        0

  private val Started    = "started by ankka local up"
  private val FromSource = "run from its source"

  private final case class Row(name: String, status: String, address: String, by: Option[String])
