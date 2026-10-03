package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.graph.GraphConsumer

/**
 * Over an event sourced entity: two nodes for an amount added — so one change has more than one
 * record — at the event's sequence number, or at the amount itself when it is a thousand or more; a
 * tombstone for each when the ledger is deleted.
 */
final class LedgerGraph extends GraphConsumer[LedgerEvent]:

  private def id = messageContext.subject

  def onMessage(event: LedgerEvent): Effect = event match
    case LedgerEvent.Added(amount) =>
      val ledger = graph.node(s"ledger:$id", Seq("Ledger"), Map("ledgerId" -> id))
      val latest = graph.node(s"latest:$id", Seq("Entry"), Map("amount" -> amount))
      if amount >= 1000 then effects.publish(ledger.at(amount), latest.at(amount))
      else effects.publish(ledger, latest)
    case LedgerEvent.Closed => effects.ignore()

  override def onDelete: Effect =
    effects.publish(graph.tombstoneNode(s"ledger:$id"), graph.tombstoneNode(s"latest:$id"))

object LedgerGraph
    extends GraphConsumer.Companion[LedgerGraph, LedgerEvent](
      componentId = ComponentId("ledger-graph"),
      source = ChangeSource.eventsOf(LedgerEntity),
      topic = "ledger-graph"
    ):
  def create(ctx: ConsumerContext) = new LedgerGraph

/** Over a key value entity: its node at each state's revision, its tombstone when it is deleted. */
final class ProfileGraph extends GraphConsumer[Profile]:

  def onMessage(profile: Profile): Effect =
    effects.publish(
      graph.node(
        s"profile:${messageContext.subject}",
        Seq("Profile"),
        Map("name" -> profile.name, "logins" -> profile.logins)
      )
    )

  override def onDelete: Effect =
    effects.publish(graph.tombstoneNode(s"profile:${messageContext.subject}"))

object ProfileGraph
    extends GraphConsumer.Companion[ProfileGraph, Profile](
      componentId = ComponentId("profile-graph"),
      source = ChangeSource.stateOf(ProfileEntity),
      topic = "profile-graph"
    ):
  def create(ctx: ConsumerContext) = new ProfileGraph

/**
 * Over a topic, whose messages have no sequence number: a node per sku, at the version the message
 * states in `delta` when its warehouse is "stated", and at no stated version otherwise.
 */
final class StockGraph extends GraphConsumer[StockEvent]:

  def onMessage(event: StockEvent): Effect =
    val node = graph.node(s"sku:${event.sku}", Seq("Sku"), Map("warehouse" -> event.warehouse))
    effects.publish(if event.warehouse == "stated" then node.at(event.delta) else node)

object StockGraph
    extends GraphConsumer.Companion[StockGraph, StockEvent](
      componentId = ComponentId("stock-graph"),
      source = ChangeSource.fromTopic(
        "stock-graph-events",
        Codecs.serializer[StockEvent]("stock-event"),
        StartFrom.Earliest
      ),
      topic = "stock-graph"
    ):
  def create(ctx: ConsumerContext) = new StockGraph
