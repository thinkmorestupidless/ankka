package com.thinkmorestupidless.ankka.telemetry

import org.slf4j.Logger

import java.net.URI

/**
 * Whether the collector can be reached, as the exporters last found it, and the one place that is
 * said: once when an outage begins and once when it ends, never once a batch. An exporter that
 * flooded a service's log every second it could not send would be one an operator turned off.
 *
 * The address is said without anything that could be a credential: no headers, and no user
 * information an address might carry.
 */
final class Outage(endpoint: URI, log: Logger, now: () => Long = () => System.nanoTime()):

  private val address =
    s"${endpoint.getScheme}://${endpoint.getHost}" + (if endpoint.getPort >= 0 then
                                                        s":${endpoint.getPort}"
                                                      else "")

  @volatile private var since: Option[Long] = None
  @volatile private var lostAtStart: Long   = 0L

  def inProgress: Boolean = since.isDefined

  /** An export failed: the first failure of an outage says so, and the rest say nothing. */
  def failed(reason: String, lostSoFar: => Long): Unit = synchronized {
    if since.isEmpty then
      since = Some(now())
      lostAtStart = lostSoFar
      log.warn(
        "telemetry: the collector at {} cannot be reached ({}). The service goes on as before; " +
          "spans wait in the trace window and those it overwrites before the collector can be " +
          "reached again are lost, and counted.",
        address,
        reason
      )
  }

  /**
   * An export succeeded: the first success after an outage says how long it was and what it lost.
   */
  def succeeded(lostSoFar: => Long): Unit = synchronized {
    since.foreach { started =>
      since = None
      log.info(
        "telemetry: the collector at {} can be reached again after {} s; {} spans were lost meanwhile.",
        address,
        ((now() - started) / 1_000_000_000L).toString,
        (lostSoFar - lostAtStart).toString
      )
    }
  }
