package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Contract, Metadata, Serializer}
import com.thinkmorestupidless.ankka.runtime.{
  InMemoryBroker,
  ProjectDeclarations,
  ProjectionRuntime,
  StartRefusal
}
import com.thinkmorestupidless.ankka.sdk.{
  ChangeSource,
  Consumer,
  ConsumerContext,
  Publication,
  StartFrom
}

import java.nio.file.Files
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

/**
 * features/topics/contracts.feature, offline: the project's declarations are a value handed to the
 * projection runtime, as the file the operator writes would be, and the broker is in memory. The
 * cluster proof of the same scenarios is `TopicContractsFeatures`.
 */
class TopicContractsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val v1 = Contract("transaction.v1", "sha256:" + "a" * 64)
  private val v2 = Contract("transaction.v2", "sha256:" + "b" * 64)

  private def declaring(contract: Option[Contract]): ProjectDeclarations =
    ProjectDeclarations(
      "money",
      Map(
        "transactions" -> ProjectDeclarations.Topic("transactions", 3, compacted = false, contract)
      ),
      Map.empty
    )

  private val eventSerializer = Codecs.serializer[StockEvent]("stock-event")
  private val lineSerializer  = Codecs.serializer[FanLine]("fan-line")

  /** A consumer of "events" publishing to "transactions", stating `stated` for it, on `broker`. */
  private def wallet(stated: Option[Contract], broker: Option[String] = None) =
    new Consumer.Companion[Relay, StockEvent, FanLine](
      ComponentId("wallet"),
      ChangeSource.fromTopic("events", eventSerializer, StartFrom.Earliest)
    ):
      def create(ctx: ConsumerContext)                           = new Relay
      override val outputSerializer: Option[Serializer[FanLine]] = Some(lineSerializer)
      override def produces: Option[Publication] = Some(Publication("transactions", stated, broker))

  private var kit: AnkkaTestKit           = null
  private val broker                      = InMemoryBroker()
  private val terminationLog              = Files.createTempFile("termination", ".log")
  private var hadProperty: Option[String] = None

  override def beforeAll(): Unit =
    hadProperty = sys.props.get(StartRefusal.PathProperty)
    sys.props(StartRefusal.PathProperty) = terminationLog.toString

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    hadProperty.fold(sys.props.remove(StartRefusal.PathProperty): Unit)(
      sys.props(StartRefusal.PathProperty) = _
    )

  private def start(
      descriptor: com.thinkmorestupidless.ankka.core.ComponentDescriptor,
      declared: ProjectDeclarations
  ) =
    Try(
      AnkkaTestKit.start(
        Seq(descriptor),
        Seq(ProjectionRuntime.withBroker(broker, broker).withDeclarations(declared))
      )
    )

  private def refusal(
      descriptor: com.thinkmorestupidless.ankka.core.ComponentDescriptor,
      declared: ProjectDeclarations
  ): String =
    Files.deleteIfExists(terminationLog): Unit
    val failed = start(descriptor, declared)
    failed.foreach(_.stop())
    assert(failed.isFailure, "the service started")
    // The reason, where the operator reads it: the container's termination message.
    val message = Files.readString(terminationLog)
    assert(message.startsWith("cannot start ankka projections:"), message)
    message

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("a component that states another contract is refused, naming both") {
    val message = refusal(wallet(Some(v2)).descriptor, declaring(Some(v1)))
    assert(
      message.contains(
        s"consumer 'wallet' publishes to 'transactions' as 'transaction.v2' (${v2.fingerprint})"
      ),
      message
    )
    assert(
      message.contains(s"project 'money' declares 'transaction.v1' (${v1.fingerprint})"),
      message
    )
  }

  test("a component that states no contract on a topic that has one is refused") {
    val message = refusal(wallet(None).descriptor, declaring(Some(v1)))
    assert(
      message.contains("consumer 'wallet' publishes to 'transactions' with no contract"),
      message
    )
    assert(message.contains(s"declares 'transaction.v1' (${v1.fingerprint})"), message)
  }

  test("a component built against another schema is refused, naming both") {
    val other   = Contract("transaction.v1", "sha256:" + "c" * 64)
    val message = refusal(wallet(Some(other)).descriptor, declaring(Some(v1)))
    assert(
      message.contains(
        s"as 'transaction.v1' (${other.fingerprint}); project 'money' declares 'transaction.v1' (${v1.fingerprint})"
      ),
      message
    )
  }

  // features/topics/brokers.feature
  test("a component naming a broker the project has not declared is refused") {
    val message = refusal(wallet(None, Some("legacy")).descriptor, declaring(None))
    assert(
      message.contains(
        "consumer 'wallet' publishes to 'transactions' on broker 'legacy', which project 'money' does not declare"
      ),
      message
    )
  }

  test("a topic without a contract checks nothing") {
    val started = start(wallet(Some(v1)).descriptor, declaring(None))
    assert(started.isSuccess, started.toString)
    started.foreach(_.stop())
  }

  test(
    "a component that states the declared contract is accepted, and a published message carries the contract as its type"
  ) {
    val started = start(wallet(Some(v1)).descriptor, declaring(Some(v1)))
    assert(started.isSuccess, started.toString)
    kit = started.get
    broker.publish(
      "events",
      eventSerializer.toBytes(StockEvent("sku-1", -12, "w1")),
      Metadata.empty.withSubject("sku-1")
    ): Unit
    val delivered = eventually("the relay publishes")(broker.publishedTo("transactions").headOption)
    assertEquals(delivered.message.metadata.eventType, Some("transaction.v1"))
  }

/** Reads a stock event and publishes one line for it: the smallest relay. */
final class Relay extends Consumer[StockEvent, FanLine]:
  def onMessage(event: StockEvent): Effect = effects.produce(FanLine(event.sku, 1))

// docs:start contract
/** Reads "events" and publishes to "transactions", stating the contract the topic carries. */
object WalletRelay
    extends Consumer.Companion[Relay, StockEvent, FanLine](
      ComponentId("wallet"),
      ChangeSource
        .fromTopic("events", Codecs.serializer[StockEvent]("stock-event"), StartFrom.Earliest)
    ):
  // The schema document fetched from the project with `ankka projects topics schema get`.
  private val transactions = Contract
    .fromSchema(
      "transaction.v1",
      getClass.getResourceAsStream("/schemas/transaction.v1.json").readAllBytes()
    )
    .fold(why => throw IllegalArgumentException(why), identity)

  def create(ctx: ConsumerContext) = new Relay
  override val outputSerializer: Option[Serializer[FanLine]] = Some(
    Codecs.serializer[FanLine]("fan-line")
  )
  override def produces: Option[Publication] = Some(Publication("transactions", Some(transactions)))
// docs:end contract
