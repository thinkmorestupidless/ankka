package com.thinkmorestupidless.ankka.runtime

import java.nio.file.{Files, Path, Paths, StandardOpenOption}

/**
 * How `ankka local console` finds the services running on this machine.
 *
 * A process writes one small file naming itself and where its observability endpoint is listening,
 * and removes it on the way out. The console lists the directory. That is the whole mechanism — no
 * port scanning, no broadcast, and a service started before the console is found just as easily as
 * one started after.
 *
 * **Local mode only.** In Kubernetes the pod *is* the registry and the management port is the
 * exposure, so there is nothing for this to do and it does not run.
 *
 * Two details are not incidental:
 *
 *   - **The directory is overridable by a system property.** `CLAUDE.md` records why: environment
 *     variables cannot be set in-process, so without this no suite could exercise discovery without
 *     writing into the developer's own home directory. `Settings.path` in the CLI checks
 *     `-Dankka.config` first for exactly the same reason.
 *   - **A stale file is expected, not exceptional.** `kill -9` is how a developer stops a service
 *     far more often than a clean shutdown, so a leftover file is the normal case and the reader —
 *     not the writer — is responsible for noticing. See `Discovery` in the CLI.
 *
 * The file deliberately does **not** record the service's HTTP address. That is asked of the
 * running service when somebody wants it, because binding completes on its own schedule and a file
 * written at startup would be recording a guess.
 */
object ServiceRegistration:

  private val DirectoryProperty = "ankka.running.dir"

  /** Where entries live: `-Dankka.running.dir`, else `~/.ankka/running`. */
  def directory: Path =
    sys.props.get(DirectoryProperty) match
      case Some(path) => Paths.get(path)
      case None       => Paths.get(sys.props("user.home"), ".ankka", "running")

  private def fileFor(pid: Long): Path = directory.resolve(s"$pid.json")

  /**
   * Announces this process, returning the file written so it can be removed later.
   *
   * Failure to write is not failure to start: a service whose console registration fails is a
   * service that runs perfectly well and cannot be browsed, and taking the whole process down for
   * that would be the wrong trade.
   */
  def announce(name: String, observabilityAddress: String): Option[Path] =
    val pid  = ProcessHandle.current().pid()
    val file = fileFor(pid)
    try
      Files.createDirectories(directory)
      val json =
        s"""{"name":${quote(name)},"instanceId":${quote(pid.toString)},"pid":$pid,""" +
          s""""observabilityAddress":${quote(observabilityAddress)},""" +
          s""""startedAt":${quote(java.time.Instant.now().toString)}}"""
      Files.writeString(
        file,
        json,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      Some(file)
    catch case _: Throwable => None

  /** Withdraws this process's entry. Best effort — a leftover file is the reader's problem. */
  def withdraw(file: Path): Unit =
    try Files.deleteIfExists(file): Unit
    catch case _: Throwable => ()

  private def quote(value: String): String =
    val escaped = value.flatMap {
      case '"'                 => "\\\""
      case '\\'                => "\\\\"
      case '\n'                => "\\n"
      case '\r'                => "\\r"
      case '\t'                => "\\t"
      case c if c.toInt < 0x20 => f"\\u${c.toInt}%04x"
      case c                   => c.toString
    }
    s""""$escaped""""
