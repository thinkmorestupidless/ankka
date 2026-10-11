package shoppingcart.domain

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs

/**
 * A player's wallet: a balance per currency, and the idempotency keys of every deposit applied.
 *
 * The keys are what make a deposit safe to send twice. A call another service makes under a grant
 * is delivered at least once, and the platform does not deduplicate it: the wallet does, by the key
 * the caller sends with each deposit.
 */
final case class Wallet(
    player: String,
    balances: Map[String, Long] = Map.empty,
    applied: Set[String] = Set.empty
):
  def balance(currency: String): Long = balances.getOrElse(currency, 0L)

  def onDeposited(key: String, currency: String, amount: Long): Wallet =
    copy(
      balances = balances.updated(currency, balance(currency) + amount),
      applied = applied + key
    )

object Wallet:
  given JsonValueCodec[Wallet] = Codecs.make[Wallet]

enum WalletEvent:
  case Deposited(key: String, currency: String, amount: Long)

/** A deposit as the wallet's command takes it: the key, the currency and the amount. */
final case class Deposit(key: String, currency: String, amount: Long)

/** A deposit's body on the wallet's route; the key arrives as the `Idempotency-Key` header. */
final case class DepositRequest(amount: Long)

object DepositRequest:
  given JsonValueCodec[DepositRequest] = Codecs.make[DepositRequest]

/** What a deposit answers: the balance after it, and whether this request applied it. */
final case class DepositReply(currency: String, balance: Long, applied: Boolean)

object DepositReply:
  given JsonValueCodec[DepositReply] = Codecs.make[DepositReply]

/** What the deposit route answers: the wallet's reply, and the caller the platform established. */
final case class DepositMade(currency: String, balance: Long, applied: Boolean, by: String)

object DepositMade:
  given JsonValueCodec[DepositMade] = Codecs.make[DepositMade]
