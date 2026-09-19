package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Extension
import org.apache.pekko.actor.typed.ExtensionId

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One recorder per service, reachable from anywhere in it.
 *
 * A Pekko extension rather than something threaded through every constructor: the hosts that need
 * to record are built in several places and a recorder is per-service state, which is exactly what
 * an extension is for. It holds no actor reference and touches no `ActorContext`, so it is safe to
 * call from a handler thread — the recorded trap about `Future` callbacks does not apply.
 *
 * `capacity` comes from `ankka.observability.ring-capacity`, so an application that wants a longer
 * window can have one without a code change.
 */
final class Observability(val recorder: Recorder, val names: Names) extends Extension

object Observability extends ExtensionId[Observability]:

  def createExtension(system: ActorSystem[?]): Observability =
    val capacity =
      if system.settings.config.hasPath("ankka.observability.ring-capacity") then
        system.settings.config.getInt("ankka.observability.ring-capacity")
      else 4096
    new Observability(Recorder(capacity), new Names)

/**
 * Names on the way in, integers on the way out.
 *
 * A span records a component and a handler as `Int`s so that writing one allocates nothing and
 * copies no characters. This is where a name becomes an integer, once, and where a reader turns it
 * back into a name when somebody is actually looking at a trace.
 *
 * **What this costs on the hot path, stated plainly rather than claimed away**: interning a name
 * that has been seen before is one `ConcurrentHashMap` lookup. Not free — of the order of ten
 * nanoseconds beside the recorder's own twenty — but bounded, allocation-free after the first
 * sighting, and small against any real invocation. The alternative that *is* free is carrying the
 * index on the handler binding itself, which means changing a type in `sdk` that every sharded host
 * depends on; that trade is available later if measurement ever asks for it, and the measurement
 * has not asked.
 *
 * The table cannot grow without bound, because components reach the runtime only by explicit
 * registration and handler names are declared on typed companions. Both sets are fixed before the
 * first request.
 */
final class Names:
  private val ids  = new ConcurrentHashMap[String, Integer]()
  private val back = new ConcurrentHashMap[Integer, String]()
  private val next = new AtomicInteger(0)

  /** The integer for this name, assigning one the first time it is seen. */
  def intern(name: String): Int =
    val existing = ids.get(name)
    if existing ne null then existing.intValue
    else
      val assigned = Integer.valueOf(next.getAndIncrement())
      val raced    = ids.putIfAbsent(name, assigned)
      if raced ne null then raced.intValue
      else
        back.put(assigned, name)
        assigned.intValue

  /** The name behind an integer, for a reader. Unknown reads as unknown, never as an empty name. */
  def nameOf(id: Int): Option[String] = Option(back.get(Integer.valueOf(id)))
