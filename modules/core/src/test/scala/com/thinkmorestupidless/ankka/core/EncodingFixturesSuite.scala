package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Generates `protocol/fixtures/<name>.json` from the platform's own codecs, and fails when a
 * regenerated file differs from the committed one.
 *
 * The fixtures are how every SDK's default codec is held to `protocol/ENCODING.md`; this suite is
 * how `ENCODING.md` is held to the codecs. A change to `Codecs.make`'s configuration or to
 * `Serializers` shows up here as a diff, which is the moment to decide whether it is a major of the
 * protocol.
 *
 * The shapes are copies of the samples' and the control plane's domain types (`EncodingShapes`):
 * `core` depends on nothing else, and the point is the *shape* the codec sees, not the class.
 */
class EncodingFixturesSuite extends munit.FunSuite:

  import EncodingShapes.*

  private val dir: Path =
    // Run from the module directory under sbt's forked JVM; the repository root is two up.
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .take(5)
      .find(p => Files.isDirectory(p.resolve("protocol")))
      .map(_.resolve("protocol").resolve("fixtures"))
      .getOrElse(fail("could not find protocol/fixtures from " + Paths.get("").toAbsolutePath))

  /**
   * `value` is the decoded value in language-neutral JSON, as ENCODING.md's "Fixtures" describes.
   */
  private def fixture(
      name: String,
      manifest: String,
      contentType: String,
      bytes: Array[Byte],
      value: String
  ): (String, String) =
    val b64 = Base64.getEncoder.encodeToString(bytes)
    name -> s"""{
  "manifest": "$manifest",
  "content_type": "$contentType",
  "bytes_base64": "$b64",
  "value": $value
}
"""

  private def json[A](name: String, manifest: String, value: A)(using
      codec: JsonValueCodec[A]
  ): (String, String) =
    val bytes = writeToArray(value)
    fixture(name, manifest, "application/json", bytes, String(bytes, UTF_8))

  private def text[A](name: String, value: A, jsonValue: String)(using
      s: Serializer[A]
  ): (String, String) =
    fixture(name, s.manifest, "text/plain", s.toBytes(value), jsonValue)

  private def binary[A](name: String, value: A, jsonValue: String)(using
      s: Serializer[A]
  ): (String, String) =
    fixture(name, s.manifest, "application/octet-stream", s.toBytes(value), jsonValue)

  private def fixtures: Vector[(String, String)] =
    given JsonValueCodec[ShoppingCart]      = Codecs.make
    given JsonValueCodec[ShoppingCartEvent] = Codecs.make
    given JsonValueCodec[Plan]              = Codecs.make
    given JsonValueCodec[Service]           = Codecs.make
    given JsonValueCodec[ServiceEvent]      = Codecs.make
    given JsonValueCodec[Numbers]           = Codecs.make
    given JsonValueCodec[Status]            = Codecs.make
    given JsonValueCodec[Tree]              = Codecs.make
    import Serializers.given

    val pen  = LineItem("p1", "Pen", 2)
    val ink  = LineItem("p2", "Ink", 1)
    val when = Instant.parse("2026-09-23T10:00:00Z")

    Vector(
      // The shopping cart: a record with a collection, and its sum-typed events.
      json(
        "record-empty-collection",
        "shopping-cart",
        ShoppingCart(Vector.empty, checkedOut = false)
      ),
      json(
        "record-with-collection",
        "shopping-cart",
        ShoppingCart(Vector(pen, ink), checkedOut = true)
      ),
      json("sum-type-with-fields", "shopping-cart-event", ShoppingCartEvent.ItemAdded(pen)),
      json("sum-type-string-field", "shopping-cart-event", ShoppingCartEvent.ItemRemoved("p1")),
      json("sum-type-fieldless", "shopping-cart-event", ShoppingCartEvent.CheckedOut),
      // The planner: nested records and a map.
      json(
        "record-nested-and-map",
        "plan",
        Plan("weekend", Vector(Step("pack", 1), Step("go", 2)), Map("tier" -> "gold"))
      ),
      // The control plane: instants, optional fields present and absent, a fieldless enum as a field.
      json(
        "record-instants-and-options",
        "service",
        Service("cart", when, Some("dev@example.test"), None, Status.Ready)
      ),
      json("sum-type-with-instant", "service-event", ServiceEvent.Applied(3L, Some(when))),
      json("sum-type-optional-absent", "service-event", ServiceEvent.Applied(4L, None)),
      json("enum-as-field", "status", Status.Failed),
      // Numbers: a long beyond 2^53, doubles that need the shortest round-trip repr, a negative.
      json("numbers", "numbers", Numbers(9007199254740993L, 1.5, 1.0e10, 0.1, -7)),
      // A recursive type.
      json("recursive", "tree", Tree("root", Vector(Tree("leaf", Vector.empty)))),
      // Top-level primitives, as Serializers write them.
      text("text-string", "hello, world", "\"hello, world\""),
      text("text-int", 42, "42"),
      text("text-long", 9007199254740993L, "9007199254740993"),
      text("text-double", 1.5, "1.5"),
      text("text-boolean", true, "true"),
      text("text-duration-millis", 1500.millis, "1500"),
      binary[Done]("binary-done", Done, "null"),
      binary("binary-unit", (), "null"),
      binary("binary-bytes", Array[Byte](1, 2, 3), "\"AQID\""),
      binary("binary-option-none", Option.empty[Int], "null"),
      binary("binary-option-some", Option(42), "42")
    )

  test("protocol/fixtures matches what the codecs produce") {
    Files.createDirectories(dir)
    val generated = fixtures.toMap
    val existing =
      Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".json")).toVector
    val stale =
      existing.map(_.getFileName.toString.stripSuffix(".json")).filterNot(generated.contains)
    val diffs = generated.toVector.flatMap { (name, content) =>
      val path = dir.resolve(s"$name.json")
      if !Files.exists(path) then
        Files.writeString(path, content)
        Some(s"$name.json was missing and has been written; commit it")
      else if Files.readString(path) != content then
        Files.writeString(path, content)
        Some(s"$name.json differed from the codecs' output and has been rewritten; review the diff")
      else None
    }
    assert(
      diffs.isEmpty && stale.isEmpty,
      (diffs ++ stale.map(s => s"$s.json is no longer generated; delete it")).mkString("\n")
    )
  }

/** Copies of the shapes the platform persists; see the suite's note on why they are copies. */
object EncodingShapes:
  final case class LineItem(productId: String, name: String, quantity: Int)
  final case class ShoppingCart(items: Vector[LineItem], checkedOut: Boolean)

  enum ShoppingCartEvent:
    case ItemAdded(item: LineItem)
    case ItemRemoved(productId: String)
    case CheckedOut

  final case class Step(name: String, order: Int)
  final case class Plan(title: String, steps: Vector[Step], labels: Map[String, String])

  enum Status:
    case Ready
    case Failed

  final case class Service(
      name: String,
      createdAt: Instant,
      owner: Option[String],
      note: Option[String],
      status: Status
  )

  enum ServiceEvent:
    case Applied(generation: Long, at: Option[Instant])

  final case class Numbers(
      big: Long,
      half: Double,
      tenBillion: Double,
      tenth: Double,
      negative: Int
  )

  final case class Tree(label: String, children: Vector[Tree])
