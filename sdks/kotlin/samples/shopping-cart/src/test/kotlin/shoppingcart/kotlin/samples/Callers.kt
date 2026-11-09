package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef
import com.thinkmorestupidless.ankka.javasdk.client.ServiceUnresolvable
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.Caller
import com.thinkmorestupidless.ankka.javasdk.http.Callers
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenReply
import com.thinkmorestupidless.ankka.kotlin.headerOrNull
import com.thinkmorestupidless.ankka.kotlin.queryOrNull
import com.thinkmorestupidless.ankka.kotlin.stepRef
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending

/** Who is calling, in words. */
internal fun whoIsCalling(caller: Caller): String =
    when (caller) {
        is Caller.Gateway -> "the internet, through the gateway"
        is Caller.Service -> "the ${caller.name()} service in project ${caller.project()}"
        is Caller.Machine -> "the machine ${caller.name()} of organization ${caller.organization()}"
        is Caller.Local -> "this machine"
    }

/** A caller as a word: `service:<project>/<name>`, `gateway`, `local`. */
internal fun describe(caller: Caller): String =
    when (caller) {
        is Caller.Service -> "service:${caller.project()}/${caller.name()}"
        is Caller.Machine -> "machine:${caller.organization()}/${caller.name()}"
        is Caller.Gateway -> "gateway"
        is Caller.Local -> "local"
    }

/**
 * Who is calling, as the platform established it. In a cluster every request arrives over mutual
 * TLS and the caller is read from the certificate the platform issued the calling workload; on a
 * laptop every caller is `Caller.Local`.
 */
class CallersEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        // docs:start who-is-calling
        get("/callers/whoami") { whoIsCalling(request.caller()) }
        // docs:end who-is-calling

        // docs:start call-another-service
        // Calls another service in this project, as this service: `/callers/whoami` unless `path`
        // says otherwise. The answer is what that service answered, and it saw this one as the caller.
        get("/callers/call/{service}") {
            val path = request.queryOrNull("path") ?: "/callers/whoami"
            try {
                services().of(path("service")).getText(path)
            } catch (e: ServiceUnresolvable) {
                throw ProblemException(Problem(503, "Service Unavailable", e.message ?: "unresolvable"))
            }
        }
        // docs:end call-another-service
    }
}

// docs:start allow-callers
// An ACL belongs to an endpoint class, so a route admitting other callers is a class of its own.

/** Only the internet and the orders service in this project; any other caller is refused 403. */
class OnlyOrdersEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.internet(), Callers.service("orders"))

    override fun routes() = routes {
        get("/callers/only-orders") { "admitted: ${describe(request.caller())}" }
    }
}

/** Another instance of this very service, and nothing else. */
class OnlySelfEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.self())

    override fun routes() = routes { get("/callers/only-self") { "admitted: myself" } }
}
// docs:end allow-callers

/** A player's wallet: a balance per currency, and the idempotency keys of every deposit applied. */
@Manifest("wallet")
data class Wallet(val balances: Map<String, Long> = emptyMap(), val applied: Set<String> = emptySet()) {
    fun balance(currency: String): Long = balances[currency] ?: 0L

    fun onDeposited(key: String, currency: String, amount: Long): Wallet =
        Wallet(balances + (currency to balance(currency) + amount), applied + key)
}

@Manifest("deposited")
data class Deposited(val key: String, val currency: String, val amount: Long)

data class Deposit(val key: String, val currency: String, val amount: Long)

/** What a deposit answers: the balance after it, and whether this request applied it. */
data class DepositReply(val currency: String, val balance: Long, val applied: Boolean)

data class DepositRequest(val amount: Long)

data class DepositMade(val currency: String, val balance: Long, val applied: Boolean, val by: String)

/** A player's wallet, one entity per player. A deposit whose key was already applied records nothing. */
@ComponentId("wallet")
class WalletEntity : EventSourcedEntity<Wallet, Deposited>() {
    override fun emptyState() = Wallet()

    override fun applyEvent(state: Wallet, event: Deposited): Wallet =
        state.onDeposited(event.key, event.currency, event.amount)

    @Command("deposit")
    fun deposit(request: Deposit): Effect<DepositReply> = effects {
        val balance = currentState().balance(request.currency)
        when {
            request.amount <= 0 -> error("a deposit is a positive amount")
            request.key in currentState().applied -> reply(DepositReply(request.currency, balance, false))
            else ->
                persist(Deposited(request.key, request.currency, request.amount)) thenReply
                    { DepositReply(request.currency, it.balance(request.currency), true) }
        }
    }

    @Query("get")
    fun get(): ReadOnlyEffect<Wallet> = effects { reply(currentState()) }
}

// docs:start granted-callers
/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
class WalletEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.granted())

    override fun routes() = routes {
        /*
         * A deposit, with the caller's idempotency key: a granted call is delivered at least once,
         * and the wallet applies each key once. The answer names the caller as the platform
         * established it.
         */
        post<DepositRequest>("/v1/wallets/{player}/{currency}/deposits") { body ->
            val key =
                request.headerOrNull("Idempotency-Key")
                    ?: throw ProblemException(Problem.badRequest("a deposit needs an Idempotency-Key header"))
            val deposit = Deposit(key, path("currency"), body.amount)
            val reply = wallet(path("player")).call(WalletEntity::deposit).invokeSuspending(deposit)
            DepositMade(reply.currency, reply.balance, reply.applied, describe(request.caller()))
        }
    }

    private fun wallet(player: String): EntityRef = componentClient().forEventSourcedEntity(player)
}
// docs:end granted-callers

@Manifest("payout-state")
data class PayoutState(val answer: String)

// docs:start step-calls-service
/**
 * Asks the PSP gateway service to start a payout, from a step: a step may call another service, as
 * this service, and goes on with the answer.
 */
@ComponentId("payout")
class PayoutWorkflow : Workflow<PayoutState>() {
    override fun emptyState() = PayoutState("")

    @Command("start")
    fun start(amount: Int): Effect<Done> = effects {
        updateState(emptyState()).transitionTo(stepRef(PayoutWorkflow::initiatePayout), amount).thenDone()
    }

    @Step("initiate-payout")
    fun initiatePayout(amount: Int): StepEffect {
        val answer = services().of("psp-gateway").getText("/payouts?amount=$amount")
        return stepEffects { updateState(currentState().copy(answer = answer)).thenEnd() }
    }

    @Query("get")
    fun get(): ReadOnlyEffect<PayoutState> = effects { reply(currentState()) }
}
// docs:end step-calls-service
