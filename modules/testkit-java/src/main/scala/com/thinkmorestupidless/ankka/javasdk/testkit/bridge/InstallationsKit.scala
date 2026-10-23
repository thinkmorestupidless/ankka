package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.testkit.Settings
import com.thinkmorestupidless.ankka.testkit as stk

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/** The Java group of installations over the Scala one. */
final class InstallationsKit private (group: stk.AnkkaTestKit.Installations):
  private val kits = TrieMap.empty[String, ServiceKit]

  def names(): java.util.List[String] = group.names.asJava

  def start(name: String, service: ServiceBuilder, settings: Settings): ServiceKit =
    val kit = ServiceKit.startWith(service, settings) { (descriptors, extensions, config, _) =>
      group.start(name, descriptors, extensions, settings = config)
    }
    kits.put(name, kit): Unit
    kit

  def restart(name: String, service: ServiceBuilder): ServiceKit =
    val kit = ServiceKit.startWith(service, Settings.defaults()) { (descriptors, extensions, _, _) =>
      group.restart(name, descriptors, extensions)
    }
    kits.put(name, kit): Unit
    kit

  def get(name: String): ServiceKit =
    kits.getOrElse(name, throw IllegalStateException(s"installation '$name' is not started"))

  def pause(installation: String): Unit       = group.transport.pause(installation)
  def resume(installation: String): Unit      = group.transport.resume(installation)
  def block(from: String, to: String): Unit   = group.transport.block(from, to)
  def unblock(from: String, to: String): Unit = group.transport.unblock(from, to)
  def close(): Unit                           = kits.values.foreach(_.close())

object InstallationsKit:
  def of(names: Array[String]): InstallationsKit =
    InstallationsKit(stk.AnkkaTestKit.installations(names.toSeq*))
