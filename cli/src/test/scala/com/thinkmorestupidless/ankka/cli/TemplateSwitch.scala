package com.thinkmorestupidless.ankka.cli

import java.nio.file.{Files, Path}

/**
 * `-Dankka.template.tests`: unset runs every template suite, `off` runs none, and a comma-separated
 * list of languages (`python`, `typescript,scala`) runs those. The SDK jobs in CI name their own
 * language, so each proves its template against the SDK and sidecar of the same commit without
 * paying for the Scala template's local publish, which `build.sbt` runs only when `scala` is
 * selected.
 */
object TemplateSwitch:
  def enabled(language: String): Boolean = sys.props.get("ankka.template.tests") match
    case None        => true
    case Some("off") => false
    case Some(list)  => list.split(',').map(_.trim).contains(language)

  /** Named in the switch's list, rather than run because the switch is unset. */
  def named(language: String): Boolean =
    sys.props.get("ankka.template.tests").exists(_.split(',').map(_.trim).contains(language))

  /**
   * Whether a template suite is skipped. With the switch unset, a suite whose tools are missing is
   * skipped, so `sbt test` works on a machine without uv or cargo. Named, it is not: a CI job that
   * asked for a template's suite and got a skipped one would report green for work that never
   * happened, so the suite runs and `requireTools` fails it, naming what is missing.
   */
  def skip(language: String, missing: Seq[String]): Boolean =
    !enabled(language) || (missing.nonEmpty && !named(language))

  /**
   * Fails a suite that is running although its tools are missing, which only a named one can be.
   */
  def requireTools(language: String, missing: Seq[String]): Unit =
    if missing.nonEmpty then
      throw AssertionError(
        s"the $language template suite was asked for (-Dankka.template.tests) but " +
          s"${missing.mkString(", ")} ${if missing.size == 1 then "is" else "are"} not on PATH"
      )

  def onPath(tool: String): Boolean =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .exists(dir => Files.isExecutable(Path.of(dir).resolve(tool)))
