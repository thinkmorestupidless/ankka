package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.sdk.*

/** Payments' consumer of brand's `players` topic: what it was handed, by player. */
final class Cardholders extends Consumer[PlayerEvent, Nothing]:
  def onMessage(event: PlayerEvent): Effect =
    event match
      case PlayerEvent.Registered(id, _, name, _, _, _) => Cardholders.handed.put(id, name): Unit
      case _                                            => ()
    effects.done()

object Cardholders
    extends Consumer.Companion[Cardholders, PlayerEvent, Nothing](
      componentId = ComponentId("cardholders"),
      source = ChangeSource.fromTopic("players", PlayerEntity.eventSerializer, StartFrom.Earliest)
    ):
  def create(ctx: ConsumerContext) = new Cardholders
  val handed = java.util.concurrent.ConcurrentHashMap[String, Personal[String]]()

/** What payments keeps of a brand player: the name, still under brand's key. */
final case class CardholderRow(playerId: String, name: Personal[String])

final class CardholderRowsView extends View[PlayerEvent, CardholderRow]:
  def onChange(event: PlayerEvent): Effect = event match
    case PlayerEvent.Registered(id, _, name, _, _, _) => effects.updateRow(CardholderRow(id, name))
    case _                                            => effects.ignore()

object CardholderRows
    extends View.Companion[CardholderRowsView, PlayerEvent, CardholderRow](
      componentId = ComponentId("cardholder-rows"),
      source = ChangeSource.fromTopic("players", PlayerEntity.eventSerializer, StartFrom.Earliest),
      rowSerializer = Codecs.serializer[CardholderRow]("cardholder-row")
    ):
  def create(ctx: ViewComponentContext) = new CardholderRowsView
