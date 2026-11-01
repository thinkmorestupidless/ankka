package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.core.ContextMap

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * `ankka context-map check` and `render` (feature 062): a project's context map held true against
 * its own files, and rendered for people to read. Local commands: no control plane, no login, no
 * project flag. The rules are `core`'s `ContextMap`, which a project's tests module also calls.
 */
object ContextMapCommand:

  /** Every problem between the map at `dir` and the project around it; empty when it is true. */
  def check(dir: Path): Vector[String] =
    ContextMap.read(dir).fold(identity, ContextMap.check(dir, _))

  /** Writes `CONTEXT-MAP.md` and answers its path, or the problems that stop the map being read. */
  def render(dir: Path): Either[Vector[String], Path] =
    ContextMap.read(dir).map { map =>
      val target = dir.resolve(ContextMap.RenderingName)
      Files.writeString(target, ContextMap.render(map), UTF_8)
      target
    }

  /** Problems printed one to a line, then the exit the contract gives them. */
  private[cli] def refuse(problems: Vector[String]): Nothing =
    problems.foreach(Console.err.println)
    throw ExitWith(1)
