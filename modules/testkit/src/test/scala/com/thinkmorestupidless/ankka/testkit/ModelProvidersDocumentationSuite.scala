package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.providers.{Presets, ProviderConfig}

import java.nio.file.{Files, Path}

/**
 * `features/documentation/model-providers.feature`: the statements the published documentation must
 * make about model providers, held to the pages. One test per scenario, named after it.
 */
class ModelProvidersDocumentationSuite extends munit.FunSuite:

  private lazy val docs: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir.resolve("docs")

  private def page(path: String): String =
    Files.readString(docs.resolve(path)).replaceAll("\\s+", " ")

  private def says(path: String, statements: String*): Unit =
    val text = page(path)
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  test(
    "the documentation lists every way a service is given a model provider and what each setting does on each"
  ) {
    says(
      "build/agents.md",
      "`AnthropicProvider.fromEnv()`",
      "`OpenAiProvider.fromEnv()`",
      "`ModelProviders.named`",
      "a preset",
      "`effects.model(provider)`"
    )
    says(
      "reference/model-providers.md",
      "`ANTHROPIC_API_KEY`",
      "`OPENAI_API_KEY`",
      "`with_model(\"fast\")` in Python, `withModel(\"fast\")` in TypeScript and `.model(\"fast\")` in Rust",
      "### Which is the default",
      "`ANKKA_MODEL_DEFAULT` names it",
      "## What each setting does",
      "| most tokens |",
      "| thinking on |",
      "| thinking off |",
      "| effort |",
      "| temperature |",
      "A setting an endpoint does not accept is withheld, never a failed call"
    )
  }

  test("the documentation lists every preset and what it sets") {
    val text = page("reference/model-providers.md")
    for name <- Presets.names do
      assert(text.contains(s"| `$name` |"), s"the preset table has no row for $name")
    says(
      "reference/model-providers.md",
      "| Preset | Protocol | Shape | Address | Key | Default model | Reasoning efforts | Temperature | Tool call ids | Notes |"
    )
  }

  test("the documentation gets a model on the developer's own machine answering an agent") {
    says(
      "deploy/local-models.md",
      "ollama pull qwen3:8b",
      "## In a Scala service",
      "## In a Python, TypeScript or Rust service",
      "ANKKA_MODEL_LOCAL_PRESET=ollama",
      "http://host.docker.internal:11434/v1",
      "## Which models call tools",
      "`qwen3:8b`"
    )
  }

  test(
    "the documentation lists every variable of the model provider family and says each is a platform setting"
  ) {
    for setting <- ProviderConfig.Settings do
      says("reference/model-providers.md", s"`ANKKA_MODEL_<NAME>_$setting`")
    says(
      "reference/model-providers.md",
      "Every one of these variables is a platform setting",
      "a process is never given it, and a module's `config` answers it absent"
    )
    says("reference/service-descriptor.md", "| `OPENAI_` | the sidecar |")
    says("reference/configuration.md", "`ANTHROPIC_`, `OPENAI_` or `ANKKA_MODEL_`")
  }

  test(
    "the documentation's limitations say what a model provider at an address of the developer's own does not give"
  ) {
    says(
      "reference/limitations.md",
      "A compatible endpoint never produces a refusal",
      "Cache accounting is what the endpoint reports",
      "is the model's and its chat template's to get right",
      "A model's reasoning is carried within a turn only"
    )
  }
