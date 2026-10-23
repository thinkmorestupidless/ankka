package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.core.graph.GraphDelta
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.cart.{LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken.BrokenConsumers
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit as stk
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Java consumers and graph consumers, hosted by the Scala projection runtime under the Scala
 * `AnkkaTestKit` with an in-memory broker, so what they publish can be read back.
 */
class ConsumersSuite extends munit.FunSuite with LogCapturing:

  private val read = JavaServiceBuilder.read(
    Vector(
      classOf[ShoppingCart],
      classOf[TreeNode],
      classOf[CartLines],
      classOf[Placements],
      classOf[TreeGraph]
    ).map(_ -> None),
    Map.empty
  )

  private val broker                = InMemoryBroker()
  private var kit: stk.AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = stk.AnkkaTestKit.start(
      read.descriptors,
      extensions = Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def client = Clients.of(kit.componentClient, read.refs)

  private def eventually[A](what: String)(check: => Option[A]): A =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var last     = check
    while last.isEmpty do
      if System.nanoTime() > deadline then fail(s"never: $what")
      Thread.sleep(100)
      last = check
    last.get

  private def published(topic: String, subject: String) =
    broker.publishedTo(topic).toVector.map(_.message).filter(_.metadata.subject.contains(subject))

  private def text(payload: Array[Byte]) = String(payload, UTF_8)

  test("a consumer publishes each event, keyed by the subject unless it names a key") {
    val cart = client.forEventSourcedEntity("lines-1")
    cart.call(KindRefs.addItem).invoke(LineItem("pen", "Pen", 1))
    cart.call(KindRefs.addItem).invoke(LineItem("ink", "Ink", 1))
    cart.call(KindRefs.removeItem).invoke("ink")
    cart.call(KindRefs.checkout).invoke()
    val lines = eventually("five lines")(
      Some(published("cart-lines", "lines-1")).filter(_.size >= 5)
    )
    assertEquals(
      lines.map(m => m.key -> text(m.payload)),
      Vector(
        Some("lines-1")   -> """{"cartId":"lines-1","what":"added pen"}""",
        Some("lines-1")   -> """{"cartId":"lines-1","what":"added ink"}""",
        Some("ink")       -> """{"cartId":"lines-1","what":"removed ink"}""",
        Some("checkouts") -> """{"cartId":"lines-1","what":"checked out"}""",
        Some("receipts")  -> """{"cartId":"lines-1","what":"thanks"}"""
      )
    )
    assertEquals(lines(4).metadata.get("ce-channel"), Some("email"))
    assertEquals(lines(3).metadata.get("ce-channel"), None)
  }

  test("a consumer reads a topic and calls a component for each message") {
    val _ = broker.publish(
      "placements",
      """{"node":"from-topic","name":"placed","under":""}""".getBytes(UTF_8),
      Metadata.empty.withSubject("from-topic")
    )
    val nodes = eventually("the topic's graph node")(
      Some(published("tree-graph", "from-topic")).filter(_.nonEmpty)
    )
    val delta = GraphDelta.serializer.fromBytes(nodes.head.payload)
    assertEquals(delta.id, "from-topic")
    assertEquals(delta.properties.get("name"), Some("placed"))
  }

  test("a graph consumer publishes nodes and edges, then a tombstone when the source is deleted") {
    val node = client.forKeyValueEntity("g-child")
    node.call(KindRefs.place).invoke(TreeNode.Placed("child", "g-root"))
    val deltas = eventually("a node and an edge")(
      Some(published("tree-graph", "g-child")).filter(_.size >= 2)
    ).map(m => m.key -> GraphDelta.serializer.fromBytes(m.payload))
    assertEquals(deltas.map(_._1), Vector(Some("node:g-child"), Some("edge:g-child-under")))
    val (_, child) = deltas.head
    assertEquals(child.labels, Vector("Node"))
    assertEquals(child.properties.get("tags"), Some(Vector("a", "b")))
    assert(child.version > 0, child.toString)
    val edge = deltas(1)._2
    assertEquals(
      (edge.edgeType, edge.from, edge.to),
      (Some("UNDER"), Some("g-child"), Some("g-root"))
    )

    node.call(KindRefs.remove).invoke(): Unit
    val tombstone = eventually("the tombstone")(
      published("tree-graph", "g-child")
        .map(m => GraphDelta.serializer.fromBytes(m.payload))
        .find(_.isTombstone)
    )
    assertEquals(tombstone.id, "g-child")
  }

  test("a graph element with a property no graph can hold is refused where it is built") {
    val e = intercept[IllegalArgumentException](
      com.thinkmorestupidless.ankka.javasdk.bridge.Effects
        .graphElements()
        .node(
          "x",
          List.empty[String].asJava,
          Map[String, Object]("at" -> java.time.Instant.EPOCH).asJava
        )
    )
    assert(e.getMessage.contains("the property 'at' is a java.time.Instant"), e.getMessage)
  }

  test("a topic's contract, broker and parallel handling reach the runtime, at both ends") {
    val read = JavaServiceBuilder.read(Vector(classOf[Contracted] -> None), Map.empty)
    val consumer = read.descriptors.collect {
      case c: com.thinkmorestupidless.ankka.sdk.ConsumerDescriptor[?, ?, ?] => c
    }.head
    val options = consumer.source match
      case t: com.thinkmorestupidless.ankka.sdk.ChangeSource.Topic[?] => t.options
      case other => fail(s"not a topic: $other")
    assertEquals(options.contract.map(_.name), Some("orders"))
    assertEquals(options.contract.map(_.fingerprint), Some("sha256:0a1b"))
    assertEquals(options.broker, Some("events"))
    assert(options.parallel, "parallel")
    val publication = consumer.produces.get
    assertEquals(publication.contract.map(_.name), Some("order-lines"))
    assertEquals(publication.broker, Some("events"))
  }

  test("a contract is made from its schema, or refused naming why") {
    val made = com.thinkmorestupidless.ankka.javasdk.component.Contract
      .fromSchema("orders", """{"type":"object"}""".getBytes(UTF_8))
    assertEquals(made.name, "orders")
    assert(made.fingerprint.nonEmpty)
    val refused = intercept[IllegalArgumentException](
      com.thinkmorestupidless.ankka.javasdk.component.Contract
        .fromSchema("Orders!", "{}".getBytes(UTF_8))
    )
    assert(refused.getMessage.contains("contract name 'Orders!'"), refused.getMessage)
  }

  test("a consumer that asks for mirrored changes says so to the runtime, and only that one") {
    val mirrored = read.descriptors.collect {
      case c: com.thinkmorestupidless.ankka.sdk.ConsumerDescriptor[?, ?, ?] if c.mirroredChanges =>
        c.componentId.toString
    }
    val cartLines =
      classOf[CartLines]
        .getAnnotation(classOf[com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId])
        .value
    assertEquals(mirrored.toSet, Set(cartLines))
  }

  test("a consumer declared wrongly stops the start, saying what is wrong with each") {
    val e = intercept[ServiceProblems](
      JavaServiceBuilder.read(
        Vector(
          classOf[CartLines],
          classOf[TreeNode],
          classOf[BrokenConsumers.PublishesVoid],
          classOf[BrokenConsumers.NoTopic]
        ).map(_ -> None),
        Map.empty
      )
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("reads ShoppingCart, which the service does not register"), messages)
    assert(messages.contains("its output type is Void, so it has nothing to publish"), messages)
    assert(messages.contains("names no topic to publish to"), messages)
  }
