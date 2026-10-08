package com.thinkmorestupidless.ankka.graph.sink

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, GraphJson}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TopicSources}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** features/graph-deltas/sink.feature, offline: the sink over the in-memory broker and store. */
class GraphSinkSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val Topic             = "cart-deltas"
  private val broker            = InMemoryBroker()
  private val store             = InMemoryGraphStore()
  private var kit: AnkkaTestKit = null

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def start(version: Int): AnkkaTestKit =
    AnkkaTestKit.start(
      Seq(GraphSink(Topic, store, version = version, parallel = false).descriptor),
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

  test("the sink fills a store from a delta topic: every fixture row reads back as the row says") {
    kit = start(version = 1)
    val rows = GraphFixtures.rows("deltas.json")
    rows.foreach(row => publish(GraphFixtures.build(row.field("delta").get)))
    rows.foreach { row =>
      val delta = GraphFixtures.build(row.field("delta").get)
      if !delta.isTombstone && delta.element == GraphDelta.Element.Node then
        val _ = eventually(s"node '${delta.id}' is in the store")(
          store.node(delta.id).filter(_.version >= delta.version)
        )
    }
    rows.foreach { row =>
      val delta = GraphFixtures.build(row.field("delta").get)
      row.field("reads") match
        case Some(GraphJson.Obj(reads))
            if !delta.isTombstone && delta.element == GraphDelta.Element.Node &&
              store.node(delta.id).exists(_.version == delta.version) =>
          val stored = store.node(delta.id).get.properties
          reads.foreach { (name, kind) =>
            val value = stored.getOrElse(name, fail(s"${delta.id}.$name is not in the store"))
            kind match
              case GraphJson.Str("string") =>
                assert(value.isInstanceOf[String], s"${delta.id}.$name")
              case GraphJson.Str("integer") =>
                assert(value.isInstanceOf[Long], s"${delta.id}.$name: $value")
              case GraphJson.Str("float") =>
                assert(value.isInstanceOf[Double], s"${delta.id}.$name")
              case GraphJson.Str("boolean") =>
                assert(value.isInstanceOf[Boolean], s"${delta.id}.$name")
              case GraphJson.Str(list) if list.endsWith("s") =>
                assert(value.isInstanceOf[Seq[?]], s"${delta.id}.$name as $list")
              case _ => ()
          }
        case _ => ()
    }
  }

  test("the sink applies a delta only when its version is newer") {
    val older = GraphFixtures.delta(
      """{"kind":"node","id":"cart:newer","version":3,"labels":["Cart"],"properties":{"items":1}}"""
    )
    val newer = GraphFixtures.delta(
      """{"kind":"node","id":"cart:newer","version":5,"labels":["Cart"],"properties":{"items":5}}"""
    )
    publish(newer)
    val _ = eventually("the newer delta lands")(store.node("cart:newer").filter(_.version == 5L))
    publish(older)
    Thread.sleep(500)
    assertEquals(store.node("cart:newer").get.properties("items"), 5L)
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
    broker.redeliver(Topic): Unit
    Thread.sleep(300)
    assertEquals(store.node("bad"), None)
    broker.skipFailed(Topic)
  }

  test("the sink at a higher version builds the store again from the topic") {
    val before = store.elements
    kit.stop()
    store.clear()
    assertEquals(store.elements, Map.empty)
    kit = start(version = 2)
    val _ = eventually("the store is filled again")(Option.when(store.elements == before)(()))
  }
