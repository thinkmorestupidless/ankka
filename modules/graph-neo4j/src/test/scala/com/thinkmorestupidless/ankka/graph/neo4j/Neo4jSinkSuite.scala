package com.thinkmorestupidless.ankka.graph.neo4j

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, GraphJson, GraphRules}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TopicSources}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * features/graph-deltas/sink.feature, offline: the sink as a consumer in a service over the
 * in-memory broker, filling a Neo4j in a container.
 */
class Neo4jSinkSuite extends Neo4jSuite with LogCapturing:

  private val Topic             = "cart-deltas"
  private val broker            = InMemoryBroker()
  private var kit: AnkkaTestKit = null

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  private def start(version: Int): AnkkaTestKit =
    AnkkaTestKit.start(
      Seq(Neo4jSink(Topic, settings, version = version, parallel = false).descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  private def publish(delta: GraphDelta): Unit =
    broker.publish(
      Topic,
      Some(delta.key),
      GraphDelta.serializer.toBytes(delta),
      Metadata.empty.withSubject(delta.id).set(Metadata.CeType, GraphDelta.SchemaName)
    ): Unit

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def count(cypher: String): Long = query(cypher).head("c").asInstanceOf[java.lang.Long]

  test("the sink fills a store from a delta topic: every fixture row reads back as the row says") {
    clear()
    kit = start(version = 1)
    val rows = GraphFixtures.rows("deltas.json")
    rows.foreach(row => publish(GraphFixtures.build(row.field("delta").get)))
    // Every node row lands at its version, or is superseded by a later row of the same element.
    rows.foreach { row =>
      val delta = GraphFixtures.build(row.field("delta").get)
      if !delta.isTombstone && delta.element == GraphDelta.Element.Node then
        val _ = eventually(s"node '${delta.id}' is in the store") {
          query(
            "MATCH (n:Element {id: $id}) RETURN n._version AS v",
            Map("id" -> delta.id)
          ).headOption
            .filter(_("v").asInstanceOf[java.lang.Long].longValue >= delta.version)
        }
    }
    // Each property is the kind the fixture says it reads as, where the row's own delta is the
    // one the store holds.
    rows.foreach { row =>
      val delta = GraphFixtures.build(row.field("delta").get)
      row.field("reads") match
        case Some(GraphJson.Obj(reads))
            if !delta.isTombstone && delta.element == GraphDelta.Element.Node &&
              props(delta.id).get("_version").contains(Long.box(delta.version)) =>
          val stored = props(delta.id)
          reads.foreach { (name, kind) =>
            val value =
              stored.getOrElse(name, fail(s"${delta.id}.$name is not in the store: $stored"))
            kind match
              case GraphJson.Str("string") =>
                assert(value.isInstanceOf[String], s"${delta.id}.$name")
              case GraphJson.Str("integer") =>
                assert(value.isInstanceOf[java.lang.Long], s"${delta.id}.$name: $value")
              case GraphJson.Str("float") =>
                assert(value.isInstanceOf[java.lang.Double], s"${delta.id}.$name")
              case GraphJson.Str("boolean") =>
                assert(value.isInstanceOf[java.lang.Boolean], s"${delta.id}.$name")
              case GraphJson.Str(list) if list.endsWith("s") =>
                assert(value.isInstanceOf[java.util.List[?]], s"${delta.id}.$name as $list")
              case _ => ()
          }
        case _ => ()
    }
  }

  test("the sink applies a delta only when its version is newer") {
    val older = GraphFixtures.build(
      GraphJson
        .parse(
          """{"kind":"node","id":"cart:newer","version":3,"labels":["Cart"],"properties":{"items":1}}"""
            .getBytes(UTF_8)
        )
        .toOption
        .get
    )
    val newer = GraphFixtures.build(
      GraphJson
        .parse(
          """{"kind":"node","id":"cart:newer","version":5,"labels":["Cart"],"properties":{"items":5}}"""
            .getBytes(UTF_8)
        )
        .toOption
        .get
    )
    publish(newer)
    val _ = eventually("the newer delta lands")(
      Option.when(
        query("MATCH (n:Element {id:'cart:newer'}) RETURN n._version AS v").headOption.exists(
          _("v") == Long.box(5)
        )
      )(())
    )
    publish(older)
    Thread.sleep(1000)
    assertEquals(props("cart:newer")("items"), Long.box(5))
  }

  test("the sink refuses a delta that breaks the rules, says which, and is handed it again") {
    broker.publish(
      Topic,
      Some("node:bad"),
      """{"kind":"nod","id":"bad","version":1}""".getBytes(UTF_8),
      Metadata.empty.withSubject("bad")
    ): Unit
    val status = eventually("the sink reports what it is failing on") {
      TopicSources(kit.service.system).all.find(_.componentId == "graph-sink").flatMap(_.failing)
    }
    assert(status.contains("delta refused for 'bad'"), status)
    assert(status.contains("nod"), status)
    // Handed to the sink again: the next publication delivers it once more, still refused.
    broker.redeliver(Topic): Unit
    Thread.sleep(500)
    assertEquals(count("MATCH (n:Element {id:'bad'}) RETURN count(n) AS c"), 0L)
    broker.skipFailed(Topic)
  }

  test("the sink at a higher version builds the store again from the topic") {
    val fixtures = GraphFixtures.rows("deltas.json").size
    kit.stop()
    clear()
    assertEquals(count("MATCH (n) RETURN count(n) AS c"), 0L)
    kit = start(version = 2)
    val _ = eventually("the store is filled again")(
      Option.when(
        count("MATCH (n:Element) RETURN count(n) AS c") > 0 && count(
          "MATCH (n:Element) WHERE n._version >= 0 RETURN count(n) AS c"
        ) >= (fixtures / 2).toLong
      )(())
    )
  }

/**
 * The fixture rows, built through the core builder as every SDK builds them: the sink's suite reads
 * the same rows the SDKs are held to, which is what makes them proof that a graph consumer writes
 * what the sink reads.
 */
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
