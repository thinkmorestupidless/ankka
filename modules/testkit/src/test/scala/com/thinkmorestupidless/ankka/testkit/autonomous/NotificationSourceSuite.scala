package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.agent.autonomous.NotificationSource

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.testkit.scaladsl.TestSink

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** A reader that falls behind loses the oldest, and is told how many, before the next. */
class NotificationSourceSuite extends munit.FunSuite with LogCapturing:

  private given system: ActorSystem = ActorSystem("notification-source")

  override def afterAll(): Unit = Await.ready(system.terminate(), 10.seconds): Unit

  test("elements pass straight through a reader that keeps up") {
    val out = Await.result(
      Source(1 to 5).via(NotificationSource.dropOldest[Int](3, n => -n)).runWith(Sink.seq),
      5.seconds
    )
    assertEquals(out, 1 to 5)
  }

  test("a reader that does not read loses the oldest, and hears how many, then the newest") {
    val probe = Source(1 to 2000)
      .via(NotificationSource.dropOldest[Int](1024, n => -n))
      .toMat(TestSink[Int]())(Keep.right)
      .run()
    // Nothing has been read while 2000 arrived: the first read says 976 were lost.
    Thread.sleep(300)
    probe.request(1025)
    val got = probe.expectNextN(1025)
    assertEquals(got.head, -976)
    assertEquals(got.tail, (977 to 2000).toVector)
    probe.expectComplete()
  }
