package shoppingcart.application

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import shoppingcart.domain.*
import shoppingcart.domain.WalletEvent.*

/**
 * A player's wallet, one entity per player. A deposit whose key was already applied records nothing
 * and answers the balance as it is: the wallet makes a retried call safe, since nothing else does.
 */
final class WalletEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Wallet, WalletEvent]:

  def emptyState: Wallet = Wallet(context.entityId)

  def applyEvent(event: WalletEvent): Wallet = event match
    case Deposited(key, currency, amount) => currentState.onDeposited(key, currency, amount)

  // docs:start idempotent-deposit
  def deposit(request: Deposit): Effect[DepositReply] =
    if request.key.isEmpty then effects.error("a deposit needs an idempotency key")
    else if request.amount <= 0 then
      effects.error(s"amount must be positive, was ${request.amount}")
    else if currentState.applied.contains(request.key) then
      effects.reply(DepositReply(request.currency, currentState.balance(request.currency), false))
    else
      effects
        .persist(Deposited(request.key, request.currency, request.amount))
        .thenReply(w => DepositReply(request.currency, w.balance(request.currency), true))
  // docs:end idempotent-deposit

  def get: ReadOnlyEffect[Wallet] = effects.reply(currentState)

object WalletEntity
    extends EventSourcedEntity.Companion[WalletEntity, Wallet, WalletEvent](
      componentId = ComponentId("wallet"),
      stateSerializer = Codecs.serializer[Wallet]("wallet"),
      eventSerializer = Codecs.serializer[WalletEvent]("wallet-event")
    ):

  given Serializer[Deposit]      = Codecs.serializer[Deposit]("deposit")
  given Serializer[DepositReply] = Codecs.serializer[DepositReply]("deposit-reply")

  def create(context: EventSourcedEntityContext) = new WalletEntity(context)

  val deposit = command("deposit")(_.deposit)
  val get     = query("get")(_.get)
