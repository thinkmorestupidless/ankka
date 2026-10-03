package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, Metadata}
import com.thinkmorestupidless.ankka.runtime.{
  ConsumerGroups,
  IncomingMessage,
  KafkaPublisher,
  KafkaSubscriber,
  MessagePublisher,
  MessageSubscriber,
  ProjectionRuntime,
  ServiceIdentity,
  TopicSubscription
}
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.pekko.Done
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The real Kafka wire.
 *
 * `TopicSourceSuite` already covers dispatch, decoding and view writes over an in-memory broker;
 * what only a broker can verify is that CloudEvents attributes survive as Kafka headers, that the
 * subject becomes the record key, and that consumer groups deliver.
 *
 * Skipped unless Docker is available.
 */
class KafkaSuite extends munit.FunSuite with LogCapturing with SubscriberContract:

  override val munitTimeout = 5.minutes

  private val image = DockerImageName.parse("apache/kafka:3.8.0")

  private var kafka: KafkaContainer = null
  private var testKit: AnkkaTestKit = null
  private var bootstrap: String     = ""

  private val eventSerializer = Codecs.serializer[StockEvent]("stock-event")

  override def beforeAll(): Unit =
    kafka = KafkaContainer(image)
    kafka.start()
    bootstrap = kafka.getBootstrapServers

    testKit = AnkkaTestKit.start(
      Seq(StockLevels.descriptor, LowStockNotifier.descriptor, StockFanout.descriptor),
      Seq(ProjectionRuntime.withKafka(bootstrap))
    )

  override def afterAll(): Unit =
    if subscriberStarted then subscriber.stop()
    if testKit != null then testKit.stop()
    if kafka != null then kafka.stop()

  override def beforeEach(context: BeforeEach): Unit = LowStockNotifier.seen.clear()

  private def rows = testKit.service.viewClient.forView(StockLevels)

  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.service.system

  protected lazy val publisher: MessagePublisher = KafkaPublisher(bootstrap)

  @volatile private var subscriberStarted = false
  protected lazy val subscriber: MessageSubscriber =
    subscriberStarted = true
    KafkaSubscriber(bootstrap)

  protected def freshTopic(prefix: String): String = KafkaSuite.createTopic(bootstrap, prefix)

  private def publish(event: StockEvent): Unit =
    Await.result(
      publisher.publish(
        "stock-events",
        eventSerializer.toBytes(event),
        Metadata.empty.withSubject(event.sku)
      ),
      30.seconds
    ): Unit

  private def eventually[A](description: String, within: FiniteDuration = 60.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(250)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("a message published to Kafka reaches a topic-sourced view") {
    publish(StockEvent("k-sku-1", 7, "w1"))

    val row = eventually("the row appears")(rows.get("k-sku-1"))
    assertEquals(row.onHand, 7)
    assertEquals(row.updates, 1)
  }

  test("successive messages for one subject accumulate in order") {
    publish(StockEvent("k-sku-2", 10, "w1"))
    publish(StockEvent("k-sku-2", -4, "w1"))

    val row = eventually("both messages land")(rows.get("k-sku-2").filter(_.updates == 2))
    // Ordering is what the subject-as-key gives us: both messages land on one partition.
    assertEquals(row.onHand, 6)
  }

  test("a consumer on the same topic also receives every message") {
    publish(StockEvent("k-sku-3", -15, "w1"))

    val _ = eventually("the consumer saw it") {
      Option.when(LowStockNotifier.seen.asScala.exists(_.startsWith("k-sku-3:")))(())
    }
    // And the view saw it too — two components, one topic, separate consumer groups.
    val _ = eventually("the view saw it")(rows.get("k-sku-3"))
  }

  test("a consumer republishes onto another Kafka topic") {
    publish(StockEvent("k-sku-4", -25, "w1"))

    // Read the alert topic back with a plain consumer, so this asserts on the wire
    // rather than on ankka's own bookkeeping. Search rather than take the first record:
    // earlier tests in this suite also breach the threshold, and the topic accumulates.
    val (key, value) = eventually("an alert for k-sku-4 is published to Kafka") {
      KafkaSuite
        .readAll(bootstrap, "stock-alerts", "assert-alerts")
        .find((_, body) => body.contains("k-sku-4"))
    }
    assert(value.contains("\"sku\":\"k-sku-4\""), value)
    // ce-subject became the record key, preserving per-sku ordering downstream.
    assertEquals(key, "k-sku-4")
  }

  test("several messages for one message read: the record key is the one named, else the subject") {
    Await.result(
      publisher.publish(
        "fanout-events",
        eventSerializer.toBytes(StockEvent("k-fan-1", 5, "w1")),
        Metadata.empty.withSubject("k-fan-1")
      ),
      10.seconds
    )
    // Read back with a plain consumer: what is on the wire, not what ankka recorded.
    val records = eventually("three lines are on the topic") {
      Some(
        KafkaSuite
          .readHeaders(bootstrap, "fanout-lines", "assert-fanout")
          .filter((_, headers) => headers.get("ce-subject").contains("k-fan-1"))
      ).filter(_.sizeIs >= 3)
    }
    // In order on the partition they share; the second is under a key of its own.
    assertEquals(records.map(_._1).toSet, Set("k-fan-1", "second:k-fan-1"))
    assertEquals(records.count(_._1 == "k-fan-1"), 2)
    // The subject is the entity's id on every one, whatever the key.
    assertEquals(records.map(_._2.get("ce-subject")).distinct, Vector(Some("k-fan-1")))
    // Each is its own CloudEvent.
    assertEquals(records.flatMap(_._2.get("ce-id")).distinct.size, 3)
    assertEquals(records.count(_._2.get("x-n").contains("3")), 1)

    val bodies = KafkaSuite
      .readAll(bootstrap, "fanout-lines", "assert-fanout-bodies")
      .filter(_._2.contains("k-fan-1"))
    assertEquals(
      bodies.filter(_._1 == "k-fan-1").map(_._2),
      Vector(1, 3).map(n => s"""{"sku":"k-fan-1","n":$n}""")
    )
    assertEquals(
      bodies.filter(_._1 == "second:k-fan-1").map(_._2),
      Vector("""{"sku":"k-fan-1","n":2}""")
    )
  }

  // ── Groups of their own ───────────────────────────────────────────────────

  private def startAs(
      identity: ServiceIdentity,
      topic: String,
      id: String = "summary"
  ): AnkkaTestKit =
    AnkkaTestKit.start(
      Seq(stockView(id, topic).descriptor),
      Seq(ProjectionRuntime.withKafka(bootstrap)),
      serviceIdentity = identity
    )

  private def rowsOf(kit: AnkkaTestKit, topic: String, id: String = "summary") =
    kit.service.viewClient.forView(stockView(id, topic))

  /** `count` messages to `topic`, each about a subject of its own. */
  private def publishMany(topic: String, count: Int, prefix: String): Unit =
    (1 to count).foreach { n =>
      Await.result(
        publisher.publish(
          topic,
          eventSerializer.toBytes(StockEvent(s"$prefix-$n", 1, "w1")),
          Metadata.empty.withSubject(s"$prefix-$n")
        ),
        30.seconds
      ): Unit
    }

  private def holds(kit: AnkkaTestKit, topic: String, expected: Long): Unit =
    val _ = eventually(s"the view holds $expected rows", 120.seconds) {
      Option.when(rowsOf(kit, topic).count() == expected)(())
    }

  private def withKits[A](kits: AnkkaTestKit*)(body: => A): A =
    try body
    finally kits.foreach(_.stop())

  test("two services reading one topic through views of the same id each hold every message") {
    val topic   = KafkaSuite.createTopic(bootstrap, "shared-orders")
    val orders  = startAs(ServiceIdentity.deployed("shop", "orders"), topic)
    val billing = startAs(ServiceIdentity.deployed("shop", "billing"), topic)
    withKits(orders, billing) {
      publishMany(topic, 100, "order")
      holds(orders, topic, 100)
      holds(billing, topic, 100)
      // Read from the broker, not from ankka's log: two groups, each named for its service.
      val groups = KafkaSuite.groups(bootstrap)
      assert(groups.contains("ankka.shop.orders.view.summary"), groups.toString)
      assert(groups.contains("ankka.shop.billing.view.summary"), groups.toString)
    }
  }

  test("two named local services reading one topic each hold every message") {
    val topic   = KafkaSuite.createTopic(bootstrap, "shared-local")
    val orders  = startAs(ServiceIdentity.local("orders"), topic)
    val billing = startAs(ServiceIdentity.local("billing"), topic)
    withKits(orders, billing) {
      publishMany(topic, 100, "local")
      holds(orders, topic, 100)
      holds(billing, topic, 100)
      val groups = KafkaSuite.groups(bootstrap)
      assert(groups.contains("ankka.local.orders.view.summary"), groups.toString)
      assert(groups.contains("ankka.local.billing.view.summary"), groups.toString)
    }
  }

  test("the instances of one service read each partition of a topic once between them") {
    val topic = KafkaSuite.createTopic(bootstrap, "instances")
    val kit   = startAs(ServiceIdentity.deployed("shop", "orders"), topic)
    val peer  = kit.startPeer(Seq(ProjectionRuntime.withKafka(bootstrap)))
    try
      val group = "ankka.shop.orders.view.summary"
      // Both instances are members, and the topic's three partitions are divided between them,
      // each assigned to exactly one.
      val assigned = eventually("both instances share the topic", 120.seconds) {
        val members = KafkaSuite.assignments(bootstrap, group)
        Option.when(members.size == 2 && members.forall(_.nonEmpty))(members)
      }
      assertEquals(assigned.flatten.sorted, Vector(0, 1, 2))
      publishMany(topic, 100, "instance")
      holds(kit, topic, 100)
    finally
      peer.stop()
      kit.stop()
  }

  test("the longest permitted ids make a group the broker accepts") {
    val topic      = KafkaSuite.createTopic(bootstrap, "longest")
    val subscriber = KafkaSubscriber(bootstrap)
    val seen       = java.util.concurrent.ConcurrentLinkedQueue[String]()
    try
      subscriber.subscribe(
        TopicSubscription(topic, ConsumerGroups.Longest, StartFrom.Earliest),
        (message: IncomingMessage) =>
          seen.add(message.subject.getOrElse("")): Unit
          scala.concurrent.Future.successful(Done)
      ): Unit
      publishMany(topic, 1, "longest")
      val _ = eventually("a message is delivered under the longest group") {
        Option.when(!seen.isEmpty)(())
      }
      assert(KafkaSuite.groups(bootstrap).contains(ConsumerGroups.Longest))
    finally subscriber.stop()
  }

  test("an upgraded service starts again under its new group") {
    val topic = KafkaSuite.createTopic(bootstrap, "upgraded")
    publishMany(topic, 20, "before")
    // The group the view read under before services were named, with every offset committed.
    KafkaSuite.consumeAndCommit(bootstrap, topic, "ankka-view-summary", 20)
    val before = KafkaSuite.offsets(bootstrap, "ankka-view-summary")
    assertEquals(before.values.sum, 20L)

    val kit = startAs(ServiceIdentity.deployed("shop", "orders"), topic)
    withKits(kit) {
      // From the earliest message, under the qualified group: every row, again.
      holds(kit, topic, 20)
      assertEquals(KafkaSuite.offsets(bootstrap, "ankka-view-summary"), before)
    }
  }

  // ── Where a group starts ──────────────────────────────────────────────────

  test("a group's start is committed the moment it is assigned, so a restart does not skip") {
    // A group that starts at the latest message and has read nothing would, without a commit,
    // restart at the *new* end: past everything published while it was down.
    val topic = KafkaSuite.createTopic(bootstrap, "committed-start")
    publishMany(topic, 5, "before")
    val group = s"start-${java.util.UUID.randomUUID().toString.take(8)}"
    val seen  = java.util.concurrent.ConcurrentLinkedQueue[String]()
    def subscribe() = subscriber.subscribe(
      TopicSubscription(topic, group, StartFrom.Latest),
      (message: IncomingMessage) =>
        seen.add(message.subject.getOrElse("")): Unit
        scala.concurrent.Future.successful(Done)
    )
    val first = subscribe()
    // Every partition has an offset committed for it, having been handed nothing.
    val committed = eventually("the start is committed for every partition") {
      Some(KafkaSuite.offsets(bootstrap, group)).filter(_.size == 3)
    }
    assertEquals(committed.values.sum, 5L)
    first.stop()
    Thread.sleep(2000)
    publishMany(topic, 10, "while-down")
    val second = subscribe()
    try
      val _ = eventually("the ten published while it was down are delivered") {
        Option.when(seen.size >= 10)(())
      }
      Thread.sleep(2000)
      assertEquals(
        seen.asScala.toVector.sorted,
        (1 to 10).map(n => s"while-down-$n").toVector.sorted
      )
    finally second.stop()
  }

  // ── A version ────────────────────────────────────────────────────────────

  test(
    "a view and a consumer at a higher version read their topic again, under groups of their own"
  ) {
    import ViewVersionSuite.{VersionedRow, VersionedView}
    val topic = KafkaSuite.createTopic(bootstrap, "versioned")
    publishMany(topic, 20, "v")
    @volatile var declared = 1
    val consumed           = java.util.concurrent.ConcurrentLinkedQueue[(Int, String)]()
    def view(at: Int) =
      new com.thinkmorestupidless.ankka.sdk.View.Companion[VersionedView, StockEvent, VersionedRow](
        com.thinkmorestupidless.ankka.core.ComponentId("versioned"),
        com.thinkmorestupidless.ankka.sdk.ChangeSource.Topic(topic, eventSerializer, None),
        Codecs.serializer[VersionedRow]("versioned-row")
      ):
        override def version = Some(at)
        def create(ctx: com.thinkmorestupidless.ankka.sdk.ViewComponentContext) =
          new VersionedView(at)
    def consumer(at: Int) =
      new com.thinkmorestupidless.ankka.sdk.Consumer.Companion[RecordingAt, StockEvent, Nothing](
        com.thinkmorestupidless.ankka.core.ComponentId("consumed"),
        com.thinkmorestupidless.ankka.sdk.ChangeSource
          .Topic(topic, eventSerializer, Some(StartFrom.Earliest))
      ):
        override def version = Some(at)
        def create(ctx: com.thinkmorestupidless.ankka.sdk.ConsumerContext) =
          new RecordingAt(at, consumed)
    val log = LogLines()
    log.start()
    val kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(ProjectionRuntime.withKafka(bootstrap)),
      configure = _.register(view(declared).descriptor).register(consumer(declared).descriptor),
      serviceIdentity = ServiceIdentity.deployed("shop", "orders")
    )
    def rows() = kit.service.viewClient.forView(view(declared)).all()
    try
      eventually("twenty rows at version 1")(Option.when(rows().count(_.version == 1) == 20)(()))
      eventually("every offset committed at version 1") {
        Option.when(
          KafkaSuite.offsets(bootstrap, "ankka.shop.orders.view.versioned").values.sum == 20
        )(())
      }
      val before = KafkaSuite.offsets(bootstrap, "ankka.shop.orders.view.versioned")

      declared = 2
      kit.restartService()
      eventually("twenty rows at version 2, and none at 1", 120.seconds) {
        val now = rows()
        Option.when(now.size == 20 && now.forall(_.version == 2))(())
      }
      val groups = KafkaSuite.groups(bootstrap)
      assert(groups.contains("ankka.shop.orders.view-v2.versioned"), groups.toString)
      assert(groups.contains("ankka.shop.orders.consumer-v2.consumed"), groups.toString)
      assertEquals(KafkaSuite.offsets(bootstrap, "ankka.shop.orders.view.versioned"), before)
      eventually("the consumer was handed every message again") {
        Option.when(consumed.asScala.count(_._1 == 2) == 20)(())
      }
      assertEquals(consumed.asScala.count(_._1 == 1), 20)

      // The rebuild said how far back the broker reached: the first record of each partition.
      val line = log.containing("view rebuild: component=versioned ")
      assertEquals(line.size, 1, log.containing("component=versioned").mkString("\n"))
      KafkaSuite.firstTimestamps(bootstrap, topic).foreach { (partition, at) =>
        assert(line.head.contains(s"$partition=$at"), s"${line.head} names $partition=$at")
      }
    finally
      log.stop()
      kit.stop()
  }

  test("messages under one key reach their partition in the order they were published") {
    // A fresh publisher, so its producer is cold, and no waiting between publications: the shape
    // in which sends handed to a dispatcher one by one overtook each other.
    val topic = KafkaSuite.createTopic(bootstrap, "ordered")
    val cold  = KafkaPublisher(bootstrap)
    try
      val sent = (1 to 200).map(n =>
        cold.publish(topic, Some("one-key"), s"$n".getBytes("UTF-8"), Metadata.empty)
      )
      Await.result(
        scala.concurrent.Future.sequence(sent)(using
          implicitly,
          scala.concurrent.ExecutionContext.parasitic
        ),
        60.seconds
      ): Unit
    finally cold.close()
    val read = eventually("all two hundred are on the topic") {
      Some(KafkaSuite.readAll(bootstrap, topic, "assert-ordered").map(_._2)).filter(_.size == 200)
    }
    assertEquals(read, (1 to 200).map(_.toString).toVector)
  }

  test("CloudEvents attributes travel as Kafka headers") {
    publish(StockEvent("k-sku-5", 1, "w1"))

    val headers = eventually("the record is readable") {
      KafkaSuite
        .readHeaders(bootstrap, "stock-events", "assert-headers")
        .find((key, _) => key == "k-sku-5")
        .map(_._2)
    }
    assertEquals(headers.get("ce-subject"), Some("k-sku-5"))
    assertEquals(headers.get("ce-specversion"), Some("1.0"))
    assertEquals(headers.get("content-type"), Some("application/json"))
    assert(headers.contains("ce-id"), headers.toString)
    assert(headers.contains("ce-type"), headers.toString)
  }

