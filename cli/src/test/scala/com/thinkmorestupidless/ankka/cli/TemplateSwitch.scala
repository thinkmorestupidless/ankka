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

  def onPath(tool: String): Boolean =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .exists(dir => Files.isExecutable(Path.of(dir).resolve(tool)))
