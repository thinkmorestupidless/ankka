package com.thinkmorestupidless.ankka.core.graph

import java.nio.file.{Files, Path, Paths}

/**
 * The fixtures shared with ankka-flow, whose merge sink reads the same rows:
 * `protocol/fixtures/graph-deltas/` (see `SOURCE.md` there).
 */
private[ankka] object GraphFixtures:

  private def root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("protocol").resolve("fixtures")))
      .getOrElse(throw IllegalStateException("could not find the repository root"))

  def rows(file: String): Vector[GraphJson] =
    val path = root.resolve("protocol/fixtures/graph-deltas").resolve(file)
    GraphJson.parse(Files.readAllBytes(path)) match
      case Right(GraphJson.Arr(rows)) => rows
      case other => throw IllegalStateException(s"$file is not a JSON array: $other")

  def bytes(json: GraphJson): Array[Byte] =
    com.github.plokhotnyuk.jsoniter_scala.core.writeToArray(json)

  def text(json: GraphJson, name: String): String = json.field(name) match
    case Some(GraphJson.Str(value)) => value
    case other                      => throw IllegalStateException(s"no text '$name': $other")

  /**
   * The element a fixture row describes, built the way an author's call builds it, at the version
   * the row states (or the change's sequence number when it states none).
   */
  def build(element: GraphJson, sequenceNumber: Long = 1L): GraphDelta =
    GraphRules.resolve(Seq(describe(element)), sequenceNumber).head

  /** The description and the version it states, unresolved: what a result is made of. */
  def describe(element: GraphJson): (GraphDelta, Option[Long]) =
    def string(name: String): String = element.field(name) match
      case Some(GraphJson.Str(value)) => value
      case _                          => ""
    val properties = element.field("properties") match
      case Some(GraphJson.Obj(fields)) => fields.map((k, v) => k -> GraphRules.plain(v)).toMap
      case _                           => Map.empty[String, Any]
    val labels = element.field("labels") match
      case Some(GraphJson.Arr(items)) => items.map(i => GraphRules.plain(i).toString)
      case _                          => Vector.empty
    val id = string("id")
    val template = (string("kind"), string("element")) match
      case ("node", _) => GraphRules.node(id, labels, properties)
      case ("edge", _) =>
        GraphRules.edge(id, string("type"), string("from"), string("to"), properties)
      case ("tombstone", "node") => GraphRules.tombstoneNode(id)
      case ("tombstone", "edge") =>
        GraphRules.tombstoneEdge(id, string("type"), string("from"), string("to"))
      case other => throw IllegalStateException(s"not an element: $other")
    val version = element.field("version").map {
      // A version a `Long` cannot hold is one the types refuse; say so as the builder would.
      case GraphJson.Num(n) if n.isWhole && n.isValidLong =>
        GraphRules.stated(GraphRules.describe(template), n.toLongExact)
      case other =>
        throw GraphElementRefused("version", s"a version must be a whole number of 64 bits: $other")
    }
    (template, version)