/** A consumer that records each message it is handed, with the version it was declared at. */
final class RecordingAt(
    version: Int,
    seen: java.util.concurrent.ConcurrentLinkedQueue[(Int, String)]
) extends com.thinkmorestupidless.ankka.sdk.Consumer[StockEvent, Nothing]:
  def onMessage(event: StockEvent): Effect =
    seen.add(version -> event.sku): Unit
    effects.ignore()

object KafkaSuite:

  /** When the first record still held on each partition of `topic` that holds one was published. */
  def firstTimestamps(bootstrap: String, topic: String): Map[Int, java.time.Instant] =
    import org.apache.kafka.common.TopicPartition
    import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
    import org.apache.kafka.common.serialization.StringDeserializer
    val properties = java.util.Properties()
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    val client =
      KafkaConsumer[String, String](properties, StringDeserializer(), StringDeserializer())
    try
      val partitions =
        client.partitionsFor(topic).asScala.toVector.map(p => TopicPartition(topic, p.partition))
      client.assign(partitions.asJava)
      val ends    = client.endOffsets(partitions.asJava).asScala
      val holding = partitions.count(p => ends(p).longValue > 0)
      client.seekToBeginning(partitions.asJava)
      var found    = Map.empty[Int, java.time.Instant]
      val deadline = System.nanoTime() + 30.seconds.toNanos
      while found.size < holding && System.nanoTime() < deadline do
        client.poll(java.time.Duration.ofMillis(500)).asScala.foreach { record =>
          if !found.contains(record.partition) then
            found += record.partition -> java.time.Instant.ofEpochMilli(record.timestamp)
        }
      found
    finally client.close()
  import org.apache.kafka.clients.admin.{Admin, AdminClientConfig, NewTopic}
  import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
  import org.apache.kafka.common.serialization.StringDeserializer
  import java.time.Duration as JDuration
  import java.util.Properties

  private def admin[A](bootstrap: String)(use: Admin => A): A =
    val properties = Properties()
    properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    val client = Admin.create(properties)
    try use(client)
    finally client.close()

  /**
   * A topic of its own for one case, with three partitions, so that dividing a topic between a
   * group's members is something a case can see. Unique per call: cases leave their messages.
   */
  def createTopic(bootstrap: String, prefix: String, partitions: Int = 3): String =
    val name = s"$prefix-${java.util.UUID.randomUUID().toString.take(8)}"
    admin(bootstrap)(
      _.createTopics(java.util.List.of(NewTopic(name, partitions, 1.toShort))).all().get()
    ): Unit
    name

  /** Every consumer group the broker knows. */
  def groups(bootstrap: String): Set[String] =
    admin(bootstrap)(_.listConsumerGroups().all().get().asScala.map(_.groupId).toSet)

  /** Each member of `group`, as the partitions it is assigned. */
  def assignments(bootstrap: String, group: String): Vector[Vector[Int]] =
    admin(bootstrap) { client =>
      client
        .describeConsumerGroups(java.util.List.of(group))
        .all()
        .get()
        .asScala
        .get(group)
        .toVector
        .flatMap(_.members.asScala.toVector)
        .map(_.assignment.topicPartitions.asScala.toVector.map(_.partition))
    }

  /** The committed offset of each partition, for `group`. */
  def offsets(bootstrap: String, group: String): Map[Int, Long] =
    admin(bootstrap)(
      _.listConsumerGroupOffsets(group)
        .partitionsToOffsetAndMetadata()
        .get()
        .asScala
        .map((partition, offset) => partition.partition -> offset.offset)
        .toMap
    )

  /**
   * Reads `count` records of `topic` under `group` and commits them, as a consumer that ran would.
   */
  def consumeAndCommit(bootstrap: String, topic: String, group: String, count: Int): Unit =
    val client = consumer(bootstrap, group)
    try
      client.subscribe(java.util.List.of(topic))
      var read     = 0
      val deadline = System.nanoTime() + 60.seconds.toNanos
      while read < count && System.nanoTime() < deadline do
        read += client.poll(JDuration.ofSeconds(1)).count()
      assertEquals(read, count)
      client.commitSync()
    finally client.close()

  private def assertEquals(actual: Int, expected: Int): Unit =
    if actual != expected then throw AssertionError(s"read $actual records, expected $expected")

  private def consumer(bootstrap: String, groupId: String): KafkaConsumer[String, String] =
    val properties = Properties()
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    KafkaConsumer[String, String](properties, StringDeserializer(), StringDeserializer())

  /** Every record on `topic`, as (key, value). */
  def readAll(bootstrap: String, topic: String, groupId: String): Vector[(String, String)] =
    // A fresh group each poll, so `earliest` really means from the start — a reused
    // group would commit offsets and see nothing on the next attempt.
    val client = consumer(bootstrap, s"$groupId-${java.util.UUID.randomUUID()}")
    try
      client.subscribe(java.util.List.of(topic))
      client.poll(JDuration.ofSeconds(5)).asScala.toVector.map(r => r.key -> r.value)
    finally client.close()

  /** Headers per record key. */
  def readHeaders(
      bootstrap: String,
      topic: String,
      groupId: String
  ): Vector[(String, Map[String, String])] =
    val client = consumer(bootstrap, s"$groupId-${java.util.UUID.randomUUID()}")
    try
      client.subscribe(java.util.List.of(topic))
      client
        .poll(JDuration.ofSeconds(5))
        .asScala
        .toVector
        .map { record =>
          record.key -> record
            .headers()
            .asScala
            .map(h => h.key -> String(h.value, "UTF-8"))
            .toMap
        }
    finally client.close()
