package shoppingcart.kotlin.samples.transfers

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Body
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.orNull
import com.thinkmorestupidless.ankka.kotlin.stepRef
import java.time.Duration
import java.util.concurrent.Flow

@Manifest("wallet")
data class Wallet(val balance: Int, val frozen: Boolean)

/** A wallet the transfer moves money between; a frozen one refuses deposits. */
@ComponentId("wallet")
class WalletEntity : KeyValueEntity<Wallet>() {
    private val walletId: String
        get() = context().entityId()

    override fun emptyState() = Wallet(0, false)

    @Command("deposit")
    fun deposit(amount: Int): Effect<Done> = effects {
        when {
            amount <= 0 -> error("deposit must be positive")
            currentState().frozen -> error(Problem.conflict("wallet '$walletId' is frozen"))
            else -> updateState(currentState().copy(balance = currentState().balance + amount)).thenDone()
        }
    }

    @Command("withdraw")
    fun withdraw(amount: Int): Effect<Done> = effects {
        when {
            amount <= 0 -> error("withdrawal must be positive")
            currentState().balance < amount ->
                error("wallet '$walletId' holds ${currentState().balance}, less than $amount")
            else -> updateState(currentState().copy(balance = currentState().balance - amount)).thenDone()
        }
    }

    @Command("freeze")
    fun freeze(): Effect<Done> = effects { updateState(currentState().copy(frozen = true)).thenDone() }

    @Query("get")
    fun get(): ReadOnlyEffect<Wallet> = effects { reply(currentState()) }
}

@Manifest("transfer")
data class Transfer(val from: String, val to: String, val amount: Int)

@Manifest("transfer-state")
data class TransferState(val transfer: Transfer, val status: String)

/**
 * Moves money between two wallets and puts it back if the deposit fails. The class holds the
 * handlers; each annotation registers one under its wire name.
 */
@ComponentId("transfer")
class TransferWorkflow : Workflow<TransferState>() {
    override fun emptyState() = TransferState(Transfer("", "", 0), "not-started")

    // docs:start settings
    override fun settings(): WorkflowSettings =
        WorkflowSettings.builder()
            .timeout(Duration.ofSeconds(60))
            .defaultStepTimeout(Duration.ofSeconds(10))
            // One retry, then compensate. The deposit is the step that can fail for reasons
            // outside this workflow's control, so it is the one worth a second attempt.
            .stepRecovery(
                stepRef(TransferWorkflow::depositStep),
                RecoverStrategy.maxRetries(1).failoverTo(stepRef(TransferWorkflow::compensateStep)),
            )
            .build()
    // docs:end settings

    // docs:start start
    @Command("start")
    fun start(transfer: Transfer): Effect<Done> = effects {
        when {
            transfer.amount <= 0 -> error("transfer amount must be greater than zero")
            currentState().status != "not-started" -> error(Problem.conflict("transfer already started"))
            else ->
                updateState(TransferState(transfer, "started"))
                    .transitionTo(stepRef(TransferWorkflow::withdrawStep), transfer)
                    .thenDone()
        }
    }
    // docs:end start

    // docs:start steps
    @Step("withdraw")
    fun withdrawStep(transfer: Transfer): StepEffect {
        wallet(transfer.from).call(WalletEntity::withdraw).invoke(transfer.amount)
        return stepEffects {
            updateState(currentState().copy(status = "withdrawn")).thenTransitionTo(stepRef(TransferWorkflow::depositStep), transfer)
        }
    }

    @Step("deposit")
    fun depositStep(transfer: Transfer): StepEffect {
        wallet(transfer.to).call(WalletEntity::deposit).invoke(transfer.amount)
        return stepEffects { updateState(currentState().copy(status = "completed")).thenEnd() }
    }

    /**
     * Puts the money back. Takes no input and reads the transfer from the state, which is why
     * failover steps are input-free: what needs compensating is whatever the workflow has
     * recorded, not whatever was known when the settings were written.
     */
    @Step("compensate")
    fun compensateStep(): StepEffect {
        val transfer = currentState().transfer
        wallet(transfer.from).call(WalletEntity::deposit).invoke(transfer.amount)
        return stepEffects { updateState(currentState().copy(status = "compensated")).thenEnd() }
    }
    // docs:end steps

    @Query("status")
    fun status(): ReadOnlyEffect<TransferState> = effects { reply(currentState()) }

    private fun wallet(id: String) = context().componentClient().forKeyValueEntity(id)
}

/** Starts transfers and answers how they stand, how they ended, and their end as a stream. */
class TransferEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Post("/transfers/{id}")
    fun start(id: String, @Body transfer: Transfer): Done = componentClient().forWorkflow(id).call(TransferWorkflow::start).invoke(transfer)

    // docs:start lifecycle
    /** The engine's own view: running, paused, completed or failed, and why. */
    @Get("/transfers/{id}/lifecycle")
    fun lifecycle(id: String): String {
        val lifecycle = componentClient().forWorkflow(id).lifecycle(TransferWorkflow::class.java).invoke()
        return lifecycle.status() + (lifecycle.failure().orNull()?.let { ": $it" } ?: "")
    }
    // docs:end lifecycle

    // docs:start await-later
    /** Answers with a transfer started earlier, by anyone: at once if it has ended, when it ends if not. */
    @Get("/transfers/{id}/end")
    fun end(id: String): TransferState = componentClient().forWorkflow(id).awaitEnd(TransferWorkflow::class.java, Duration.ofSeconds(10))
    // docs:end await-later

    // docs:start sse-await
    /**
     * A transfer's end as server-sent events: a heartbeat while it runs, then the state, on one
     * connection however long that takes.
     */
    @Sse("/transfers/{id}/events")
    fun events(id: String): Flow.Publisher<SseEvent> =
        componentClient().forWorkflow(id).awaitEndEvents(TransferWorkflow::class.java, Duration.ofMinutes(10))
    // docs:end sse-await
}
