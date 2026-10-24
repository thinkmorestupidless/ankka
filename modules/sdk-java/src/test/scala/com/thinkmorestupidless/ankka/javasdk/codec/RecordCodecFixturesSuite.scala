package com.thinkmorestupidless.ankka.javasdk.codec

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.NullNode

import java.lang.reflect.Type
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import java.util.{Base64, Optional}
import scala.jdk.CollectionConverters.*

/**
 * Every payload fixture in `protocol/fixtures` decodes with the Java SDK's own codec to its stated
 * value, and re-encodes to the same bytes (SC-001). A fixture with no Java shape fails, as it does
 * for every other SDK; the subdirectories hold other features' rules and are read by their suites.
 */
class RecordCodecFixturesSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()

  private val dir: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .take(5)
      .find(p => Files.isDirectory(p.resolve("protocol")))
      .map(_.resolve("protocol").resolve("fixtures"))
      .getOrElse(fail("could not find protocol/fixtures from " + Paths.get("").toAbsolutePath))

  /** The Java shape for each JSON manifest, and the type for each text and binary one. */
  private val types: Map[String, Type] = Map(
    "shopping-cart"       -> classOf[Shapes.ShoppingCart],
    "shopping-cart-event" -> classOf[Shapes.ShoppingCartEvent],
    "plan"                -> classOf[Shapes.Plan],
    "service"             -> classOf[Shapes.Service],
    "service-event"       -> classOf[Shapes.ServiceEvent],
    "status"              -> classOf[Shapes.Status],
    "numbers"             -> classOf[Shapes.Numbers],
    "tree"                -> classOf[Shapes.Tree],
    "string"              -> classOf[String],
    "int"                 -> classOf[java.lang.Integer],
    "long"                -> classOf[java.lang.Long],
    "double"              -> classOf[java.lang.Double],
    "boolean"             -> classOf[java.lang.Boolean],
    "duration-millis"     -> classOf[Duration],
    "done"                -> classOf[com.thinkmorestupidless.ankka.javasdk.Done],
    "unit"                -> classOf[Void],
    "bytes"               -> classOf[Array[Byte]],
    "option[int]"         -> Holder.optionalInt
  )

  private val files: List[Path] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".json"))
      .toList
      .sortBy(_.toString)

  test("the fixtures are there") {
    assert(files.nonEmpty, s"no fixtures under $dir")
  }

  files.foreach { path =>
    test(path.getFileName.toString) {
      val fixture  = mapper.readTree(path.toFile)
      val manifest = fixture.get("manifest").asText
      val bytes    = Base64.getDecoder.decode(fixture.get("bytes_base64").asText)
      val tpe      = types.getOrElse(manifest, fail(s"no Java shape for manifest $manifest"))
      val codec    = RecordCodec.forType(tpe).asInstanceOf[Serializer[Any]]
      assertEquals(codec.manifest, manifest)
      assertEquals(codec.contentType.mediaType, fixture.get("content_type").asText)
      val decoded = codec.fromBytes(bytes)
      // As JSON text: an int and a long node holding 1500 are the same value here.
      assertEquals(neutral(decoded, codec).toString, fixture.get("value").toString)
      assertEquals(String(codec.toBytes(decoded), UTF_8), String(bytes, UTF_8))
      assert(java.util.Arrays.equals(codec.toBytes(decoded), bytes), "the bytes differ")
    }
  }

  private def neutral(value: Any, codec: Serializer[Any]): JsonNode =
    codec.contentType match
      case ContentType.JSON => mapper.readTree(codec.toBytes(value))
      case _                =>
        val plain: Any = value match
          case null                                          => null
          case _: com.thinkmorestupidless.ankka.javasdk.Done => null
          case b: Array[Byte]                                => Base64.getEncoder.encodeToString(b)
          case d: Duration                                   => d.toMillis
          case o: Optional[?]                                => if o.isPresent then o.get else null
          case other                                         => other
        if plain == null then NullNode.instance else mapper.valueToTree[JsonNode](plain)
