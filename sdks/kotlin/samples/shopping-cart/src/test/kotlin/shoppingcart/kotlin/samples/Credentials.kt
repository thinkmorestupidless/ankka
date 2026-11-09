package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.Step
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.Workflow
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.dsl.routes
import com.thinkmorestupidless.ankka.kotlin.dsl.stepEffects
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.stepRef

data class KeepSecret(val name: String, val value: String)

// docs:start endpoint
/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
class ProviderCredentialsEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        post<KeepSecret>("/credentials") { request ->
            secrets().put(request.name, request.value)
            Done.done()
        }

        get("/credentials") {
            val name = request.requiredQuery("name")
            secrets().getOrNull(name) ?: throw ProblemException(Problem.notFound("no secret '$name'"))
        }

        delete("/credentials") {
            secrets().delete(request.requiredQuery("name"))
            Done.done()
        }
    }
}
// docs:end endpoint

data class Charge(val provider: String, val amount: Long)

@Manifest("charge-state")
data class ChargeState(val provider: String, val credentialLength: Int, val status: String)

// docs:start workflow
/**
 * Charges a customer through a payment provider. The step reads the provider's credential from the
 * secret store when it needs it, and records only that it charged.
 */
@ComponentId("charge")
class ChargeWorkflow : Workflow<ChargeState>() {
    override fun emptyState() = ChargeState("", 0, "not-started")

    @Command("start")
    fun start(charge: Charge): Effect<Done> = effects {
        updateState(ChargeState(charge.provider, 0, "started")).transitionTo(stepRef(ChargeWorkflow::chargeStep), charge).thenDone()
    }

    @Step("charge")
    fun chargeStep(charge: Charge): StepEffect {
        val credential =
            secrets().getOrNull("provider/${charge.provider}")
                ?: throw IllegalStateException("no credential for ${charge.provider}")
        // Calling the provider with `credential` goes here. The state records that it was charged, and
        // never the credential.
        return stepEffects { updateState(currentState().copy(credentialLength = credential.length, status = "charged")).thenEnd() }
    }

    @Query("get")
    fun get(): ReadOnlyEffect<ChargeState> = effects { reply(currentState()) }
}
// docs:end workflow
