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
