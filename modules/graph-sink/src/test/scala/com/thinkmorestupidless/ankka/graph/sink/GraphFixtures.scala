package com.thinkmorestupidless.ankka.graph.sink

import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, GraphJson, GraphRules}

/** The contract's fixtures, `protocol/fixtures/graph-deltas/`, built through the SDK's rules. */
object GraphFixtures:

  private def root: java.nio.file.Path =
    Iterator
      .iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => java.nio.file.Files.isDirectory(p.resolve("protocol").resolve("fixtures")))
      .getOrElse(throw IllegalStateException("could not find the repository root"))

  def rows(file: String): Vector[GraphJson] =
    val path = root.resolve("protocol/fixtures/graph-deltas").resolve(file)
    GraphJson.parse(java.nio.file.Files.readAllBytes(path)) match
      case Right(GraphJson.Arr(rows)) => rows
      case other => throw IllegalStateException(s"$file is not a JSON array: $other")

  def build(element: GraphJson, sequenceNumber: Long = 1L): GraphDelta =
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
    val version = element.field("version").collect { case GraphJson.Num(n) => n.toLongExact }
    GraphRules.resolve(Seq((template, version)), sequenceNumber).head

  def delta(json: String): GraphDelta =
    build(GraphJson.parse(json.getBytes("UTF-8")).toOption.get)
