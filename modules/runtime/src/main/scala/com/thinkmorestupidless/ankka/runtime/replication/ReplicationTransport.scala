package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.JournalRecord
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done, NotUsed}

import scala.concurrent.Future

/**
 * What carries a replicated entity's records and forwarded commands between installations (feature
 * 051).
 *
 * The one interface the runtime's replicated entities depend on: the runtime ships its own
 * implementation for the test kit and for two services on one machine, and a transport between
 * installations is supplied from outside the runtime, found by a `ReplicationTransportProvider` on
 * the classpath. Nothing here is the runtime's alone to see, so such a transport needs nothing the
 * runtime does not expose through this trait.
 *
 * What the runtime guarantees a transport:
 *
 *   - `records` is opened by a follower from the sequence number after the last it holds, and
 *     opened again from there after any completion or failure;
 *   - `forward` is called only for a command that may persist, at a copy that is not the home, with
 *     the home its own journal names or the one `locate` named;
 *   - `report` is called by the target of a pending handover after each record it persists;
 *   - `locate` is called at most once per command, for a copy that holds no record;
 *   - records are never asked for on behalf of a copy in conflict.
 *
 * What the runtime relies on:
 *
 *   - one installation's records of one entity arrive in sequence order with no gaps;
 *   - a record's `installation` is what its writer stamped; a transport never rewrites a record;
 *   - `forward` delivers the call once or fails, and never retries: a command is sent once;
 *   - `locate` answers from facts held at an installation, not from a cache it cannot invalidate.
 */
trait ReplicationTransport:

  /** For logs and the start refusal. */
  def name: String

  /**
   * The records of one entity held at `installation`, from sequence number `from` upwards, in
   * order, live: the source stays open and emits each record as the installation holds it.
   * Completing or failing is a signal to open it again from the caller's own position.
   */
  def records(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      from: Long
  ): Source[Replicated, NotUsed]

  /**
   * Handles `call` at `installation` as that installation's copy would handle a call from a local
   * caller, and returns the reply. The call carries the caller's metadata unchanged. Fails when the
   * installation cannot be reached.
   */
  def forward(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      call: ForwardedCall
  ): Future[ForwardedReply]

  /** Tells `installation` the sequence number this installation holds for the entity. */
  def report(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      position: Long
  ): Future[Done]

  /**
   * Where an entity this installation has never held lives: its home and its replication set, or
   * `None` when no installation that can be reached holds it.
   */
  def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]]

  /**
   * The entities other installations advanced: an element says `installation` wrote the entity up
   * to `position`. Best effort; a copy nobody wakes is behind until it is read.
   */
  def advanced: Source[Advanced, NotUsed]

  /** Whether `installation` can be reached now. */
  def reachable(installation: String): Boolean

  /** Releases what the transport holds. Called once the service's entities have stopped. */
  def stop(): Unit = ()

/** One record of an entity's journal, at its sequence number. */
final case class Replicated(sequenceNr: Long, record: JournalRecord)

/** A call a copy that is not the home hands to the home. */
final case class ForwardedCall(method: MethodName, payload: Array[Byte], metadata: Metadata)

/** The home's answer to a forwarded call. */
enum ForwardedReply:
  case Succeeded(payload: Array[Byte], metadata: Metadata)
  case Rejected(error: CommandError)

/** Where an entity lives: its home and its replication set. */
final case class Located(home: String, replicationSet: Set[String])

/**
 * `installation` wrote the entity up to `position`. `joined` when the record that advanced it put
 * the installation that reads this in the replication set: a copy that holds no record of the
 * entity is woken only for that.
 */
final case class Advanced(
    installation: String,
    componentId: ComponentId,
    entityId: EntityId,
    position: Long,
    joined: Boolean = false
)

/**
 * Supplies a transport, found through `java.util.ServiceLoader` as `RuntimeExtensionProvider` is: a
 * jar on the classpath names its provider in
 * `META-INF/services/com.thinkmorestupidless.ankka.runtime.replication.ReplicationTransportProvider`.
 */
trait ReplicationTransportProvider:

  /**
   * `None` when this provider is not configured; the runtime then falls back or refuses.
   *
   * `replicated` is every component of the service declared replicated, the set the test kit's
   * `ReplicationTransportFactory` is given too, and empty when none is: a transport serves and
   * tails those and no others (spec 057; a provider written against feature 051's two-argument form
   * does not compile against this one).
   */
  def transport(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings,
      replicated: Set[ComponentId]
  ): Option[ReplicationTransport]
