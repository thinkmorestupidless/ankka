package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.cli.console.LocalSource

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The local console's entries for a project's containers (feature 066, research R11): one file per
 * service in the running directory, as a running service writes its own, owned by the command so
 * the console keeps it while the container starts rather than sweeping it as stale.
 */
object ConsoleEntries:

  /** Who writes an entry with an owner: the command, for a container it started. */
  val Owner: String = "ankka local up"

  private def directory: Path = LocalSource.defaultDirectory

  private def fileFor(project: String, service: String): Path =
    directory.resolve(s"$project.$service.json")

  private def quote(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Writes each entry, replacing what the project's entries said before. */
  def write(project: String, entries: Vector[LocalPlan.ConsoleEntry]): Unit =
    Try {
      Files.createDirectories(directory)
      entries.foreach { e =>
        val json =
          s"""{"name":${quote(e.name)},"instanceId":${quote(s"$project/${e.name}")},""" +
            s""""observabilityAddress":${quote(e.observabilityAddress)},"owner":${quote(
                Owner
              )},""" +
            s""""project":${quote(project)},"startedAt":${quote(
                java.time.Instant.now().toString
              )}}"""
        Files.writeString(fileFor(project, e.name), json, UTF_8)
      }
    }: Unit

  /**
   * Removes the entries the command wrote for `services` of `project`; a developer's own is kept.
   */
  def remove(project: String, services: Vector[String]): Unit =
    services.map(fileFor(project, _)).foreach { f =>
      Try {
        val text = Files.readString(f, UTF_8)
        if OwnerField.findFirstMatchIn(text).exists(_.group(1) == Owner) then Files.delete(f)
      }
    }

  /** Removes every entry the command wrote for `project`. */
  def remove(project: String): Unit =
    entriesOf(project).foreach(f => Try(Files.deleteIfExists(f)))

  private def entriesOf(project: String): Vector[Path] =
    Try(
      Files
        .list(directory)
        .iterator()
        .asScala
        .filter(f =>
          f.getFileName.toString.startsWith(s"$project.") && f.getFileName.toString
            .endsWith(".json")
        )
        .toVector
    ).getOrElse(Vector.empty)

  private val NameField  = "\"name\":\"([^\"]*)\"".r
  private val OwnerField = "\"owner\":\"([^\"]*)\"".r
  private val Address    = "\"observabilityAddress\":\"([^\"]*)\"".r

  /** Which of `names` a process of the developer's announced, with no owner, and answers now. */
  def sourceRun(names: Vector[String], answers: String => Boolean): Set[String] =
    val files = Try(
      Files.list(directory).iterator().asScala.filter(_.toString.endsWith(".json")).toVector
    ).getOrElse(Vector.empty)
    files
      .flatMap(f => Try(Files.readString(f, UTF_8)).toOption)
      .filter(json => OwnerField.findFirstIn(json).isEmpty)
      .flatMap { json =>
        for
          name    <- NameField.findFirstMatchIn(json).map(_.group(1)) if names.contains(name)
          address <- Address.findFirstMatchIn(json).map(_.group(1)) if answers(address)
        yield name
      }
      .toSet
