package com.thinkmorestupidless.ankka.sdk

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.nio.file.{Files, Paths}

/**
 * The rules for a service secret's name and value, held to `protocol/fixtures/secrets/rules.json`,
 * which every SDK's store and test double reads too.
 */
class SecretRulesSuite extends munit.FunSuite:

  private final case class Row(unit: String, repeat: Int, accepted: Boolean, why: String):
    def text: String = unit * repeat
  private final case class Rules(names: Vector[Row], values: Vector[Row])
  private given JsonValueCodec[Rules] = JsonCodecMaker.make

  private lazy val rules: Rules =
    val file = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/fixtures/secrets/rules.json"))
      .find(Files.isRegularFile(_))
      .getOrElse(fail("could not find protocol/fixtures/secrets/rules.json"))
    readFromString[Rules](Files.readString(file))

  test("every name in the fixture gets its verdict") {
    assert(rules.names.nonEmpty)
    rules.names.foreach { row =>
      assertEquals(SecretRules.nameProblem(row.text).isEmpty, row.accepted, row.why)
    }
  }

  test("every value in the fixture gets its verdict") {
    assert(rules.values.nonEmpty)
    rules.values.foreach { row =>
      assertEquals(SecretRules.valueProblem(row.text).isEmpty, row.accepted, row.why)
    }
  }

  test("a refused name is told the rule") {
    val problem = SecretRules.nameProblem("provider acme").get
    assert(problem.contains("'.', '_', '-' or '/'"), problem)
    assert(problem.contains("253"), problem)
  }

  test("a refused value is told the limit and never quoted") {
    val value   = "x" * 65537
    val problem = SecretRules.valueProblem(value).get
    assert(problem.contains("65536"), problem)
    assert(!problem.contains("xxxx"), problem)
  }

  test("check refuses with BadRequest") {
    val error = intercept[CommandError](SecretRules.check("acme", ""))
    assertEquals(error.code, ErrorCode.BadRequest)
    val badName = intercept[CommandError](SecretRules.check("a:b"))
    assertEquals(badName.code, ErrorCode.BadRequest)
    SecretRules.check("provider/acme", "sk-1")
  }
