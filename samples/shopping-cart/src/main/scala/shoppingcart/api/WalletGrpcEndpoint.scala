package shoppingcart.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.grpc.GrpcEndpoint
import com.thinkmorestupidless.ankka.http.{Acl, Callers, EndpointClients}
import shoppingcart.application.WalletEntity
import shoppingcart.domain.Deposit
import shoppingcart.v1.wallet.{BalanceReply, DepositReply, WalletServiceGrpc}

/**
 * The wallet over gRPC, under granted callers (feature 040): a grant names one method, so a caller
 * granted `WalletService/Deposit` is refused `GetBalance`.
 */
final class WalletGrpcEndpoint(clients: EndpointClients)
    extends GrpcEndpoint(WalletServiceGrpc.SERVICE):

  val acl: Acl = Acl.allowCallers(Callers.granted)

  unary(WalletServiceGrpc.METHOD_DEPOSIT) { request =>
    val reply = wallet(request.player)
      .call(WalletEntity.deposit)
      .invoke(Deposit(request.idempotencyKey, request.currency, request.amount))
    DepositReply(reply.currency, reply.balance, reply.applied)
  }

  unary(WalletServiceGrpc.METHOD_GET_BALANCE) { request =>
    val w = wallet(request.player).call(WalletEntity.get).invoke()
    BalanceReply(request.currency, w.balance(request.currency))
  }

  private def wallet(player: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(player))
