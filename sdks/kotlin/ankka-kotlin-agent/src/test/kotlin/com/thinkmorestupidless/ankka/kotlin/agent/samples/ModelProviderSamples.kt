package com.thinkmorestupidless.ankka.kotlin.agent.samples

// docs:start provider-imports
import com.thinkmorestupidless.ankka.agent.javasdk.Agents // the agents module
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProviders // every provider
// docs:end provider-imports
import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.AgentModule
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.kotlin.agent.effects

/** The agents guide's samples of choosing model providers, compiled with the tests so the page cannot drift. */
object ModelProviderSamples {
    // docs:start two-providers
    // Both read their key from the environment. Without it a provider still exists and every call to
    // it fails naming the variable, so the service starts with either key missing.
    val claude: ModelProvider = ModelProviders.anthropic("claude-opus-5") // ANTHROPIC_API_KEY, named "anthropic"
    val gpt: ModelProvider = ModelProviders.openAi("gpt-6.1-sol") // OPENAI_API_KEY, named "openai"

    // Any server speaking either protocol, under a name the service chooses.
    val local: ModelProvider = ModelProviders.preset("local", "ollama", "qwen3:8b")

    // One is the module's default; a handler names another with `model(...)` in its effects block.
    val agents: AgentModule = Agents.module().withModelProvider(claude)
    // docs:end two-providers

    // docs:start installation-model
    /** The installation's own model server, by the model's tag; its address and credential are the platform's. */
    fun installation(): ModelProvider = ModelProviders.installation("qwen3:0.6b")
    // docs:end installation-model

    // docs:start compaction
    /** The oldest messages of a session are summarised once its text passes 100,000 characters. */
    fun compactingService(model: ModelProvider): ServiceBuilder {
        val agents = Agents.module().withModelProvider(model).withCompaction(100_000, 10)
        return Ankka.service("support").withModule(agents).register(ApprovalAgent::class.java)
    }
    // docs:end compaction
}

// docs:start naming-a-provider
@ComponentId("triage")
class Triage : Agent() {
    @Command("route")
    fun route(question: String): AgentEffect<String> = effects {
        model(ModelProviderSamples.gpt).systemMessage("Say which team should answer.").userMessage(question).thenReply()
    }
}
// docs:end naming-a-provider
