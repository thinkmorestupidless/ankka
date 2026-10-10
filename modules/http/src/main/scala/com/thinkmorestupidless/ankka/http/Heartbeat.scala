package com.thinkmorestupidless.ankka.http

import com.typesafe.config.Config

import scala.concurrent.duration.*

/**
 * How often a stream that waits says it is still there: a third of the server's idle timeout, so
 * the service never ends a connection for being quiet while a wait goes on, and twenty seconds when
 * the idle timeout is infinite. The caller does not set it: an interval at or above the idle
 * timeout is a cut connection, and nothing to configure means nothing to get wrong. The SSE form of
 * a wait in Scala and the sidecar's stream for a process both take it from here.
 */
object Heartbeat:

  val WhenIdleIsInfinite: FiniteDuration = 20.seconds

  def interval(config: Config): FiniteDuration =
    val path = "pekko.http.server.idle-timeout"
    if !config.hasPath(path) then WhenIdleIsInfinite
    else
      Duration(config.getString(path)) match
        case idle: FiniteDuration if idle > Duration.Zero => (idle / 3).max(100.millis)
        case _                                            => WhenIdleIsInfinite
