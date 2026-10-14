package com.thinkmorestupidless.ankka.runtime.replication

import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration

/**
 * The installations started in this JVM, by name, so the runtime's own transport reaches a sibling
 * in the same JVM straight through its sharding (the test kit's case) rather than over loopback.
 *
 * A name is one installation: a second service started under a name another holds is refused, since
 * two copies answering for one installation could each believe it is the home.
 */
private[ankka] object LocalInstallations:

  /** Enough of a started service to reach its entities. */
  final case class Local(system: ActorSystem[?], askTimeout: FiniteDuration)

  private val started = ConcurrentHashMap[String, Local]()

  def register(installation: String, local: Local): Unit =
    val before = started.putIfAbsent(installation, local)
    if before != null && (before ne local) then
      throw IllegalStateException(
        s"installation '$installation' is already started in this JVM; each installation is one service"
      )

  def unregister(installation: String, local: Local): Unit =
    started.remove(installation, local): Unit

  def lookup(installation: String): Option[Local] = Option(started.get(installation))
