package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.Keyring
import org.apache.pekko.actor.typed.ActorSystem

import java.util.{Collections, IdentityHashMap}

/**
 * Which keyring each running service's hosts bind (feature 042), by the service's actor system: two
 * services in one JVM (two test kits) each have their own, which `Keyring.current`'s one default
 * could not give them.
 */
object ServiceKeyring:

  // By identity: two systems of one name compare equal, and a service stopping late must not take
  // away the keyring of the one that started after it.
  private val bySystem =
    Collections.synchronizedMap(IdentityHashMap[ActorSystem[?], Keyring]())

  def register(system: ActorSystem[?], keyring: Keyring): Unit = bySystem.put(system, keyring): Unit

  /** Forgets `system`'s keyring, if it is still `keyring`. */
  def unregister(system: ActorSystem[?], keyring: Keyring): Unit =
    bySystem.remove(system, keyring): Unit

  /** The keyring of the service on `system`; a system no service registered has none. */
  def of(system: ActorSystem[?]): Keyring = bySystem.getOrDefault(system, Keyring.unavailable)

  /** Runs `body` with the keyring of the service on `system` bound on this thread. */
  def binding[T](system: ActorSystem[?])(body: => T): T = Keyring.binding(of(system))(body)

  /**
   * Decodes what `body` decodes on a virtual thread, with the keyring of the service on `system`
   * bound. Where a value read from the database is decoded: never in a row mapper, which runs on
   * the database driver's event loop that every connection of the JVM shares, since a personal
   * field may wait on the keyring and that wait stalled every database's statements until the fetch
   * timed out (2026-11-05). The event loop also never saw the service's binding, and read with
   * whichever keyring was installed last.
   */
  def decoding[T](system: ActorSystem[?])(body: => T): scala.concurrent.Future[T] =
    scala.concurrent.Future(binding(system)(body))(using
      com.thinkmorestupidless.ankka.runtime.AnkkaExecutors.virtual
    )
