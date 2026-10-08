package com.thinkmorestupidless.ankka.runtime

import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.util.Try

/**
 * A service that refuses to start says why where `ankka services get` can show it: the container's
 * termination message, which the operator reads as the service's detail (feature 037, research R4).
 * Every start-time refusal goes through here: an invalid service, a projection the runtime cannot
 * run, a contract or broker the project does not declare, a discovery the sidecar rejects.
 *
 * Best effort: outside a container there is no termination log, and failing to write one must not
 * hide the reason the service is refusing to start. The path is `ankka.termination.log` so a suite
 * can read it back.
 */
private[ankka] object StartRefusal:

  private val log = LoggerFactory.getLogger("com.thinkmorestupidless.ankka.runtime.Ankka")

  val PathProperty: String = "ankka.termination.log"

  def path: String = sys.props.getOrElse(PathProperty, "/dev/termination-log")

  /** Writes the reason and logs it; the caller throws. */
  def report(reason: String): Unit =
    Try(Files.writeString(Paths.get(path), reason + "\n", UTF_8)): Unit
    log.error(reason)

  /** Reports, then throws the exception the reason belongs to. */
  def refuse(reason: String, failure: String => Throwable): Nothing =
    report(reason)
    throw failure(reason)
