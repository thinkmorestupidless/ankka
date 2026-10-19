package com.thinkmorestupidless.ankka.sdk

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

/**
 * The storage client's rules, held to `protocol/fixtures/storage/rules.json`, which every SDK's
 * client and test double reads too. A row added to the fixture is a case added here.
 */
class ObjectRulesSuite extends munit.FunSuite:

  private final case class Row(
      unit: String,
      repeat: Int,
      accepted: Boolean,
      why: String,
      says: Option[String] = None
  ):
    def text: String = unit * repeat
  private final case class Signed(maxSeconds: Long, defaultSeconds: Long)
  private final case class Rules(
      objectNames: Vector[Row],
      prefixes: Vector[Row],
      bucketNames: Vector[Row],
      signedUrl: Signed
  )
  private given JsonValueCodec[Rules] = JsonCodecMaker.make

  private lazy val rules: Rules =
    val file = Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/fixtures/storage/rules.json"))
      .find(Files.isRegularFile(_))
      .getOrElse(fail("could not find protocol/fixtures/storage/rules.json"))
    readFromString[Rules](Files.readString(file))

  private def verdicts(rows: Vector[Row], problem: String => Option[String]): Unit =
    assert(rows.nonEmpty)
    rows.foreach { row =>
      val found = problem(row.text)
      assertEquals(found.isEmpty, row.accepted, s"${row.why}: $found")
      row.says.foreach(says =>
        assert(found.exists(_.contains(says)), s"${row.why}: '$found' should say '$says'")
      )
      if !row.accepted then assert(row.says.nonEmpty, s"${row.why}: a refused row names its words")
    }

  test("every object name in the fixture gets its verdict and its words") {
    verdicts(rules.objectNames, ObjectRules.nameProblem)
  }

  test("every prefix in the fixture gets its verdict and its words") {
    verdicts(rules.prefixes, ObjectRules.prefixProblem)
  }

  test("every bucket name in the fixture gets its verdict and its words") {
    verdicts(rules.bucketNames, ObjectRules.bucketNameProblem)
  }

  test("a signed URL's bound and default are the fixture's") {
    assertEquals(ObjectRules.MaxSignedUrl.toSeconds, rules.signedUrl.maxSeconds)
    assertEquals(ObjectRules.DefaultSignedUrl.toSeconds, rules.signedUrl.defaultSeconds)
    assert(ObjectRules.spanProblem(7.days).isEmpty)
    assert(ObjectRules.spanProblem(7.days + 1.second).exists(_.contains("at most 7 days")))
    assert(ObjectRules.spanProblem(Duration.Zero).nonEmpty)
  }

  test("a refusal is BadRequest and shows the name without its control characters") {
    val error = intercept[CommandError](ObjectRules.checkName("a\nb"))
    assertEquals(error.code, ErrorCode.BadRequest)
    assert(error.getMessage.contains("\"a?b\""), error.getMessage)
  }

  test("a client with no store refuses every bucket, naming provisionObjectStorage") {
    val error = intercept[CommandError](ObjectStorage.unavailable.default)
    assertEquals(error.code, ErrorCode.Unavailable)
    assert(error.getMessage.contains("provisionObjectStorage"), error.getMessage)
    val named = intercept[CommandError](ObjectStorage.unavailable.bucket("photos"))
    assertEquals(named.code, ErrorCode.Unavailable)
    assert(named.getMessage.contains("names none"), named.getMessage)
    assertEquals(ObjectStorage.unavailable.buckets, Vector.empty)
  }

  test("an unknown bucket's refusal lists the names the descriptor gives") {
    val error = StorageRefusals.unknown("archive", Vector("photos", "exports"))
    assertEquals(
      error.getMessage,
      "this service has no bucket named 'archive'; its descriptor names 'exports', 'photos'"
    )
  }

  test("an exposure is read from its word and written back") {
    BucketExposure.values.foreach(e => assertEquals(BucketExposure.fromWord(e.word), Some(e)))
    assertEquals(BucketExposure.fromWord("visible"), None)
  }
