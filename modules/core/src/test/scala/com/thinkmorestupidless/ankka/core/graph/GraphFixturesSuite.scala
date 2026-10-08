package com.thinkmorestupidless.ankka.core.graph

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, WriterConfig}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * `protocol/fixtures/graph-deltas/keys.json` and `deltas.json` are ankka's own (feature 037): each
 * row's element, built through the builder as an author's call builds it, with the key and the
 * delta the builder produces. Under `-Dankka.fixtures.regenerate=on` the files are written from
 * their own elements through the builder; otherwise a row whose recorded key or delta is not what
 * the builder produces is refused, so the files cannot drift from the builder that every SDK and
 * the sink are held to. `refused.json` is authored by hand and only read.
 */
class GraphFixturesSuite extends munit.FunSuite:

  import GraphFixtures.*

  private val regenerate = sys.props.get("ankka.fixtures.regenerate").contains("on")

  for file <- Seq("keys.json", "deltas.json") do
    test(s"$file is what the builder writes for its elements") {
      val recorded = rows(file)
      val built = recorded.map { row =>
        val element = row.field("delta").getOrElse(fail(s"$file row has no delta"))
        val delta   = build(element)
        val text    = new String(GraphDelta.serializer.toBytes(delta), UTF_8)
        val written = GraphJson.parse(text.getBytes(UTF_8)).toOption.get
        val fields = Vector("delta" -> written, "key" -> GraphJson.Str(delta.key)) ++
          row.field("reads").map("reads" -> _).toVector
        GraphJson.Obj(fields)
      }
      if regenerate then Files.writeString(fixturePath(file), render(built)): Unit
      recorded.zip(built).zipWithIndex.foreach { case ((row, generated), index) =>
        assertEquals(
          normalised(row.field("key").get),
          normalised(generated.field("key").get),
          s"$file row $index: the recorded key is not the one the builder writes"
        )
        assertEquals(
          normalised(row.field("delta").get),
          normalised(generated.field("delta").get),
          s"$file row $index: the recorded delta is not what the builder writes; regenerate on purpose, or fix the change"
        )
      }
    }

  /**
   * As the reader has it: field order and number spelling aside, `2.0` and `2` are one value, and
   * `labels` and `properties` that are absent equal ones that are empty.
   */
  private def normalised(json: GraphJson): GraphJson = json match
    case GraphJson.Obj(fields) =>
      val kept = fields.filterNot {
        case ("labels", GraphJson.Arr(items))      => items.isEmpty
        case ("properties", GraphJson.Obj(fields)) => fields.isEmpty
        case _                                     => false
      }
      GraphJson.Obj(kept.map((k, v) => k -> normalised(v)).sortBy(_._1))
    case GraphJson.Arr(items) => GraphJson.Arr(items.map(normalised))
    case GraphJson.Num(n)     => GraphJson.Num(n.bigDecimal.stripTrailingZeros())
    case other                => other

  private def render(rows: Vector[GraphJson]): String =
    val config = WriterConfig.withIndentionStep(2)
    writeToString(GraphJson.Arr(rows), config)(using GraphJson.codec) + "\n"

  private def fixturePath(file: String) =
    Iterator
      .iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("protocol").resolve("fixtures")))
      .map(_.resolve("protocol/fixtures/graph-deltas").resolve(file))
      .getOrElse(throw IllegalStateException("could not find the repository root"))
