package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.runtime.{
  IncomingMessage,
  InMemoryBroker,
  MessagePublisher,
  MessageSubscriber,
  TopicSubscription
}
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.pekko.Done

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*

/** The in-memory broker keeps the subscriber contract, and what only it can be asked to do. */
class InMemoryBrokerSuite extends munit.FunSuite with SubscriberContract:

  private val broker                              = InMemoryBroker()
  protected def publisher: MessagePublisher       = broker
  protected def subscriber: MessageSubscriber     = broker
  override protected def patience: FiniteDuration = 10.seconds
  private var topics                              = 0
  protected def freshTopic(prefix: String): String =
    topics += 1
    s"$prefix-$topics"

  private def send(topic: String, body: String): Unit =
    broker.publish(topic, body.getBytes("UTF-8"), Metadata.empty.withSubject(body)): Unit

  test("a group that starts behind is handed its backlog before what is published meanwhile") {
    val topic = freshTopic("backlog")
    (1 to 3).foreach(n => send(topic, s"old$n"))
    val seen = ConcurrentLinkedQueue[String]()
    // The first message is held until a new one has been published, so a broker that delivered
    // the new one straight away would put it ahead of the backlog.
    val release = Promise[Done]()
    broker.subscribe(
      TopicSubscription(topic, "g", StartFrom.Earliest),
      (message: IncomingMessage) =>
        val body = String(message.payload, "UTF-8")
        seen.add(body): Unit
        if body == "old1" then release.future else Future.successful(Done)
    ): Unit
    send(topic, "new")
    release.success(Done)
    assertEquals(seen.asScala.toVector, Vector("old1", "old2", "old3", "new"))
  }

  test("the clock a test sets decides where a start at a time begins") {
    val topic = freshTopic("clock")
    val base  = Instant.parse("2026-10-01T12:00:00Z")
    var now   = base
    broker.setClock(() => now)
    (1 to 50).foreach { n =>
      now = base.plusSeconds(n)
      send(topic, s"m$n")
    }
    broker.setClock(() => Instant.now())
    val seen = ConcurrentLinkedQueue[String]()
    broker.subscribe(
      TopicSubscription(topic, "g", StartFrom.At(base.plusSeconds(21))),
      (message: IncomingMessage) =>
        seen.add(String(message.payload, "UTF-8")): Unit
        Future.successful(Done)
    ): Unit
    assertEquals(seen.size, 30)
    assertEquals(seen.asScala.head, "m21")
  }
