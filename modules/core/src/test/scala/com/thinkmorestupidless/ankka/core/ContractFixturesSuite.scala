package com.thinkmorestupidless.ankka.core

import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/**
 * `protocol/fixtures/contracts/fingerprints.json`: rows of `{"name", "schema", "fingerprint"}`,
 * written here and refused when the tree's copy differs, unless `-Dankka.fixtures.regenerate=on`.
 * Every SDK fingerprints each row's schema and must produce the row's fingerprint, which is what
 * makes "the same document fingerprints the same in every language" a fact rather than a hope.
 */
class ContractFixturesSuite extends munit.FunSuite:

  private val rows: Vector[(String, String)] = Vector(
    "empty.v1" -> "{}",
    "order.v1" -> """{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "required": ["id", "total"], "properties": {"total": {"type": "number"}, "id": {"type": "string"}}}""",
    "nested.v1" -> """{"type":"object","properties":{"lines":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"},"qty":{"type":"integer","minimum":1}}}}}}""",
    "numbers.v1" -> """{"examples": [0, -0, 1, -1.5, 4.50, 1e21, 1E30, 2e-3, 1e-7, 123456789012345680000, 333333333.33333329]}""",
    "unicode.v1" -> """{"title": "Händler €", "ä": 1, "a": 2, "Z": 3, "control": "\u0001\t\n\"\\"}""",
    "deep.v1" -> """{"a":{"b":{"c":{"d":{"e":[[[]],{}]}}}}}"""
  )

  test("fingerprints.json is what this suite writes") {
    val expected = render(rows)
    val path     = ContractFixturesSuite.file
    if sys.props.get("ankka.fixtures.regenerate").contains("on") then
      Files.createDirectories(path.getParent)
      Files.write(path, expected.getBytes(UTF_8)): Unit
    assert(Files.exists(path), s"$path is missing: run with -Dankka.fixtures.regenerate=on")
    assertEquals(
      new String(Files.readAllBytes(path), UTF_8),
      expected,
      s"$path differs: regenerate it on purpose, or fix the change"
    )
  }

  test("every row's schema is canonical and its fingerprint is of that form") {
    rows.foreach { (name, schema) =>
      val contract = Contract.fromSchema(name, schema.getBytes(UTF_8)).toOption.get
      assert(contract.fingerprint.matches("sha256:[0-9a-f]{64}"))
    }
  }

  private def render(rows: Vector[(String, String)]): String =
    val items = rows.map { (name, schema) =>
      val json      = GraphJson.parse(schema.getBytes(UTF_8)).toOption.get
      val canonical = Contract.Canonical.render(json)
      val contract  = Contract.fromSchema(name, schema.getBytes(UTF_8)).toOption.get
      s"""  {"name": "$name", "schema": $canonical, "fingerprint": "${contract.fingerprint}"}"""
    }
    items.mkString("[\n", ",\n", "\n]\n")

object ContractFixturesSuite:
  def file: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("protocol").resolve("fixtures")))
      .map(_.resolve("protocol/fixtures/contracts/fingerprints.json"))
      .getOrElse(throw IllegalStateException("could not find the repository root"))
