package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*

/**
 * Numbers a process reports about something it watches rather than something it does (feature 041):
 * how far behind a project's archive is, whether its backups are failing. Each is a name and a set
 * of attributes, with the latest value set; nothing here exports anything, and no library is
 * needed. `ankka-telemetry-otlp` reads it as it reads `Recorder.totals`, registering a gauge for a
 * name the first time it is set, so a name set long after the exporter started is still exported.
 *
 * One per JVM (`Gauges.global`), as a process has one exporter. A test builds its own.
 */
final class Gauges:

  private val values =
    new ConcurrentHashMap[String, ConcurrentHashMap[Map[String, String], Double]]()
  private val descriptions = new ConcurrentHashMap[String, String]()
  private val listeners    = new CopyOnWriteArrayList[(String, String) => Unit]()

  /** The value of `name` for `attributes`, from now on; the first set of a name announces it. */
  def set(
      name: String,
      attributes: Map[String, String],
      value: Double,
      description: String = ""
  ): Unit =
    val isNew = descriptions.putIfAbsent(name, description) == null
    values.computeIfAbsent(name, _ => new ConcurrentHashMap()).put(attributes, value): Unit
    if isNew then listeners.asScala.foreach(_(name, description))

  /** `name` no longer has a value for `attributes`: a project that went is no longer reported. */
  def remove(name: String, attributes: Map[String, String]): Unit =
    Option(values.get(name)).foreach(_.remove(attributes)): Unit

  /** The attribute sets `name` has a value for. */
  def attributesOf(name: String): Set[Map[String, String]] =
    Option(values.get(name)).map(_.keySet.asScala.toSet).getOrElse(Set.empty)

  /** Every value of `name` now. */
  def snapshot(name: String): Vector[(Map[String, String], Double)] =
    Option(values.get(name))
      .map(_.entrySet.asScala.toVector.map(e => e.getKey -> e.getValue))
      .getOrElse(Vector.empty)

  def names: Set[String] = descriptions.keySet.asScala.toSet

  /**
   * Calls `listener` with each name and its description: every one set already, now, and every one
   * set for the first time later.
   */
  def onNewName(listener: (String, String) => Unit): Unit =
    listeners.add(listener): Unit
    descriptions.asScala.foreach(listener.tupled)

object Gauges:

  /** The process's gauges, which the telemetry module exports. */
  val global: Gauges = new Gauges
