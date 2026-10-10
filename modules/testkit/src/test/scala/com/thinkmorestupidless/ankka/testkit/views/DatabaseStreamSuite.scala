package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory
import io.r2dbc.spi.R2dbcException
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
 * What a stream of rows promises against a real database, and the two facts about Postgres it rests
 * on: the statement timeout bounds each fetch of a portal rather than the stream, and a reader that
 * stops reading is bounded by the stream itself.
 *
 * The pool is held to ten connections, so "every connection went back" is a query answered after
 * fifty streams were cancelled, which a leak of even a fifth of them would starve.
 */
class DatabaseStreamSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var kit: AnkkaTestKit = null

  private def system: ActorSystem[?] = kit.service.system
  private def database: Database     = Database()(using system)
  private def mat: Materializer      = Materializer(system)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Nil,
      settings = ConfigFactory.parseString(
        """pekko.persistence.r2dbc.connection-factory { initial-size = 2, max-size = 10 }"""
      )
    )
    await(
      database.executeAll(
        Seq(
          SqlFragment.raw("CREATE TABLE streamed (n INT PRIMARY KEY)"),
          SqlFragment.raw("INSERT INTO streamed SELECT generate_series(1, 5000)")
        )
      )
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def await[A](future: Future[A], within: FiniteDuration = 30.seconds): A =
    Await.result(future, within)

  private def numbers(
      sql: String = "SELECT n FROM streamed ORDER BY n",
      timeout: FiniteDuration = 5.seconds,
      fetchSize: Int = 256
  ): Source[Int, ?] =
    database.stream(timeout, fetchSize)(sql, Nil)((row, _) => row.get(0, classOf[Integer]).intValue)

  test("every row, in the statement's order, past any limit a whole answer has") {
    val read = await(numbers().runWith(Sink.seq)(using mat))
    assertEquals(read, (1 to 5000).toVector)
  }

  test("a value is bound as a parameter") {
    val read = await(
      database
        .stream(5.seconds, 16)(
          "SELECT n FROM streamed WHERE n <= $1 ORDER BY n",
          Seq(Integer.valueOf(3))
        )((row, _) => row.get(0, classOf[Integer]).intValue)
        .runWith(Sink.seq)(using mat)
    )
    assertEquals(read, Vector(1, 2, 3))
  }

  test("fifty streams cancelled part way give every connection back to the pool") {
    (1 to 50).foreach { _ =>
      await(numbers(fetchSize = 16).take(10).runWith(Sink.seq)(using mat)): Unit
    }
    val answered = await(
      database.queryOne(SqlFragment.raw("SELECT count(*) FROM streamed"))(
        _.get(0, classOf[java.lang.Long]).longValue
      ),
      10.seconds
    )
    assertEquals(answered, Some(5000L))
  }

  test("a statement the database ends fails the stream, and is not a shorter answer") {
    val outcome = Try(
      await(
        numbers(sql = "SELECT n FROM streamed, pg_sleep(1) LIMIT 3", timeout = 200.millis)
          .runWith(Sink.seq)(using mat)
      )
    )
    // The driver's own exception arrives wrapped by Reactor, so the cause chain is read.
    def causes(t: Throwable): LazyList[Throwable] =
      if t == null then LazyList.empty else t #:: causes(t.getCause)
    val state = outcome.failed.toOption.flatMap(
      causes(_).collectFirst { case e: R2dbcException => e.getSqlState }
    )
    assertEquals(state, Some("57014"), s"expected the database's statement timeout, got $outcome")
  }

  test(
    "a slow reader outlives the statement timeout: the timeout bounds each fetch, not the stream"
  ) {
    val read = await(
      numbers(timeout = 1.second, fetchSize = 2)
        .take(6)
        .throttle(1, 300.millis)
        .runWith(Sink.seq)(using mat)
    )
    assertEquals(read, Vector(1, 2, 3, 4, 5, 6))
  }

  test("a reader that takes nothing for the timeout fails the stream, naming how long") {
    val produced = AtomicInteger()
    val queue = numbers(timeout = 500.millis, fetchSize = 16)
      .map { n =>
        produced.incrementAndGet(); n
      }
      .toMat(Sink.queue[Int]())(Keep.right)
      .run()(using mat)
    assertEquals(await(queue.pull()), Some(1))
    Thread.sleep(1500)
    // The queue hands over what it already holds before the failure behind it.
    def drain(taken: Int): Option[Throwable] =
      Try(await(queue.pull())) match
        case scala.util.Failure(failure)                => Some(failure)
        case scala.util.Success(Some(_)) if taken < 100 => drain(taken + 1)
        case scala.util.Success(_)                      => None
    val failure = drain(0)
    assert(
      failure.exists(_.isInstanceOf[Database.NotRead]),
      s"expected the stream to end not read, got $failure"
    )
    assert(failure.get.getMessage.contains("500 milliseconds"), failure.get.getMessage)
    assert(produced.get < 1000, s"${produced.get} rows were produced for a reader that took one")
  }
