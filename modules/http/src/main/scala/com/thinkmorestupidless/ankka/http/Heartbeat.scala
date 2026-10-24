package com.thinkmorestupidless.ankka.http

import com.typesafe.config.Config

import scala.concurrent.duration.*

/**
 * How long a stream of server-sent events may be quiet before the service says it is still there:
 * `ankka.http.sse.heartbeat`, or half the server's idle timeout when that is sooner, so the service
 * never ends a connection for being quiet whatever the two are set to. The one reading of the
 * setting: every SSE route's heartbeat, the SSE form of a wait in Scala and the sidecar's wait
 * stream for a process all take it from here.
 */
object Heartbeat:

  /** What `ankka.http.sse.heartbeat` is when nothing sets it, as `reference.conf` says. */
  val Default: FiniteDuration = 15.seconds

  def interval(config: Config): FiniteDuration =
    val setting = "ankka.http.sse.heartbeat"
    val every   =
      if config.hasPath(setting) then config.getDuration(setting).toMillis.millis else Default
    if every <= Duration.Zero then
      throw IllegalArgumentException(s"$setting must be positive, not $every")
    idleTimeout(config) match
      case Some(idle) if every >= idle => (idle / 2).max(1.millisecond)
      case _                           => every

  /**
   * The server's idle timeout, read as pekko reads it: `infinite`, or a duration HOCON understands.
   */
  private def idleTimeout(config: Config): Option[FiniteDuration] =
    val path = "pekko.http.server.idle-timeout"
    if !config.hasPath(path) || config.getString(path).trim == "infinite" then None
    else Some(config.getDuration(path).toMillis.millis).filter(_ > Duration.Zero)
