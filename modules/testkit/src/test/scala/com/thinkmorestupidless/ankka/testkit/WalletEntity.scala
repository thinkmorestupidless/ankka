package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** A wallet, so a transfer has something to move money between. */
final case class Wallet(balance: Int, frozen: Boolean)

final class WalletEntity(context: KeyValueEntityContext) extends KeyValueEntity[Wallet]:

  private val walletId: String = context.entityId

  def emptyState: Wallet = Wallet(0, frozen = false)

  def deposit(amount: Int): Effect[Done] =
    if amount <= 0 then effects.error("deposit must be positive")
    else if currentState.frozen then
      effects.error(s"wallet '$walletId' is frozen", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(balance = currentState.balance + amount))
        .thenReply(_ => Done)

  def withdraw(amount: Int): Effect[Done] =
    if amount <= 0 then effects.error("withdrawal must be positive")
    else if currentState.balance < amount then
      effects.error(
        s"wallet '$walletId' has ${currentState.balance}, cannot withdraw $amount",
        ErrorCode.Conflict
      )
    else
      effects
        .updateState(currentState.copy(balance = currentState.balance - amount))
        .thenReply(_ => Done)

  /** Makes deposits fail, so compensation paths can be exercised deliberately. */
  def freeze: Effect[Done] =
    effects.updateState(currentState.copy(frozen = true)).thenReply(_ => Done)

  def balance: ReadOnlyEffect[Int] = effects.reply(currentState.balance)

object WalletEntity
    extends KeyValueEntity.Companion[WalletEntity, Wallet](
      componentId = ComponentId("wallet"),
      stateSerializer = Codecs.serializer[Wallet]("wallet")
    ):
  def create(context: KeyValueEntityContext) = new WalletEntity(context)

  val deposit  = command("deposit")(_.deposit)
  val withdraw = command("withdraw")(_.withdraw)
  val freeze   = command("freeze")(_.freeze)
  val balance  = query("balance")(_.balance)
