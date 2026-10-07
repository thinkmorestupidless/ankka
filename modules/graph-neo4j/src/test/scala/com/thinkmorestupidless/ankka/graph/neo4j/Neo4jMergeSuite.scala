package com.thinkmorestupidless.ankka.graph.neo4j

import com.thinkmorestupidless.ankka.core.graph.GraphDelta
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * The store's statements and version rules, one delta per transaction: what a delta does to a node,
 * an edge and a tombstone, and what a stale one does not.
 */
class Neo4jMergeSuite extends Neo4jSuite with LogCapturing:

  private var current: Option[Neo4jStore] = None

  override def beforeEach(context: BeforeEach): Unit =
    clear()
    current.foreach(_.close())
    current = Some(Neo4jStore(settings))

  override def afterAll(): Unit =
    current.foreach(_.close())
    super.afterAll()

  private def store = current.get

  private def apply(deltas: String*): Unit =
    deltas.foreach(text => store.apply(parse(text)))

  private def parse(text: String): GraphDelta =
    GraphDelta.read(text.getBytes(UTF_8)).fold(why => fail(s"$text: $why"), identity)

  private def node(id: String, v: Long, labels: Seq[String] = Seq("Cart"), props: String = "{}") =
    s"""{"kind":"node","id":"$id","version":$v,"labels":[${labels
        .map(l => s""""$l"""")
        .mkString(",")}],"properties":$props}"""

  private def edge(id: String, v: Long, from: String, to: String, props: String = "{}") =
    s"""{"kind":"edge","id":"$id","version":$v,"type":"LINKS","from":"$from","to":"$to","properties":$props}"""

  private def nodeTomb(id: String, v: Long) =
    s"""{"kind":"tombstone","element":"node","id":"$id","version":$v}"""

  private def edgeTomb(id: String, v: Long, from: String, to: String) =
    s"""{"kind":"tombstone","element":"edge","id":"$id","version":$v,"type":"LINKS","from":"$from","to":"$to"}"""

  test("a node delta creates the node with its labels, properties and version") {
    apply(node("cart:1", 5, Seq("Cart", "Basket"), """{"cartId":"1","items":3}"""))
    assertEquals(labels("cart:1"), Set("Element", "Cart", "Basket"))
    assertEquals(
      props("cart:1"),
      Map[String, AnyRef](
        "id"       -> "cart:1",
        "_version" -> Long.box(5),
        "cartId"   -> "1",
        "items"    -> Long.box(3)
      )
    )
  }

  test("a higher version replaces labels and properties, removing what it no longer carries") {
    apply(node("cart:1", 5, Seq("Cart", "Basket"), """{"cartId":"1","items":3}"""))
    apply(node("cart:1", 6, Seq("Order"), """{"cartId":"1"}"""))
    assertEquals(labels("cart:1"), Set("Element", "Order"))
    assertEquals(
      props("cart:1"),
      Map[String, AnyRef]("id" -> "cart:1", "_version" -> Long.box(6), "cartId" -> "1")
    )
  }

  test("an equal or lower version is stale and changes nothing") {
    apply(node("cart:1", 5, props = """{"v":"five"}"""))
    apply(node("cart:1", 5, props = """{"v":"five again"}"""))
    apply(node("cart:1", 4, props = """{"v":"four"}"""))
    assertEquals(props("cart:1")("v"), "five")
  }

  test("an edge creates placeholder endpoints that the nodes' own deltas replace") {
    apply(edge("e:1", 1, "a", "b", """{"since":2026}"""))
    assertEquals(props("a"), Map[String, AnyRef]("id" -> "a", "_version" -> Long.box(-1)))
    assertEquals(labels("a"), Set("Element"))
    apply(node("a", 1, Seq("Cart"), """{"name":"a"}"""))
    assertEquals(props("a")("name"), "a")
    val edges = query(
      "MATCH (:Element {id:'a'})-[r:LINKS]->(:Element {id:'b'}) RETURN r.id AS id, r.since AS since, r._version AS v"
    )
    assertEquals(
      edges,
      Vector(Map[String, AnyRef]("id" -> "e:1", "since" -> Long.box(2026), "v" -> Long.box(1)))
    )
  }

  test(
    "an edge is merged once however often it arrives, and a higher version replaces its properties"
  ) {
    apply(edge("e:1", 1, "a", "b", """{"w":1}"""))
    apply(edge("e:1", 1, "a", "b", """{"w":1}"""), edge("e:1", 2, "a", "b", """{"w":2}"""))
    val edges = query("MATCH ()-[r:LINKS {id:'e:1'}]->() RETURN r.w AS w")
    assertEquals(edges, Vector(Map[String, AnyRef]("w" -> Long.box(2))))
  }

  test(
    "a tombstone marks the node and clears it; a lower merge after it is stale; a higher one revives it"
  ) {
    apply(node("cart:1", 1, props = """{"cartId":"1"}"""))
    apply(nodeTomb("cart:1", 2))
    assertEquals(props("cart:1")("_deleted"), java.lang.Boolean.TRUE)
    assertEquals(props("cart:1").get("cartId"), None)
    apply(node("cart:1", 2, props = """{"cartId":"stale"}"""))
    assertEquals(props("cart:1").get("cartId"), None)
    assertEquals(props("cart:1")("_deleted"), java.lang.Boolean.TRUE)
    apply(node("cart:1", 3, props = """{"cartId":"1"}"""))
    assertEquals(props("cart:1").get("_deleted"), None)
    assertEquals(props("cart:1")("cartId"), "1")
  }

  test(
    "a tombstone for an element never seen creates it marked, so an older merge cannot revive it"
  ) {
    apply(nodeTomb("ghost", 10))
    apply(node("ghost", 9, props = """{"late":true}"""))
    assertEquals(
      props("ghost"),
      Map[String, AnyRef](
        "id"       -> "ghost",
        "_version" -> Long.box(10),
        "_deleted" -> java.lang.Boolean.TRUE
      )
    )
  }

  test("an edge tombstone marks the edge and clears its properties") {
    apply(edge("e:1", 1, "a", "b", props = """{"since":1843}"""))
    apply(edgeTomb("e:1", 2, "a", "b"))
    assertEquals(
      query("MATCH ()-[r:LINKS {id:'e:1'}]->() RETURN properties(r) AS p").head("p"),
      java.util.Map.of("id", "e:1", "_version", Long.box(2), "_deleted", java.lang.Boolean.TRUE)
    )
  }

  private val bareMarker = Map[String, AnyRef](
    "id"       -> "cart:9",
    "_version" -> Long.box(2),
    "_deleted" -> java.lang.Boolean.TRUE
  )

  test("a tombstone leaves the same bare marker whether it follows the node at once or later") {
    apply(node("cart:9", 1, labels = Seq("Cart"), props = """{"cartId":"9"}"""))
    apply(nodeTomb("cart:9", 2))
    assertEquals(props("cart:9"), bareMarker)
    assertEquals(
      query("MATCH (n:Element {id:'cart:9'}) RETURN labels(n) AS l").head("l"),
      java.util.List.of("Element")
    )
    // A later merge brings it back whole, with nothing of the old state under it.
    apply(node("cart:9", 3, labels = Seq("Cart"), props = """{"cartId":"9","again":true}"""))
    assertEquals(props("cart:9").get("_deleted"), None)
    assertEquals(props("cart:9")("again"), java.lang.Boolean.TRUE)
  }

  test("strings, integers, floats, booleans and arrays round-trip") {
    apply(
      node(
        "n",
        1,
        props = """{"s":"x","i":9007199254740993,"f":1.25,"b":false,"xs":["a","b"],"ns":[1,2]}"""
      )
    )
    val p = props("n")
    assertEquals(p("s"), "x")
    assertEquals(p("i"), Long.box(9007199254740993L))
    assertEquals(p("f"), Double.box(1.25))
    assertEquals(p("b"), java.lang.Boolean.FALSE)
    assertEquals(p("xs").asInstanceOf[java.util.List[AnyRef]].asScala.toList, List("a", "b"))
    assertEquals(
      p("ns").asInstanceOf[java.util.List[AnyRef]].asScala.toList,
      List(Long.box(1), Long.box(2))
    )
  }

  test("opening creates the element_id uniqueness constraint, idempotently") {
    store.open(): Unit
    Neo4jStore(settings).open(): Unit
    val names = query("SHOW CONSTRAINTS YIELD name RETURN name").map(_("name"))
    assert(names.contains("element_id"), names.toString)
  }

  test("a wrong password never opens, and neither the failure nor a log line carries it") {
    val wrong = "not-the-password-4711"
    val appender =
      new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    val logger = org.slf4j.LoggerFactory
      .getLogger(classOf[Neo4jStore])
      .asInstanceOf[ch.qos.logback.classic.Logger]
    logger.addAppender(appender)
    try
      val refused = Neo4jStore(settings.copy(password = wrong))
      val failure = intercept[Neo4jStore.Neo4jRefused](refused.apply(parse(node("n", 1))))
      assert(!failure.getMessage.contains(wrong), failure.getMessage)
      assert(!settings.copy(password = wrong).toString.contains(wrong))
      val lines = appender.list.asScala.map(_.getFormattedMessage)
      assert(!lines.exists(_.contains(wrong)), lines.mkString("\n"))
      assertEquals(query("MATCH (n) RETURN count(n) AS c").head("c"), Long.box(0))
    finally logger.detachAppender(appender): Unit
  }

  test("the server version check accepts 5.26 and later, and calendar versions") {
    assert(Neo4jStore.supported("Neo4j/5.26.0"))
    assert(Neo4jStore.supported("Neo4j/5.27.1"))
    assert(Neo4jStore.supported("Neo4j/2025.01.0"))
    assert(!Neo4jStore.supported("Neo4j/5.24.2"))
    assert(!Neo4jStore.supported("Neo4j/4.4.30"))
    assert(!Neo4jStore.supported("Memgraph"))
  }

  test("a node and an edge with the same id are different elements under different keys") {
    apply(node("same", 1), edge("same", 1, "a", "b"))
    assertEquals(query("MATCH (n:Element {id:'same'}) RETURN count(n) AS c").head("c"), Long.box(1))
    assertEquals(
      query("MATCH ()-[r:LINKS {id:'same'}]->() RETURN count(r) AS c").head("c"),
      Long.box(1)
    )
  }

  test("a record that is not a delta is refused, naming the rule, and nothing is written") {
    val refused = GraphDelta.read("""{"kind":"nod","id":"m","version":1}""".getBytes(UTF_8))
    assert(refused.left.exists(_.contains("nod")), refused.toString)
    assertEquals(query("MATCH (n) RETURN count(n) AS c").head("c"), Long.box(0))
  }
