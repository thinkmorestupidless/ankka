package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.runtime.{AnkkaService, RuntimeExtension}
import org.slf4j.LoggerFactory

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.util.control.NonFatal

/**
 * Replaces the machine token signing key every thirty days, and drops one thirty-one days old that
 * is no longer the newest (feature 040). Each control plane instance looks every `every` minutes;
 * the sweep is the same on every instance, and a rotation two instances make at once adds two keys,
 * both published, which signs nothing wrongly. Only for keys the Secret holds: keys in memory
 * belong to one process, which a restart replaces anyway.
 */
final class MachineKeyRotation(keys: MachineKeys, everyMinutes: Long = 60) extends RuntimeExtension:

  private val log = LoggerFactory.getLogger(classOf[MachineKeyRotation])

  @volatile private var scheduler: Option[ScheduledExecutorService] = None

  def name: String = "machine-key-rotation"

  def start(service: AnkkaService): Unit =
    val _ = service
    val s = Executors.newSingleThreadScheduledExecutor(r =>
      val t = Thread(r, "machine-key-rotation")
      t.setDaemon(true)
      t
    )
    s.scheduleAtFixedRate(() => check(), everyMinutes, everyMinutes, TimeUnit.MINUTES): Unit
    scheduler = Some(s)

  /** One look: a rotation when one is due, then the sweep. */
  def check(): Unit =
    try
      if keys.keys.nonEmpty && keys.due then
        log.info("rotated the machine token signing key: {}", keys.rotate())
      val dropped = keys.sweep()
      if dropped.nonEmpty then log.info("dropped machine token keys {}", dropped.mkString(", "))
    catch case NonFatal(e) => log.warn("machine token keys could not be rotated: {}", e.toString)

  override def stop(): Unit =
    scheduler.foreach(_.shutdownNow()): Unit
    scheduler = None
