package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.sdk.{Overflow, WatchEnd, WatchEnded, WatchEvent}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The unread rows of a watch: coalesced per key, bounded, and overflowing as the watcher chose,
 * with a reader that reads nothing until every row has arrived.
 *
 * The rows are offered through a queue the test fills before the reader pulls once, so "a reader
 * that does not read" is a fact of the test rather than of timing.
 */
class KeyedBufferSuite extends munit.FunSuite:

  // No cluster: a plain system binds no remoting port (the testing rules' first trap).
  private val system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "keyed-buffer",
    ConfigFactory.parseString("pekko.actor.provider = local").withFallback(ConfigFactory.load())
  )
  private given Materializer = Materializer(system)

  override def afterAll(): Unit = system.terminate()

  private def row(key: String, version: Int): WatchEvent[Int] = WatchEvent.Row(key, version)

  /**
   * A reader that asks for nothing until the test says, so "has not read" is exact: every element
   * is offered and taken by the buffer before the reader asks for its first.
   */
  private final class Reader extends org.reactivestreams.Subscriber[WatchEvent[Int]]:
    private val subscribed = scala.concurrent.Promise[org.reactivestreams.Subscription]()
    val got = java.util.concurrent.LinkedBlockingQueue[Try[Option[WatchEvent[Int]]]]()
    def onSubscribe(s: org.reactivestreams.Subscription): Unit = subscribed.success(s): Unit
    def onNext(e: WatchEvent[Int]): Unit = got.put(scala.util.Success(Some(e)))
    def onError(t: Throwable): Unit      = got.put(scala.util.Failure(t))
    def onComplete(): Unit               = got.put(scala.util.Success(None))
    def readAll(): Try[Vector[WatchEvent[Int]]] =
      val s = Await.result(subscribed.future, 3.seconds)
      def next(acc: Vector[WatchEvent[Int]]): Try[Vector[WatchEvent[Int]]] =
        s.request(1)
        Option(got.poll(3, java.util.concurrent.TimeUnit.SECONDS)) match
          case None => scala.util.Failure(AssertionError("nothing came"))
          case Some(scala.util.Success(Some(e))) => next(acc :+ e)
          case Some(scala.util.Success(None))    => scala.util.Success(acc)
          case Some(scala.util.Failure(t))       => scala.util.Failure(t)
      next(Vector.empty)

  /** Every element offered before the reader reads, then everything the reader is given. */
  private def through(bound: Int, overflow: Overflow)(
      elements: WatchEvent[Int]*
  ): Try[Vector[WatchEvent[Int]]] =
    val reader = Reader()
    val queue = Source
      .queue[WatchEvent[Int]](elements.size + 1, OverflowStrategy.fail)
      .via(KeyedBuffer[Int](bound, overflow))
      .toMat(Sink.fromSubscriber(reader))(Keep.left)
      .run()
    elements.foreach(e => Await.result(queue.offer(e), 3.seconds))
    queue.complete()
    // Let the buffer take everything offered before anything is read.
    Thread.sleep(300)
    reader.readAll()

  /** The keys of the rows the reader was given, in order. */
  private def keys(read: Try[Vector[WatchEvent[Int]]]): Vector[String] =
    read.get.collect { case WatchEvent.Row(k, _) => k }

  test("fifty versions of one row reach a reader that reads late as the last") {
    val read = through(10, Overflow.DropHead)((1 to 50).map(row("c1", _))*)
    assertEquals(read.get, Vector(row("c1", 50)))
  }

  test("a key written again keeps its one place, at the back") {
    assertEquals(
      keys(through(3, Overflow.DropHead)(row("a", 1), row("b", 1), row("c", 1), row("a", 2))),
      Vector("b", "c", "a")
    )
  }

  test("drop-head drops the row changed longest ago") {
    assertEquals(
      keys(through(3, Overflow.DropHead)(row("a", 1), row("b", 1), row("c", 1), row("d", 1))),
      Vector("b", "c", "d")
    )
  }

  test("drop-tail drops the row changed most recently") {
    assertEquals(
      keys(through(3, Overflow.DropTail)(row("a", 1), row("b", 1), row("c", 1), row("d", 1))),
      Vector("a", "b", "d")
    )
  }

  test("drop-new drops the row that arrived") {
    assertEquals(
      keys(through(3, Overflow.DropNew)(row("a", 1), row("b", 1), row("c", 1), row("d", 1))),
      Vector("a", "b", "c")
    )
  }

  test("drop-all drops every unread row") {
    assertEquals(
      keys(through(3, Overflow.DropAll)(row("a", 1), row("b", 1), row("c", 1), row("d", 1))),
      Vector("d")
    )
  }

  test("fail ends the watch unread") {
    val read = through(3, Overflow.Fail)(row("a", 1), row("b", 1), row("c", 1), row("d", 1))
    assertEquals(read.failed.toOption, Some(WatchEnded(WatchEnd.Unread)))
  }

  test("a removal coalesces with the row it removes") {
    val read = through(3, Overflow.DropHead)(row("a", 1), WatchEvent.Removed("a"))
    assertEquals(read.get, Vector(WatchEvent.Removed("a")))
  }

  test("everything held is given before the stream completes") {
    assertEquals(
      keys(through(10, Overflow.DropHead)(row("a", 1), row("b", 1), row("c", 1))),
      Vector("a", "b", "c")
    )
  }
