package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.providers.{ModelProtocol, Presets}

import java.nio.file.{Files, Path, Paths}

/**
 * The documentation's preset table is generated from the presets themselves.
 *
 * `docs/reference/model-providers.md` holds the table between two generated-block comments. This
 * suite renders it from `Presets.all` and fails when the page differs, so a preset added or changed
 * is a failing build until the page carries it. Run with `-Dankka.docs.update=true` to rewrite it:
 *
 * {{{
 * sbt -Dankka.docs.update=true 'agent/testOnly *ModelPresetsReferenceSuite'
 * }}}
 */
class ModelPresetsReferenceSuite extends munit.FunSuite:

  private val Page = "docs/reference/model-providers.md"
  private val Name = "model-presets"

  test("the model providers page lists every preset, as the presets describe themselves") {
    val path    = ModelPresetsReferenceSuite.repoRoot.resolve(Page)
    val current = Files.readString(path)
    val expected =
      ModelPresetsReferenceSuite.replaceBlock(current, Name, ModelPresetsReferenceSuite.render())
    if expected != current then
      if sys.props.get("ankka.docs.update").contains("true") then
        Files.writeString(path, expected): Unit
      else
        fail(
          s"$Page is stale: a preset changed. Run " +
            "sbt -Dankka.docs.update=true 'agent/testOnly *ModelPresetsReferenceSuite' and commit the page."
        )
  }

object ModelPresetsReferenceSuite:

  def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve("mkdocs.yml")))
      .getOrElse(throw IllegalStateException("could not find the repository root"))

  def render(): String =
    val header =
      "| Preset | Protocol | Shape | Address | Key | Default model | Reasoning efforts | Temperature | Tool call ids | Notes |\n" +
        "|---|---|---|---|---|---|---|---|---|---|\n"
    val rows = Presets.names.map(Presets.all).map { p =>
      val shape = if p.protocol == ModelProtocol.Anthropic then "—" else p.shape.wireName
      val address = p.address
        .map(a => s"`$a`")
        .getOrElse(
          if p.name == Presets.Installation then "the platform's" else "required"
        )
      val key   = if p.keyRequired then "required" else "none"
      val model = p.defaultModel.map(m => s"`$m`").getOrElse("required")
      val efforts =
        if p.protocol == ModelProtocol.Anthropic then
          if p.quirks.sendsThinking then "adaptive thinking and effort" else "—"
        else p.quirks.reasoningEfforts.map(_.mkString(", ")).getOrElse("as asked")
      val temperature = if p.quirks.acceptsTemperature then "sent" else "withheld"
      val ids = p.quirks.toolCallIds
        .map { case com.thinkmorestupidless.ankka.agent.providers.IdRule.Alphanumeric(n) =>
          s"rewritten to $n letters and digits"
        }
        .getOrElse("as given")
      s"| `${p.name}` | ${p.protocol.wireName} | $shape | $address | $key | $model | $efforts | $temperature | $ids | ${p.notes} |"
    }
    header + rows.mkString("\n") + "\n"

  def replaceBlock(page: String, name: String, content: String): String =
    val open  = s"<!-- generated:start $name -->\n"
    val close = s"<!-- generated:end $name -->"
    val from  = page.indexOf(open)
    val to    = page.indexOf(close)
    if from < 0 || to < from then
      throw IllegalStateException(s"no generated block named '$name' in the page")
    page.substring(0, from + open.length) + content + page.substring(to)
