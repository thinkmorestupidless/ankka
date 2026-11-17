package com.thinkmorestupidless.ankka.cli

import scala.util.Try

/**
 * `-Dankka.local.tests` (feature 066): whether the suites that bring a project up against a real
 * Docker run. `on` runs them and fails them when Docker or Compose v2 does not answer, so a CI job
 * that asked for them cannot pass having skipped; unset runs them when both answer and skips them
 * otherwise, so `sbt test` works on a machine without Docker; `off` skips them.
 */
object LocalSwitch:
  private def setting: Option[String] = sys.props.get("ankka.local.tests").map(_.trim)

  /** Whether `docker version` and `docker compose version` both answer, asked once. */
  lazy val dockerAnswers: Boolean =
    def ok(words: String*): Boolean =
      Try {
        val process = ProcessBuilder(words*).redirectErrorStream(true).start()
        process.getInputStream.readAllBytes()
        process.waitFor() == 0
      }.getOrElse(false)
    ok("docker", "version") && ok("docker", "compose", "version")

  /** Whether a Docker-backed suite is skipped. */
  def skip: Boolean = setting match
    case Some("off") => true
    case Some("on")  => false
    case _           => !dockerAnswers

  /** Fails a suite that was asked for, and is running, without Docker. */
  def requireDocker(): Unit =
    if !dockerAnswers then
      throw AssertionError(
        "the local project suites were asked for (-Dankka.local.tests=on) but docker or " +
          "docker compose does not answer on this machine"
      )
