package com.thinkmorestupidless.ankka.testkit

// docs:start provider-imports
import com.thinkmorestupidless.ankka.agent.* // AnthropicProvider, AgentRuntime
import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import com.thinkmorestupidless.ankka.agent.providers.ModelProviders
// docs:end provider-imports
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

/**
 * The agents guide's samples of choosing model providers. Compiled with the test sources so the
 * pages cannot show code that does not build; `ModelProvidersSuite` proves what they describe.
 */
object ModelProviderSamples:

  // docs:start two-providers
  // Both read their key from the environment. Without it a provider still exists and every call to
  // it fails naming the variable, so the service starts with either key missing.
  val claude = AnthropicProvider.fromEnv() // ANTHROPIC_API_KEY, named "anthropic"
  val gpt    = OpenAiProvider.fromEnv()    // OPENAI_API_KEY, named "openai"

  // Any server speaking either protocol, under a name the service chooses.
  val local = ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))

  // One is the default; a handler names another with `effects.model(...)`.
  val runtime = AgentRuntime.withDefaultModel(claude)
  // docs:end two-providers

  // docs:start local-provider
  val laptop = ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))

  val onTheLaptop = AgentRuntime.withDefaultModel(laptop)
  // docs:end local-provider

  // docs:start naming-a-provider
  final class Triage extends Agent:
    def route(question: String): Effect[String] =
      effects
        .model(gpt)
        .systemMessage("Say which team should answer.")
        .userMessage(question)
        .thenReply()

  object Triage extends Agent.Companion[Triage](ComponentId("triage")):
    def create(context: AgentContext) = new Triage
    val route                         = command("route")(_.route)
  // docs:end naming-a-provider
