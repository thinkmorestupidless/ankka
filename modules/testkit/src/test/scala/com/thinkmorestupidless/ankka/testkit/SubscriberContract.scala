package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.runtime.{
  IncomingMessage,
  MessagePublisher,
  MessageSubscriber,
  TopicSubscription
}
import com.thinkmorestupidless.ankka.sdk.StartFrom
import org.apache.pekko.Done

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters.*

/**
 * What every `MessageSubscriber` owes, as cases a suite mixes in for its own broker: the in-memory
 * one every fast suite reads topics through, and Kafka. Two implementations of one contract drift
 * unless the same cases hold them both.
 */
trait SubscriberContract:
  self: munit.FunSuite =>

  /** The broker under test, both directions. */
  protected def publisher: MessagePublisher
  protected def subscriber: MessageSubscriber

  /** A topic no case has used, existing on the broker. */
  protected def freshTopic(prefix: String): String

  /** How long a delivery may take on this broker. */
  protected def patience: FiniteDuration = 60.seconds

  /**
   * Publishes and waits for the broker. The in-memory broker's publication also reports how its
   * groups' handlers fared, which is not the publisher's business here: every case asserts on what
   * was delivered instead.
   */
  private def send(topic: String, body: String): Unit =
    scala.util.Try(
      Await.result(
        publisher.publish(topic, body.getBytes("UTF-8"), Metadata.empty.withSubject(body)),
        30.seconds
      )
    ): Unit

  /** A subscription recording every body it is handed, in order. */
  private def recording(topic: String, group: String, start: StartFrom) =
    val seen = ConcurrentLinkedQueue[String]()
    val subscribed = subscriber.subscribe(
      TopicSubscription(topic, group, start),
      (message: IncomingMessage) =>
        seen.add(String(message.payload, "UTF-8")): Unit
        Future.successful(Done)
    )
    (seen, subscribed)

  private def waitFor(description: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + patience.toNanos
    while !check && System.nanoTime() < deadline do Thread.sleep(100)
    if !check then fail(s"$description did not happen within $patience")

  /** Long enough that a delivery that should not happen would have. */
  private def settle(): Unit = Thread.sleep(3000)

  private def group(): String = s"contract-${UUID.randomUUID().toString.take(8)}"

  test("contract: a group shares a topic between its subscribers, and every group gets it all") {
    val topic               = freshTopic("share")
    val shared              = group()
    val (first, firstSub)   = recording(topic, shared, StartFrom.Earliest)
    val (second, secondSub) = recording(topic, shared, StartFrom.Earliest)
    val (alone, aloneSub)   = recording(topic, group(), StartFrom.Earliest)
    try
      (1 to 20).foreach(n => send(topic, s"m$n"))
      waitFor("the group has every message")(first.size + second.size >= 20)
      waitFor("the other group has every message")(alone.size >= 20)
      settle()
      val both = first.asScala.toVector ++ second.asScala.toVector
      assertEquals(both.sorted, (1 to 20).map(n => s"m$n").toVector.sorted)
      assertEquals(alone.asScala.toVector.sorted, both.sorted)
    finally Seq(firstSub, secondSub, aloneSub).foreach(_.stop())
  }

  test("contract: a group resumes where it stopped, not where it would start") {
    val topic              = freshTopic("resume")
    val name               = group()
    val (before, firstSub) = recording(topic, name, StartFrom.Earliest)
    (1 to 5).foreach(n => send(topic, s"a$n"))
    waitFor("the first five")(before.size == 5)
    settle()
    firstSub.stop()
    settle()
    (1 to 5).foreach(n => send(topic, s"b$n"))
    // `Latest`, which a group that had never read would start after all ten: it resumes instead.
    val (after, secondSub) = recording(topic, name, StartFrom.Latest)
    try
      waitFor("the five published while it was stopped")(after.size >= 5)
      settle()
      assertEquals(after.asScala.toVector.sorted, (1 to 5).map(n => s"b$n").toVector.sorted)
    finally secondSub.stop()
  }

  test("contract: a group that has never read starts where it declares") {
    val topic = freshTopic("start")
    (1 to 5).foreach(n => send(topic, s"old$n"))
    Thread.sleep(1500)
    val between = Instant.now()
    Thread.sleep(1500)
    (1 to 3).foreach(n => send(topic, s"new$n"))

    val (earliest, e) = recording(topic, group(), StartFrom.Earliest)
    val (latest, l)   = recording(topic, group(), StartFrom.Latest)
    val (at, a)       = recording(topic, group(), StartFrom.At(between))
    try
      waitFor("earliest has everything")(earliest.size >= 8)
      waitFor("a time has what came after it")(at.size >= 3)
      settle()
      assertEquals(earliest.size, 8)
      assertEquals(at.asScala.toVector.sorted, Vector("new1", "new2", "new3"))
      assertEquals(latest.size, 0)
      send(topic, "newest")
      waitFor("latest has what came after it started")(latest.size >= 1)
      assertEquals(latest.asScala.toVector, Vector("newest"))
    finally Seq(e, l, a).foreach(_.stop())
  }

  test("contract: a message whose handler failed is delivered again") {
    val topic                = freshTopic("redeliver")
    val attempts             = ConcurrentLinkedQueue[String]()
    @volatile var failedOnce = false
    val subscribed = subscriber.subscribe(
      TopicSubscription(topic, group(), StartFrom.Earliest),
      (message: IncomingMessage) =>
        val body = String(message.payload, "UTF-8")
        attempts.add(body): Unit
        if body == "fragile" && !failedOnce then
          failedOnce = true
          Future.failed(RuntimeException("not this time"))
        else Future.successful(Done)
    )
    try
      send(topic, "fragile")
      waitFor("it failed once")(failedOnce)
      send(topic, "after")
      waitFor("it came again, and what followed it")(
        attempts.asScala.count(_ == "fragile") >= 2 && attempts.contains("after")
      )
    finally subscribed.stop()
  }

  test("contract: earliestRetained answers for every partition, and None for an empty one") {
    val empty = freshTopic("empty")
    val none  = Await.result(subscriber.earliestRetained(empty), patience)
    assert(none.nonEmpty, none.toString)
    assert(none.values.forall(_.earliestAt.isEmpty), none.toString)
    // Feature 043: an empty partition begins where it ends, and nothing was ever dropped from it.
    assert(none.values.forall(r => r.beginning == 0 && r.end == 0), none.toString)

    val topic = freshTopic("retained")
    val sent  = Instant.now().minusSeconds(1)
    send(topic, "one")
    val answer = Await.result(subscriber.earliestRetained(topic), patience)
    assertEquals(answer.keySet, none.keySet, "every partition is answered for")
    val times = answer.values.flatMap(_.earliestAt)
    assertEquals(times.size, 1, answer.toString)
    assert(!times.head.isBefore(sent), s"${times.head} is before $sent")
    // The partition holding it ends one past its beginning, which nothing has moved.
    val holding = answer.values.filter(_.earliestAt.isDefined)
    assertEquals(holding.map(r => (r.beginning, r.end)).toVector, Vector((0L, 1L)), answer.toString)
  }
