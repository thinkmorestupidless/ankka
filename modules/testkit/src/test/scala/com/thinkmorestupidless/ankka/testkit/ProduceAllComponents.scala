package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** An event sourced entity for consumers to read: amounts added, then closed and deleted. */
final case class Ledger(total: Int)

enum LedgerEvent:
  case Added(amount: Int)
  case Closed

final class LedgerEntity extends EventSourcedEntity[Ledger, LedgerEvent]:

  def emptyState: Ledger = Ledger(0)

  def applyEvent(event: LedgerEvent): Ledger = event match
    case LedgerEvent.Added(amount) => Ledger(currentState.total + amount)
    case LedgerEvent.Closed        => currentState

  def add(amount: Int): Effect[Done] =
    effects.persist(LedgerEvent.Added(amount)).thenReply(_ => Done)

  def close: Effect[Done] = effects.persist(LedgerEvent.Closed).deleteEntity().thenReply(_ => Done)

object LedgerEntity
    extends EventSourcedEntity.Companion[LedgerEntity, Ledger, LedgerEvent](
      componentId = ComponentId("ledger"),
      stateSerializer = Codecs.serializer[Ledger]("ledger"),
      eventSerializer = Codecs.serializer[LedgerEvent]("ledger-event")
    ):
  def create(context: EventSourcedEntityContext) = new LedgerEntity

  val add   = command("add")(_.add)
  val close = command("close")(_.close)

/** What the fan-out consumers publish: the n-th line about `subject`, at the change's sequence. */
final case class Line(subject: String, sequence: Long, n: Int, note: String)

/**
 * Over the ledger's events: three lines for an amount added — the second under a key of its own —
 * none for an amount of zero, and two when the ledger is deleted.
 */
final class LedgerFanout extends Consumer[LedgerEvent, Line]:

  private def line(n: Int, note: String) =
    effects.message(Line(messageContext.subject, messageContext.sequenceNumber, n, note))

  def onMessage(event: LedgerEvent): Effect = event match
    case LedgerEvent.Added(0) => effects.produceAll(Nil)
    case LedgerEvent.Added(amount) =>
      effects.produceAll(
        Seq(
          line(1, s"added $amount"),
          line(2, s"added $amount").withKey(s"second:${messageContext.subject}"),
          line(3, s"added $amount")
        )
      )
    case LedgerEvent.Closed => effects.ignore()

  override def onDelete: Effect =
    effects.produceAll(
      Seq(line(1, "deleted"), line(2, "deleted").withKey(s"gone:${messageContext.subject}"))
    )

object LedgerFanout
    extends Consumer.Companion[LedgerFanout, LedgerEvent, Line](
      componentId = ComponentId("ledger-fanout"),
      source = ChangeSource.eventsOf(LedgerEntity)
    ):
  def create(ctx: ConsumerContext) = new LedgerFanout

  override val outputSerializer: Option[Serializer[Line]] =
    Some(Codecs.serializer[Line]("line"))

  override val produceTo: Option[String] = Some("ledger-lines")

/** Over a key value entity's states: two lines for a state, one for its deletion. */
final class ProfileFanout extends Consumer[Profile, Line]:

  private def line(n: Int, note: String) =
    effects.message(Line(messageContext.subject, messageContext.sequenceNumber, n, note))

  def onMessage(profile: Profile): Effect =
    effects.produceAll(
      Seq(
        line(1, profile.name),
        line(2, profile.name).withKey(s"name:${messageContext.subject}")
      )
    )

  override def onDelete: Effect = effects.produceAll(Seq(line(1, "deleted")))

object ProfileFanout
    extends Consumer.Companion[ProfileFanout, Profile, Line](
      componentId = ComponentId("profile-fanout"),
      source = ChangeSource.stateOf(ProfileEntity)
    ):
  def create(ctx: ConsumerContext) = new ProfileFanout

  override val outputSerializer: Option[Serializer[Line]] =
    Some(Codecs.serializer[Line]("line"))

  override val produceTo: Option[String] = Some("profile-lines")
