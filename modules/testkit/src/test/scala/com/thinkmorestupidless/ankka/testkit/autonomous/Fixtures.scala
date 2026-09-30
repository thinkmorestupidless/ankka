package com.thinkmorestupidless.ankka.testkit.autonomous

import java.nio.file.{Files, Path, Paths}

/**
 * Compares generated wire forms with committed files, writing any that differ.
 *
 * The same discipline as `core`'s `EncodingFixturesSuite`: a change to a codec shows up as a diff
 * in a committed file, which is the moment to decide whether it breaks a journal or an SDK. A file
 * that was missing or different is rewritten, and the test still fails, so the new form is reviewed
 * rather than accepted silently.
 */
private[testkit] object Fixtures:

  /** The repository root, found by walking up from the forked test JVM's working directory. */
  def repositoryRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.isDirectory(p.resolve("protocol")) && Files.isDirectory(p.resolve("modules"))
      )
      .getOrElse(throw IllegalStateException("could not find the repository root"))

  /** Problems found; empty when every file matched. */
  def check(files: Map[Path, String]): Vector[String] =
    files.toVector.sortBy(_._1.toString).flatMap { (path, content) =>
      Files.createDirectories(path.getParent)
      if !Files.exists(path) then
        Files.writeString(path, content)
        Some(s"${path.getFileName} was missing and has been written; commit it")
      else if Files.readString(path) != content then
        Files.writeString(path, content)
        Some(s"${path.getFileName} differed and has been rewritten; review the diff")
      else None
    }
