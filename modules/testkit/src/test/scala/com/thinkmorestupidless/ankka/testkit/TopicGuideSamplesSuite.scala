package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.runtime.{
  Ankka,
  InMemoryBroker,
  ProjectionRuntime,
  ServiceBuilder
}

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The samples of the topics guide (docs/build/topics.md): naming the broker, and the broker held in
 * memory.
 */
object TopicGuideSamples:

  /** A service that names its broker in code, as a service run on a developer's machine may. */
  def withKafka: ServiceBuilder =
    // docs:start with-kafka
    Ankka.service
      .register(StockLevels.descriptor)
      .withExtension(ProjectionRuntime.withKafka("localhost:9092"))
    // docs:end with-kafka

  /** A service that finds its broker in its environment, as one deployed to the platform must. */
  def fromEnv: ServiceBuilder =
    // docs:start from-env
    Ankka.service
      .register(StockLevels.descriptor)
      .withExtension(ProjectionRuntime.fromEnv())
    // docs:end from-env

class TopicGuideSamplesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("a service that names its broker, and one that reads it from its environment, are valid") {
    assert(TopicGuideSamples.withKafka.validate.isRight)
    assert(TopicGuideSamples.fromEnv.validate.isRight)
  }

  test("a message published to the in-memory broker becomes the view's row") {
    // docs:start in-memory-broker
    val broker  = InMemoryBroker()
    val testKit = AnkkaTestKit.start(
      Seq(StockLevels.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

    val sent = broker.publish(
      "stock-events",
      """{"sku":"p1","delta":5,"warehouse":"w1"}""".getBytes("UTF-8"),
      Metadata.empty.withSubject("p1")
    )
    // docs:end in-memory-broker
    try
      Await.ready(sent, 10.seconds): Unit
      val row = eventually("the row appears")(
        testKit.service.viewClient.forView(StockLevels).get("p1")
      )
      assertEquals(row.onHand, 5)
    finally testKit.stop()
  }

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))
