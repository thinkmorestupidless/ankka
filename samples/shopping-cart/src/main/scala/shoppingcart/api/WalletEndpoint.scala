package shoppingcart.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, Callers, HttpEndpoint, HttpProblem}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.stream.scaladsl.Source
import shoppingcart.application.WalletEntity
import shoppingcart.domain.{Deposit, DepositMade, DepositRequest, Wallet}

import scala.concurrent.duration.DurationInt

// docs:start granted-callers
/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
final class WalletEndpoint(client: ComponentClient) extends HttpEndpoint("/v1/wallets"):

  val acl: Acl = Acl.allowCallers(Callers.granted)

  /**
   * A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
   * the wallet applies each key once. The answer names the caller as the platform established it.
   */
  postBody("/{player}/{currency}/deposits") {
    (player: String, currency: String, body: DepositRequest) =>
      val key = request
        .header("Idempotency-Key")
        .getOrElse(throw HttpProblem.badRequest("a deposit needs an Idempotency-Key header"))
      val reply =
        wallet(player).call(WalletEntity.deposit).invoke(Deposit(key, currency, body.amount))
      DepositMade(reply.currency, reply.balance, reply.applied, Who(caller))
  }
  // docs:end granted-callers

  get("/{player}/{currency}") { (player: String, currency: String) =>
    wallet(player).call(WalletEntity.get).invoke().balance(currency).toString
  }

  /** The player's ledger as it changes, while the caller reads: a stream a grant may admit. */
  sse("/{player}/ledger") { (player: String) =>
    Source
      .tick(0.millis, 500.millis, ())
      .map(_ => describe(wallet(player).call(WalletEntity.get).invoke()))
      .mapMaterializedValue(_ => ())
  }

  /** Every frame answered with who sent it, until the socket closes: a socket a grant may admit. */
  socket[String]("/{player}/events") { (player, socket) =>
    Iterator
      .continually(socket.receive())
      .takeWhile(_.isDefined)
      .flatten
      .foreach(frame => socket.send(s"$player: $frame from ${Who(caller)}"))
  }

  /** The whole wallet, to the lobby of this project alone: a route no grant can open. */
  withAcl(Acl.allowCallers(Callers.service("lobby"))) {
    get("/{player}")((player: String) => describe(wallet(player).call(WalletEntity.get).invoke()))
  }

  private def describe(w: Wallet): String =
    w.balances.toVector.sorted.map((c, b) => s"$c $b").mkString(", ")

  private def wallet(player: String) = client.forEventSourcedEntity(EntityId(player))
