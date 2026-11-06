package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.core.{ComponentId, DataSubject, EntityId, KeyAnswer}
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}
import org.apache.pekko.actor.typed.ActorSystem

import java.util.{Collections, IdentityHashMap}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * The agent records in memory that hold a data subject's conversation (feature 042, R23): sessions
 * and tasks that recovered or began with a subject, and autonomous instances tagged with one.
 *
 * An erasure destroys the key, and a record read from its journal from then on reads as erased; but
 * one already in memory read its conversation before, and keeps it until it leaves. This is how the
 * erasure finds those, to send them away. Kept per service, by the transport its components call
 * through, so two services in one JVM never send each other's records away.
 *
 * An entry outlives the record leaving memory on its own; sending away a record that is not in
 * memory recovers it and lets it go, which costs a read and changes nothing.
 */
private[ankka] object LiveSubjects:

  /** What holds a subject's conversation, and how it is sent away. */
  enum Holder:
    /** A session or a task: an entity, passivated so it reads its journal again. */
    case Entity(componentId: ComponentId, entityId: EntityId)

    /** An autonomous agent's instance, terminated by its host. */
    case Instance(componentId: ComponentId, instanceId: String)

  private val held =
    Collections.synchronizedMap(
      IdentityHashMap[CallTransport, ConcurrentHashMap[Holder, String]]()
    )

  private def of(client: ComponentClient): ConcurrentHashMap[Holder, String] =
    held.synchronized(held.computeIfAbsent(client.transportRef, _ => ConcurrentHashMap()))

  /** Notes that `holder`, of the service `client` calls for, holds `subject`'s conversation. */
  def hold(client: ComponentClient, holder: Holder, subject: String): Unit =
    of(client).put(holder, subject): Unit

  /** Forgets `holder`. */
  def release(client: ComponentClient, holder: Holder): Unit = of(client).remove(holder): Unit

  /** Everything held for `subject`, forgotten as it is answered. */
  def take(client: ComponentClient, subject: String): Vector[Holder] =
    val here  = of(client)
    val found = here.asScala.collect { case (h, s) if s == subject => h }.toVector
    found.foreach(here.remove)
    found

  /**
   * Whether `subject`'s key was destroyed, asked of the service's keyring: what an instance that
   * was not in memory at the erasure checks when it next wakes. A keyring that cannot answer is
   * taken as not erased, and the instance goes on until it can.
   */
  def erased(system: ActorSystem[?], subject: String): Boolean =
    DataSubject.from(subject).toOption.exists { s =>
      val keyring = com.thinkmorestupidless.ankka.runtime.keyring.ServiceKeyring.of(system)
      try keyring.key(keyring.project, s, create = false) == KeyAnswer.Destroyed
      catch case scala.util.control.NonFatal(_) => false
    }
