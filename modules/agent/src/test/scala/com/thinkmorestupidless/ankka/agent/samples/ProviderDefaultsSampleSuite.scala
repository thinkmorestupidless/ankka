package com.thinkmorestupidless.ankka.agent.samples

import com.thinkmorestupidless.ankka.agent.{AnthropicProvider, Effort}

/** The agents guide's sample of changing a provider's defaults. */
class ProviderDefaultsSampleSuite extends munit.FunSuite:

  test("a provider's defaults are given where the provider is made") {
    // docs:start defaults
    val model = AnthropicProvider.fromEnv(
      modelName = "claude-opus-5",
      defaults =
        AnthropicProvider.Defaults(maxTokens = 16000L, thinking = true, effort = Some(Effort.High))
    )
    // docs:end defaults
    assertEquals(model.modelName, "claude-opus-5")
    assertEquals(model.name, AnthropicProvider.Name)
  }
