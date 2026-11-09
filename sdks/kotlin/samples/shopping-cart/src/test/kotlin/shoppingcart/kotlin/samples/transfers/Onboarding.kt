package shoppingcart.kotlin.samples.transfers

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.stepRef
import java.time.Duration

@Manifest("kyc")
data class Kyc(val applicant: String, val steps: List<String>, val decision: String?)

/** A check of an applicant over two steps, `documents` and `decision`. */
@ComponentId("kyc")
class KycWorkflow : Workflow<Kyc>() {
    override fun emptyState() = Kyc("", emptyList(), null)

    @Command("start")
    fun start(applicant: String): Effect<Done> = effects {
        updateState(currentState().copy(applicant = applicant)).transitionTo(KycWorkflow::documentsStep).thenDone()
    }

    @Step("documents")
    fun documentsStep(): StepEffect = stepEffects {
        updateState(currentState().copy(steps = currentState().steps + "documents")).thenTransitionTo(stepRef(KycWorkflow::decisionStep))
    }

    @Step("decision")
    fun decisionStep(): StepEffect {
        val decision = if (currentState().applicant.startsWith("refused")) "declined" else "approved"
        return stepEffects { updateState(currentState().copy(steps = currentState().steps + "decision", decision = decision)).thenEnd() }
    }
}

@Manifest("onboarding")
data class Onboarding(val applicant: String, val status: String)

/**
 * Onboards an applicant: its `verify` step starts a `kyc` workflow for the applicant and waits for
 * its end within the step's own timeout, then goes on to the step the kyc's decision chooses.
 */
@ComponentId("onboarding")
class OnboardingWorkflow : Workflow<Onboarding>() {
    override fun emptyState() = Onboarding("", "not-started")

    override fun settings(): WorkflowSettings =
        WorkflowSettings.builder()
            .defaultStepTimeout(Duration.ofSeconds(60))
            .stepTimeout(stepRef(OnboardingWorkflow::verifyStep), VERIFY_TIMEOUT)
            .build()

    @Command("start")
    fun start(applicant: String): Effect<Done> = effects {
        updateState(Onboarding(applicant, "verifying")).transitionTo(OnboardingWorkflow::verifyStep).thenDone()
    }

    // docs:start step-awaits
    @Step("verify")
    fun verifyStep(): StepEffect {
        val kyc =
            context()
                .componentClient()
                .forWorkflow("kyc-${context().entityId()}")
                .callThenAwaitEnd(KycWorkflow::start, VERIFY_TIMEOUT)
                .invoke(currentState().applicant)
        return stepEffects {
            if (kyc.decision == "approved") thenTransitionTo(stepRef(OnboardingWorkflow::welcomeStep))
            else thenTransitionTo(stepRef(OnboardingWorkflow::declineStep))
        }
    }
    // docs:end step-awaits

    @Step("welcome")
    fun welcomeStep(): StepEffect = stepEffects { updateState(currentState().copy(status = "welcomed")).thenEnd() }

    @Step("decline")
    fun declineStep(): StepEffect = stepEffects { updateState(currentState().copy(status = "declined")).thenEnd() }

    @Query("status")
    fun status(): ReadOnlyEffect<Onboarding> = effects { reply(currentState()) }

    companion object {
        val VERIFY_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
