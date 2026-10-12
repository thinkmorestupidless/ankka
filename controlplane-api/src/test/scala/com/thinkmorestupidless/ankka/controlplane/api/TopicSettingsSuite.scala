package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/**
 * A topic's settings as a member writes them and as Kafka reads them (feature 043): the words each
 * value is parsed from and written as, and `toKafka`, held to
 * `protocol/fixtures/topics/settings.json`, which the operator's rendering is held to as well. The
 * fixture is written here, and refused when the tree's copy differs, unless
 * `-Dankka.fixtures.regenerate=on`.
 */
class TopicSettingsSuite extends munit.FunSuite:

  test("durations are a whole number and a unit, written back in the largest exact unit") {
    assertEquals(RetentionTime.parse("90d"), Right(RetentionTime.Bounded(90L * 86400000L)))
    assertEquals(RetentionTime.parse("500ms"), Right(RetentionTime.Bounded(500L)))
    assertEquals(RetentionTime.parse("everything"), Right(RetentionTime.Everything))
    assertEquals(RetentionTime.Bounded(36L * 3600000L).text, "36h")
    assertEquals(RetentionTime.Bounded(48L * 3600000L).text, "2d")
    assertEquals(RetentionTime.Bounded(1500L).text, "1500ms")
    for text <- Seq("90 days", "1w", "-1", "0d", "d", "") do
      assert(RetentionTime.parse(text).isLeft, text)
    assertEquals(
      RetentionTime.parse("1w"),
      Left("a duration: a whole number and ms, s, m, h or d, or \"everything\"")
    )
  }

  test("sizes are a whole number and a binary unit, or none") {
    assertEquals(RetentionSize.parse("50GiB"), Right(RetentionSize.Bounded(50L << 30)))
    assertEquals(RetentionSize.parse("none"), Right(RetentionSize.NoLimit))
    assertEquals(RetentionSize.Bounded(1536L << 20).text, "1536MiB")
    for text <- Seq("50GB", "50 GiB", "0B", "lots") do
      assert(RetentionSize.parse(text).isLeft, text)
  }

  test("a cleanup policy is delete, compact or both, in either order") {
    assertEquals(CleanupPolicy.parse("delete"), Right(CleanupPolicy.Delete))
    assertEquals(CleanupPolicy.parse("compact"), Right(CleanupPolicy.Compact))
    assertEquals(CleanupPolicy.parse("compact,delete"), Right(CleanupPolicy.CompactDelete))
    assertEquals(CleanupPolicy.parse("delete, compact"), Right(CleanupPolicy.CompactDelete))
    for text <- Seq("Compact", "keep", "") do assert(CleanupPolicy.parse(text).isLeft, text)
  }

  test("every value crosses the wire as its words") {
    given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[TopicSettings] =
      Codecs.make[TopicSettings]
    val settings = TopicSettingsSuite.full
    val json     = writeToString(settings)
    assertEquals(
      json,
      """{"retention":"90d","retentionSize":"50GiB","cleanup":"compact,delete","tombstoneWindow":"1d",""" +
        """"minCompactionLag":"1h","maxCompactionLag":"none","copies":3,"minInSync":2}"""
    )
    assertEquals(readFromString[TopicSettings](json), settings)
  }

  test("a value that is not one of the words is refused naming what it should be") {
    given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[RetentionTime] =
      RetentionTime.codec
    val e = intercept[Exception](readFromString[RetentionTime](""""90 days""""))
    assert(e.getMessage.contains("retention time \"90 days\" is not a duration"), e.getMessage)
  }

  test("toKafka states every key, with -1 for everything and none, and no limit as Long.MaxValue") {
    assertEquals(
      TopicSettingsSuite.full.toKafka,
      Map(
        "retention.ms"          -> (90L * 86400000L).toString,
        "retention.bytes"       -> (50L << 30).toString,
        "cleanup.policy"        -> "compact,delete",
        "delete.retention.ms"   -> "86400000",
        "min.compaction.lag.ms" -> "3600000",
        "max.compaction.lag.ms" -> Long.MaxValue.toString,
        "min.insync.replicas"   -> "2"
      )
    )
    val legacy = TopicSettingsSuite.full.copy(
      retention = RetentionTime.Everything,
      retentionSize = RetentionSize.NoLimit,
      copies = None,
      minInSync = None
    )
    assertEquals(legacy.toKafka("retention.ms"), "-1")
    assertEquals(legacy.toKafka("retention.bytes"), "-1")
    assert(!legacy.toKafka.contains("min.insync.replicas"), "an unstated minimum is the broker's")
  }

  test("compacted is said by the cleanup policy") {
    assert(!TopicSettingsSuite.full.copy(cleanup = CleanupPolicy.Delete).compacted)
    assert(TopicSettingsSuite.full.copy(cleanup = CleanupPolicy.Compact).compacted)
    assert(TopicSettingsSuite.full.compacted)
  }

  test("topics/settings.json is what this suite writes") {
    val expected = TopicSettingsSuite.render
    val path     = TopicSettingsSuite.file
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

object TopicSettingsSuite:

  val full: TopicSettings = TopicSettings(
    retention = RetentionTime.Bounded(90L * 86400000L),
    retentionSize = RetentionSize.Bounded(50L << 30),
    cleanup = CleanupPolicy.CompactDelete,
    tombstoneWindow = TimeSpan(86400000L),
    minCompactionLag = TimeSpan(3600000L),
    maxCompactionLag = CompactionLag.NoLimit,
    copies = Some(3),
    minInSync = Some(2)
  )

  /** The rows every side renders: the settings in words beside Kafka's configuration for them. */
  val rows: Vector[TopicSettings] = Vector(
    full,
    TopicSettingsRules.fill(GivenSettings(), TopicDefaults.Shipped).settings,
    TopicSettingsRules.legacy(compacted = true, TopicDefaults.Shipped).settings,
    full.copy(
      retention = RetentionTime.Everything,
      retentionSize = RetentionSize.NoLimit,
      cleanup = CleanupPolicy.Compact,
      maxCompactionLag = CompactionLag.Bounded(7L * 86400000L)
    )
  )

  def render: String =
    given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[TopicSettings] =
      Codecs.make[TopicSettings]
    val items = rows.map { s =>
      val kafka = s.toKafka.toVector.sortBy(_._1).map((k, v) => s"\"$k\": \"$v\"").mkString(", ")
      s"""  {"settings": ${writeToString(s)}, "kafka": {$kafka}}"""
    }
    items.mkString("[\n", ",\n", "\n]\n")

  def file: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("protocol").resolve("fixtures")))
      .getOrElse(sys.error("no protocol/fixtures above the working directory"))
      .resolve("protocol")
      .resolve("fixtures")
      .resolve("topics")
      .resolve("settings.json")
