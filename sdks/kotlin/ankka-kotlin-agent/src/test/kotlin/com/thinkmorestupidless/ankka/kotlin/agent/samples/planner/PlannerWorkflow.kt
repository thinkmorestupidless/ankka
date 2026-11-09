package com.thinkmorestupidless.ankka.kotlin.agent.samples.planner

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.kotlin.agent.agentRef
import com.thinkmorestupidless.ankka.kotlin.agent.call
import com.thinkmorestupidless.ankka.kotlin.agent.forAgent
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.dsl.thenTransitionTo
import com.thinkmorestupidless.ankka.kotlin.stepRef
import com.thinkmorestupidless.ankka.kotlin.suspend.invokeSuspending
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Orchestrates the specialists into one plan.
 *
 * Three patterns in one workflow, which is the point of the sample:
 *
 * - **dynamic**: the first step *asks* which specialists are needed rather than hard-coding them,
 *   so adding a specialist changes no orchestration code;
 * - **parallel**: the second consults all of them at once, as coroutines, because they do not
 *   depend on each other;
 * - **durable**: each step's result is journalled before the next begins, so a crash between
 *   consulting and summarising resumes rather than re-consulting.
 *
 * Every agent is addressed with the *same session id*, the workflow's own id, so they accumulate
 * one shared conversation that the summariser can read back.
 */
@ComponentId("planner")
class PlannerWorkflow : Workflow<PlanState>() {

    data class Start(val userId: String, val destination: String)

    private val client get() = context().componentClient()

    // docs:start session
    /** Every agent in a plan is addressed with the same session id: the workflow's own id. */
    private val session: String
        get() = context().entityId()
    // docs:end session

    override fun emptyState(): PlanState = PlanState.empty()

    // docs:start settings
    override fun settings(): WorkflowSettings =
        WorkflowSettings.builder()
            .timeout(Duration.ofMinutes(5))
            // Model calls are slow, and a tool loop can legitimately take a while.
            .defaultStepTimeout(Duration.ofSeconds(90))
            .defaultRecovery(RecoverStrategy.maxRetries(1))
            .build()
    // docs:end settings

    @Command("start")
    fun start(request: Start): Effect<Done> = effects {
        when {
            request.destination.isBlank() -> error("a destination is required")
            currentState().status != PlanState.NOT_STARTED -> error(Problem.conflict("this plan has already started"))
            else ->
                updateState(PlanState(request.userId, request.destination, PlanState.SELECTING, null, emptyList(), null))
                    .transitionTo(stepRef(PlannerWorkflow::selectSpecialists), request.destination)
                    .thenDone()
        }
    }

    // docs:start select
    /** Asks the selector which specialists this request needs. */
    @Step("select-specialists")
    suspend fun selectSpecialists(destination: String): StepEffect {
        val selection = client.forAgent(session).call(agentRef(SelectorAgent::select)).invokeSuspending("Plan a trip to $destination")

        // Ignore anything the model invented that is not a real specialist.
        val known = selection.specialists.filter { it in Specialist.ALL }
        // A selector that names nothing usable should not stall the plan.
        val chosen = known.ifEmpty { listOf(Specialist.ACTIVITY) }

        return stepEffects {
            updateState(currentState().copy(status = PlanState.CONSULTING, selection = selection.copy(specialists = chosen)))
                .thenTransitionTo(PlannerWorkflow::consultSpecialists)
        }
    }
    // docs:end select

    // docs:start parallel
    /**
     * Consults every chosen specialist at once.
     *
     * One coroutine per specialist, on the step's own thread: the specialists are independent, so
     * waiting for each in turn would make the step as slow as the sum of the model calls instead of
     * the slowest one.
     */
    @Step("consult-specialists")
    suspend fun consultSpecialists(): StepEffect = coroutineScope {
        val state = currentState()
        val chosen = state.selection?.specialists.orEmpty()

        val pending =
            chosen.map { specialist ->
                specialist to
                    async {
                        when (specialist) {
                            Specialist.WEATHER ->
                                client.forAgent(session).call(agentRef(WeatherAgent::consult)).invokeSuspending(state.destination)
                            Specialist.ACTIVITY ->
                                client
                                    .forAgent(session)
                                    .call(ActivityAgent::consult)
                                    .invokeSuspending(ActivityAgent.Request(state.userId, state.destination))
                            Specialist.BUDGET ->
                                client.forAgent(session).call(agentRef(BudgetAgent::consult)).invokeSuspending(state.destination)
                            else -> "no specialist named '$specialist'"
                        }
                    }
            }

        val contributions = pending.map { (specialist, answer) -> Contribution(specialist, answer.await()) }

        stepEffects {
            updateState(currentState().copy(contributions = contributions)).thenTransitionTo(PlannerWorkflow::summarise)
        }
    }
    // docs:end parallel

    // docs:start summarise
    /** Combines the contributions, reading them back from the shared session. */
    @Step("summarise")
    suspend fun summarise(): StepEffect {
        val brief = client.forAgent(session).call(agentRef(SummaryAgent::summarise)).invokeSuspending(currentState().destination)

        return stepEffects { updateState(currentState().copy(status = PlanState.COMPLETED, summary = brief)).thenEnd() }
    }
    // docs:end summarise

    @Query("plan")
    fun plan(): ReadOnlyEffect<PlanState> = effects { reply(currentState()) }
}
