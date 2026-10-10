package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime, SqlFragment}
import com.thinkmorestupidless.ankka.sdk.ViewDescriptor
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
 * The steps of `features/view-streams/`, run against a real service on a real database.
 *
 * One service serves every scenario of a file. A row-stream scenario's rows are written straight
 * into the view's table, emptied before each scenario: what is under test is how the rows are read,
 * and a hundred thousand orders placed through the entity would test the projection for minutes
 * first.
 *
 * The ask timeout is 1.5 seconds, so a declared query's statement timeout is one second: short
 * enough for a statement that pauses on every row to outlast one fetch.
 */
abstract class ViewStreamSteps(feature: String) extends GherkinSuite(feature) with LogCapturing:

  override val munitTimeout: Duration = 5.minutes

  protected var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    super.beforeAll()
    kit = AnkkaTestKit.start(
      Seq(OrderEntity.descriptor, Orders.descriptor),
      Seq(ProjectionRuntime()),
      settings = ConfigFactory.parseString("ankka.ask-timeout = 1500ms")
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  protected given Materializer = Materializer(kit.service.system)

  protected def database: Database = Database()(using kit.service.system)

  protected def await[A](future: Future[A], within: FiniteDuration = 60.seconds): A =
    Await.result(future, within)

  // ── The service ───────────────────────────────────────────────────────────

  Given("a service {string} with an event sourced entity {string}")((_: String, _: String) => ())

  Given("a view {string} that reads the events of {string} and keeps a row for each entity of it")(
    (_: String, _: String) => ()
  )

/** `features/view-streams/row-streams.feature`. */
final class RowStreamFeatures
    extends ViewStreamSteps("../../features/view-streams/row-streams.feature"):

  private val table = ViewDescriptor.tableFor(Orders.componentId)

  private def orders = kit.service.viewClient.forView(Orders)

  // What the scenario has seen.
  private var slowly                                = false
  private var answer: Option[Try[Vector[OrderRow]]] = None
  private var midway: Option[Boolean]               = None
  private val produced                              = AtomicInteger()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    slowly = false
    answer = None
    midway = None
    produced.set(0)
    await(database.execute(SqlFragment.raw(s"TRUNCATE $table"))): Unit

  /** Writes `count` rows for `customer`, numbered on from `from`. */
  private def hold(count: Int, customer: String, from: Int = 1): Unit =
    await(
      database.execute(
        SqlFragment.raw(
          s"""INSERT INTO $table (row_key, payload)
             |SELECT 'o' || n, json_build_object('orderId', 'o' || n, 'customer', '$customer', 'n', n)::text
             |FROM generate_series($from, ${from + count - 1}) AS n""".stripMargin
        )
      )
    ): Unit

  /** Whether the database is still holding a stream's statement open, part way through it. */
  private def statementOpen(): Boolean =
    await(
      database.query(
        SqlFragment.raw(
          s"""SELECT count(*) FROM pg_stat_activity
             |WHERE state IN ('active', 'idle in transaction') AND query LIKE '%FROM $table%'
             |AND query NOT LIKE '%pg_stat_activity%'""".stripMargin
        )
      )(_.get(0, classOf[java.lang.Long]).longValue)
    ).head > 0

  private def counted[A](source: Source[A, ?]): Source[A, ?] =
    source.map { row =>
      produced.incrementAndGet(); row
    }

  private def rows: Vector[OrderRow] =
    answer.getOrElse(fail("nothing was asked")).fold(f => fail(s"the stream failed: $f"), identity)

  private def failure: Throwable =
    answer.getOrElse(fail("nothing was asked")).failed.getOrElse(fail(s"the stream gave $answer"))

  Given("{string} holds {string} rows")((_: String, count: String) => hold(count.toInt, "anyone"))

  Given(
    "{string} declares the query {string} with a statement that reads its own table for the rows " +
      "holding the value {string}, in an order"
  )((_: String, query: String, _: String) => assertEquals(query, Orders.byCustomer.name))

  Given("{string} holds {string} rows holding {string} and {string} holding {string}")(
    (_: String, first: String, a: String, second: String, b: String) =>
      hold(first.toInt, a)
      hold(second.toInt, b, from = first.toInt + 1)
  )

  Given("the statement timeout is shorter than reading them takes")(() => slowly = true)

  When("a handler of {string} asks {string} for every row as a stream")((_: String, _: String) =>
    answer = Some(
      Try(
        await(
          counted(orders.allStream())
            .map { row =>
              // At the first row, the database must still be part way through the statement: the rows
              // reach the handler as they are yielded, not once they have all been read.
              if midway.isEmpty then midway = Some(statementOpen())
              row
            }
            .runWith(Sink.seq)
        ).toVector
      )
    )
  )

  When("a handler of {string} asks {string} for every row as a stream and reads it")(
    (_: String, _: String) =>
      // "Every row", read by a statement that pauses on each: the scenario's slow reading.
      val source = if slowly then orders.askStream(Orders.allSlowly) else orders.allStream()
      answer = Some(Try(await(source.runWith(Sink.seq)).toVector))
  )

  When("a handler of {string} asks {string} for every row whole")((_: String, _: String) =>
    answer = Some(Try(orders.all()))
  )

  When(
    "a handler of {string} asks {string} the query {string} as a stream with {string} as {string}"
  )((_: String, _: String, query: String, value: String, name: String) =>
    assertEquals(query, Orders.byCustomer.name)
    answer = Some(
      Try(await(orders.askStream(Orders.byCustomer, name -> value).runWith(Sink.seq)).toVector)
    )
  )

  When("a handler of {string} asks {string} for every row as a stream and reads {string} rows")(
    (_: String, _: String, count: String) =>
      val queue = counted(orders.allStream()).toMat(Sink.queue[OrderRow]())(Keep.right).run()
      (1 to count.toInt).foreach(_ => await(queue.pull()))
      Thread.sleep(1000) // what more the stream would produce unread, it has by now
      // Counted downstream, a stream over a collected answer would also show few rows produced;
      // the database still part way through the statement is what shows nothing was read ahead.
      midway = Some(statementOpen())
      queue.cancel()
  )

  When(
    "a handler of {string} asks {string} for every row as a stream and goes away after {string} rows"
  )((_: String, _: String, count: String) =>
    await(counted(orders.allStream()).take(count.toLong).runWith(Sink.ignore)): Unit
  )

  Then("the handler is given {string} rows")((count: String) =>
    assertEquals(rows.size, count.toInt)
  )

  Then("each row reaches the handler as the database yields it")(() =>
    assertEquals(midway, Some(true), "the first row arrived after the statement had finished")
  )

  Then("the handler is given {string} rows in the statement's order and no other")(
    (count: String) =>
      assertEquals(rows.size, count.toInt)
      assertEquals(rows.map(_.n), rows.map(_.n).sorted, "not in the statement's order")
      assertEquals(rows.map(_.customer).distinct, Vector("alice"))
  )

  Then("the stream ends in a failure that says the statement was ended")(() =>
    failure match
      case e: CommandError =>
        assertEquals(e.code, ErrorCode.Timeout)
        assert(e.getMessage.contains("the database ended it"), e.getMessage)
      case other => fail(s"expected a timeout, got $other")
  )

  Then("the handler is not given a shorter answer")(() =>
    assert(
      answer.exists(_.isFailure),
      s"the stream completed with ${answer.map(_.map(_.size))} rows"
    )
  )

  Then("fewer than {string} rows have been produced")((count: String) =>
    assertEquals(midway, Some(true), "the statement had been read to its end for a reader of ten")
    assert(produced.get < count.toInt, s"${produced.get} rows were produced for a reader of ten")
  )

  Then("the stream stops being produced")(() =>
    kit.eventually("the statement is no longer open")(Option.when(!statementOpen())(()))
    val before = produced.get
    Thread.sleep(500)
    assertEquals(produced.get, before, "rows were still produced after the handler went away")
  )
