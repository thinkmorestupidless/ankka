package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.ConcurrentLinkedQueue

/** `players`: an account per player, its email personal and its currency not. */
final case class Opened(playerId: String, email: Personal[String], currency: String)
final case class Account(email: Option[Personal[String]], currency: String)

final class PlayerAccount(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Account, Opened]:
  def emptyState: Account                = Account(None, "")
  def applyEvent(event: Opened): Account = Account(Some(event.email), event.currency)
  private def subject                    = context.entityId.toString
  def open(email: String): Effect[Done] =
    effects
      .persist(Opened(subject, Personal.present(subject, email), "GBP"))
      .thenReply(_ => Done)
  def read: ReadOnlyEffect[Account] = effects.reply(currentState)

object PlayerAccount
    extends EventSourcedEntity.Companion[PlayerAccount, Account, Opened](
      componentId = ComponentId("player-accounts"),
      stateSerializer = Codecs.serializer[Account]("player-account"),
      eventSerializer = Codecs.serializer[Opened]("player-opened")
    ):
  given Serializer[Account]                      = stateSerializer
  def create(context: EventSourcedEntityContext) = new PlayerAccount(context)
  val open                                       = command("open")(_.open)
  val read                                       = query("read")(_.read)

final case class PlayerRow(playerId: String, email: Personal[String], currency: String)

final class PlayerRowsView extends View[Opened, PlayerRow]:
  def onChange(event: Opened): Effect =
    effects.updateRow(PlayerRow(event.playerId, event.email.forLookup, event.currency))

/**
 * The players' view, declared at the version `at`: raising it empties the table and reads again.
 */
final class PlayerRows(at: Int)
    extends View.Companion[PlayerRowsView, Opened, PlayerRow](
      componentId = ComponentId("player-rows"),
      source = ChangeSource.eventsOf(PlayerAccount),
      rowSerializer = Codecs.serializer[PlayerRow]("player-row")
    ):
  override def version: Option[Int]     = Some(at)
  def create(ctx: ViewComponentContext) = new PlayerRowsView

/** `wallet`: a balance per player, its holder's name personal and its amount not. */
final case class Funded(holder: Personal[String], amount: Long)
final case class Balance(holder: Option[Personal[String]], amount: Long)

final class Wallet(context: EventSourcedEntityContext) extends EventSourcedEntity[Balance, Funded]:
  def emptyState: Balance = Balance(None, 0)
  def applyEvent(event: Funded): Balance =
    Balance(Some(event.holder), currentState.amount + event.amount)
  def fund(holder: String): Effect[Done] =
    effects
      .persist(Funded(Personal.present(context.entityId.toString, holder), 100))
      .thenReply(_ => Done)
  def read: ReadOnlyEffect[Balance] = effects.reply(currentState)

object Wallet
    extends EventSourcedEntity.Companion[Wallet, Balance, Funded](
      componentId = ComponentId("wallets"),
      stateSerializer = Codecs.serializer[Balance]("wallet-balance"),
      eventSerializer = Codecs.serializer[Funded]("wallet-funded")
    ):
  given Serializer[Balance]                      = stateSerializer
  def create(context: EventSourcedEntityContext) = new Wallet(context)
  val fund                                       = command("fund")(_.fund)
  val read                                       = query("read")(_.read)

/** `engagement`: what a player was last seen as, personal, and how often, not. */
final case class Seen(name: Option[Personal[String]], visits: Int)

final class Engagement(context: KeyValueEntityContext) extends KeyValueEntity[Seen]:
  def emptyState: Seen = Seen(None, 0)
  def visit(name: String): Effect[Done] =
    effects
      .updateState(
        Seen(Some(Personal.present(context.entityId.toString, name)), currentState.visits + 1)
      )
      .thenReply(_ => Done)
  def read: ReadOnlyEffect[Seen] = effects.reply(currentState)

object Engagement
    extends KeyValueEntity.Companion[Engagement, Seen](
      componentId = ComponentId("engagements"),
      stateSerializer = Codecs.serializer[Seen]("engagement-seen")
    ):
  given Serializer[Seen]                     = stateSerializer
  def create(context: KeyValueEntityContext) = new Engagement(context)
  val visit                                  = command("visit")(_.visit)
  val read                                   = query("read")(_.read)

/**
 * Every run of `players`' erasure handler: the subject, whether it was a reapplication, its time.
 */
final case class HandlerRun(subject: String, reapply: Boolean, nanos: Long)

object PlayersHandler:
  val runs = ConcurrentLinkedQueue[HandlerRun]()

  /** Nothing of its own to erase but a note that it ran: what the second run finds at once. */
  val handler: ErasureHandler = ctx =>
    val started = System.nanoTime()
    runs.add(HandlerRun(ctx.subject, ctx.reapply, System.nanoTime() - started)): Unit
    ErasureOutcome.Done(if ctx.reapply then "nothing left" else "noted")
