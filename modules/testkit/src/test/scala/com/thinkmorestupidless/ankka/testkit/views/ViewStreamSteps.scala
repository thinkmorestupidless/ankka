package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer, asSse}
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime, SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.sdk.{ViewDescriptor, WatchEvent}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
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

  protected var server: HttpServer = null

  override def beforeAll(): Unit =
    super.beforeAll()
    server = HttpServer.at("127.0.0.1", 0)(clients => CartsEndpoint(clients.viewClient))
    kit = AnkkaTestKit.start(
      Seq(OrderEntity.descriptor, Orders.descriptor, CartEntity.descriptor, Carts.descriptor),
      Seq(ProjectionRuntime(), server),
      settings = settings
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  /** What the service is started with: the row-stream scenarios shorten the ask timeout. */
  protected def settings: com.typesafe.config.Config = ConfigFactory.empty()

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

/** The route `watching.feature`'s browser reads: the open carts, watched, as server-sent events. */
final class CartsEndpoint(views: ViewClient) extends HttpEndpoint(""):
  val acl: Acl                          = Acl.AllowAll
  private given JsonValueCodec[CartRow] = JsonCodecMaker.make

  // docs:start watch-sse
  sseEvents("/carts/open")(() => views.forView(Carts).watch(Carts.openCarts).asSse)
  // docs:end watch-sse

/**
 * A handler reading a watch: everything it is given lands in a queue the steps read with a
 * deadline, so "given nothing" is a wait that came back empty. Stopping it cancels the watch, as a
 * handler that goes away does.
 */
final class Watcher(source: Source[WatchEvent[CartRow], NotUsed])(using Materializer):
  /** `Right` an element; `Left(None)` the watch completed; `Left(Some(e))` it failed with `e`. */
  val events = LinkedBlockingQueue[Either[Option[Throwable], WatchEvent[CartRow]]]()

  private val kill: UniqueKillSwitch =
    val (switch, done) = source
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(e => events.put(Right(e))))(Keep.both)
      .run()
    done.onComplete(r => events.put(Left(r.failed.toOption)))(using
      scala.concurrent.ExecutionContext.parasitic
    )
    switch

  def next(
      within: FiniteDuration = 10.seconds
  ): Option[Either[Option[Throwable], WatchEvent[CartRow]]] =
    Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))

  /** The rows given before the caught-up marker, failing when it does not come. */
  def untilCaughtUp(): Vector[WatchEvent.Row[CartRow]] =
    def loop(acc: Vector[WatchEvent.Row[CartRow]]): Vector[WatchEvent.Row[CartRow]] =
      next() match
        case Some(Right(row: WatchEvent.Row[CartRow])) => loop(acc :+ row)
        case Some(Right(WatchEvent.CaughtUp))          => acc
        case other => throw AssertionError(s"waited to be caught up, got $other after $acc")
    loop(Vector.empty)

  def stop(): Unit = kill.shutdown()

/** `features/view-streams/watching.feature`. */
final class WatchingFeatures
    extends ViewStreamSteps("../../features/view-streams/watching.feature"):

  private val scenarios                                         = AtomicInteger()
  private var scope                                             = ""
  private var watcher: Option[Watcher]                          = None
  private var seen: Vector[WatchEvent.Row[CartRow]]             = Vector.empty
  private var browser: Option[LinkedBlockingQueue[String]]      = None
  private var browsing: Option[java.util.stream.Stream[String]] = None
  private var statementsBefore                                  = 0

  override def beforeAll(): Unit =
    super.beforeAll()
    kit.logStatements()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    watcher.foreach(_.stop())
    watcher = None
    seen = Vector.empty
    browsing.foreach(_.close())
    browsing = None
    browser = None
    scope = s"w${scenarios.incrementAndGet()}"

  override def afterEach(context: AfterEach): Unit =
    watcher.foreach(_.stop())
    browsing.foreach(_.close())
    super.afterEach(context)

  private def scoped(key: String): String   = s"$scope-$key"
  private def unscoped(key: String): String = key.stripPrefix(s"$scope-")
  private def mine(key: String): Boolean    = key.startsWith(s"$scope-")

  private def carts = kit.service.viewClient.forView(Carts)

  private def cart(key: String) = kit.componentClient.forEventSourcedEntity(EntityId(scoped(key)))

  private def row(key: String): Option[CartRow] = carts.get(scoped(key))

  /** Waits until the view's row for `key` is as `ready` says: the view trails the entity. */
  private def projected(key: String)(ready: Option[CartRow] => Boolean): Unit =
    kit.eventually(s"the row $key")(Option.when(ready(row(key)))(()))

  private def open(key: String): Unit =
    cart(key).call(CartEntity.open).invoke(0L): Unit
    projected(key)(_.isDefined)

  private def checkOut(key: String): Unit =
    cart(key).call(CartEntity.checkOut).invoke(Done): Unit
    projected(key)(_.exists(_.checkedOut))

  private def startWatching(): Watcher =
    val w = Watcher(carts.watch(Carts.openCarts))
    watcher = Some(w)
    w

  private def startWatchingRow(key: String): Watcher =
    val w = Watcher(carts.watchRow(scoped(key)))
    watcher = Some(w)
    w

  private def reading: Watcher = watcher.getOrElse(fail("no handler is watching"))

  /** The next element given about one of this scenario's rows, skipping other scenarios'. */
  private def nextMine(
      within: FiniteDuration = 10.seconds
  ): Option[Either[Option[Throwable], WatchEvent[CartRow]]] =
    val deadline = System.nanoTime() + within.toNanos
    def loop(): Option[Either[Option[Throwable], WatchEvent[CartRow]]] =
      val left = (deadline - System.nanoTime()).nanos
      if left <= Duration.Zero then None
      else
        reading.next(left) match
          case Some(Right(WatchEvent.Row(key, _))) if !mine(key)  => loop()
          case Some(Right(WatchEvent.Removed(key))) if !mine(key) => loop()
          case other                                              => other
    loop()

  private def statements(): Int = "ankka_watched".r.findAllMatchIn(kit.databaseLog).size

  Given(
    "{string} declares the query {string} with a statement that reads its own table for the rows whose cart is open"
  )((_: String, query: String) => assertEquals(query, Carts.openCarts.name))

  Given(
    "{string} holds the open carts {string} and {string} and the cart {string} that is checked out"
  )((_: String, a: String, b: String, closed: String) =>
    open(a)
    open(b)
    open(closed)
    checkOut(closed)
  )

  Given("{string} holds the open carts {string} and {string}")((_: String, a: String, b: String) =>
    open(a)
    open(b)
  )

  Given("{string} holds no open cart")((_: String) => ())

  Given("{string} holds the row {string}")((_: String, key: String) => open(key))

  Given("{string} holds no row {string}")((_: String, key: String) => assertEquals(row(key), None))

  Given("{string} wrote the row {string} while no handler was watching {string}")(
    (_: String, key: String, _: String) => open(key)
  )

  Given("the cart {string} was then checked out")((key: String) => checkOut(key))

  Given("a handler of {string} watching {string}")((_: String, _: String) =>
    seen = startWatching().untilCaughtUp()
  )

  Given("a handler of {string} watching {string} that was given the row {string}")(
    (_: String, _: String, key: String) =>
      open(key)
      seen = startWatching().untilCaughtUp()
      assert(seen.exists(_.key == scoped(key)), s"$key was not among ${seen.map(_.key)}")
  )

  Given("a handler of {string} watching {string} that was not given the row {string}")(
    (_: String, _: String, key: String) =>
      open(key)
      checkOut(key)
      seen = startWatching().untilCaughtUp()
      assert(!seen.exists(_.key == scoped(key)), s"$key was given")
  )

  Given("a handler of {string} watching the row {string}")((_: String, key: String) =>
    if row(key).isEmpty then open(key)
    seen = startWatchingRow(key).untilCaughtUp()
  )

  Given("a handler of {string} watching the row {string} that was told it is caught up at once")(
    (_: String, key: String) =>
      seen = startWatchingRow(key).untilCaughtUp()
      assertEquals(seen, Vector.empty)
  )

  Given("an HTTP endpoint of {string} that serves {string} as server-sent events")(
    (_: String, query: String) => assertEquals(query, Carts.openCarts.name)
  )

  When("a handler of {string} watches {string}")((_: String, _: String) => startWatching(): Unit)

  When("a handler of {string} watches {string} and the cart {string} is then opened")(
    (_: String, _: String, key: String) =>
      startWatching()
      seen = reading.untilCaughtUp()
      cart(key).call(CartEntity.open).invoke(0L): Unit
  )

  When("the cart {string} is opened and {string} writes the row {string}")(
    (key: String, _: String, _: String) => cart(key).call(CartEntity.open).invoke(0L): Unit
  )

  When("an item is added to the cart {string} and {string} writes the row {string} again")(
    (key: String, _: String, _: String) => cart(key).call(CartEntity.addItem).invoke(Done): Unit
  )

  When(
    "the cart {string} is checked out and {string} writes the row {string} so that it no longer matches {string}"
  )((key: String, _: String, _: String, _: String) =>
    cart(key).call(CartEntity.checkOut).invoke(Done): Unit
  )

  When("{string} writes the row {string} so that it still does not match {string}")(
    (_: String, key: String, _: String) =>
      statementsBefore = statements()
      cart(key).call(CartEntity.touch).invoke(Done): Unit
      projected(key)(_.exists(_.version >= 3))
  )

  When("the handler stops reading")(() =>
    reading.stop()
    statementsBefore = statements()
  )

  When("{string} writes the row {string} three times")((_: String, key: String) =>
    (1 to 3).foreach(_ => cart(key).call(CartEntity.addItem).invoke(Done): Unit)
  )

  When("{string} writes the row {string}")((_: String, key: String) =>
    cart(key).call(CartEntity.open).invoke(0L): Unit
  )

  When("{string} deletes the row {string}")((_: String, key: String) =>
    cart(key).call(CartEntity.remove).invoke(Done): Unit
  )

  When("a browser reads the route and the cart {string} is then opened")((key: String) =>
    val lines = LinkedBlockingQueue[String]()
    val port  = server.boundPort.getOrElse(fail("the server did not bind"))
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/carts/open")).GET().build(),
        HttpResponse.BodyHandlers.ofLines()
      )
    assertEquals(response.statusCode, 200)
    val stream = response.body
    browsing = Some(stream)
    browser = Some(lines)
    Thread.ofVirtual().start(() => scala.util.Try(stream.forEach(l => lines.put(l))): Unit): Unit
    // The rows now, then caught up, before the cart is opened.
    kit.eventually("the browser is caught up")(
      Option.when(lines.toArray.toVector.contains("event:caught-up"))(())
    )
    cart(key).call(CartEntity.open).invoke(0L): Unit
  )

  Then("the handler is first given the rows {string} and {string} and no other")(
    (a: String, b: String) =>
      val rows = reading.untilCaughtUp().map(_.key).filter(mine).map(unscoped)
      assertEquals(rows.sorted, Vector(a, b).sorted)
  )

  Then("the handler is given the rows {string} and {string}")((a: String, b: String) =>
    assertEquals(seen.map(_.key).filter(mine).map(unscoped).sorted, Vector(a, b).sorted)
  )

  Then("the handler is then told it is caught up")(() =>
    // untilCaughtUp returned, so the marker came after the rows now and before anything after.
    ()
  )

  Then("the handler is told it is caught up before any row")(() =>
    val rows = reading.untilCaughtUp().filter(r => mine(r.key))
    assertEquals(rows, Vector.empty)
  )

  Then("the handler is then given the row {string}")((key: String) => givenRow(key))

  Then("the handler is given the row {string}")((key: String) => givenRow(key))

  private def givenRow(key: String): CartRow =
    nextMine() match
      case Some(Right(WatchEvent.Row(k, row))) if k == scoped(key) => row
      case other => fail(s"expected the row $key, got $other")

  Then("the handler is given the row {string} as it was written again")((key: String) =>
    val row = givenRow(key)
    assertEquals(row.items, 1, s"the row as written again has its item: $row")
  )

  Then("the handler is given a removal naming {string}")((key: String) =>
    nextMine() match
      case Some(Right(WatchEvent.Removed(k))) => assertEquals(k, scoped(key))
      case other                              => fail(s"expected a removal of $key, got $other")
  )

  Then("the handler is given nothing for {string}")((_: String) =>
    assertEquals(nextMine(2.seconds), None)
    // And the watch did read the row afresh, which is what makes the silence mean something.
    assert(statements() > statementsBefore, "the written row was never evaluated for the watch")
  )

  Then("the watch ends")(() =>
    reading.next() match
      case Some(Left(None)) => ()
      case other            => fail(s"expected the watch to end, got $other")
  )

  Then("no further row is produced for it")(() =>
    open("after")
    Thread.sleep(1000)
    assertEquals(statements(), statementsBefore, "a stopped watch was still evaluated")
  )

  Then("the handler is not given the row {string}")((key: String) =>
    val rows = reading.untilCaughtUp()
    assert(!rows.exists(_.key == scoped(key)), s"$key was given")
    assertEquals(nextMine(1.second), None)
  )

  Then("the handler was first given the row {string} as it stood")((key: String) =>
    assertEquals(seen.map(_.key), Vector(scoped(key)))
  )

  Then("the handler is given the versions in the order they were written")(() =>
    val first = seen.head.row.version
    // The view trails its entity by however long its projection takes, so the versions are read
    // until the last one written arrives, not for a fixed time.
    def read(acc: Vector[Int]): Vector[Int] =
      if acc.lastOption.contains(first + 3) then acc
      else
        nextMine(30.seconds) match
          case Some(Right(WatchEvent.Row(_, row))) => read(acc :+ row.version)
          case other => fail(s"after the versions $acc, expected the next, got $other")
    val versions = read(Vector.empty)
    assertEquals(versions, versions.sorted, "versions out of order")
    seenVersions = first +: versions
  )

  private var seenVersions: Vector[Int] = Vector.empty

  Then("the handler is never given an older version after a newer one")(() =>
    // The same version may be given twice — two writes read after the second — but never an older.
    assertEquals(seenVersions, seenVersions.sorted)
  )

  Then(
    "the browser receives the rows {string} and {string} and then the row {string} as one stream"
  )((a: String, b: String, c: String) =>
    val lines = browser.getOrElse(fail("no browser"))
    kit.eventually(s"the browser is given $c")(
      Option
        .when(lines.toArray.toVector.exists(_.toString.contains(s""""key":"${scoped(c)}"""")))(())
    )
    // One event is the lines up to a blank line, its fields in whatever order the server wrote.
    val events = lines.toArray.toVector
      .map(_.toString)
      .foldLeft(Vector(Vector.empty[String]))((acc, line) =>
        if line.isEmpty then acc :+ Vector.empty else acc.init :+ (acc.last :+ line)
      )
      .filter(_.nonEmpty)
      .flatMap { fields =>
        for
          name <- fields.find(_.startsWith("event:")).map(_.stripPrefix("event:").trim)
          data <- fields.find(_.startsWith("data:")).map(_.stripPrefix("data:").trim)
        yield (name, data)
      }
    val mineOnly = events.filter { case (name, data) =>
      name == "caught-up" || data.contains(s""""key":"$scope-""")
    }
    def keyOf(data: String) =
      """"key":"([^"]+)"""".r.findFirstMatchIn(data).map(m => unscoped(m.group(1)))
    val names = mineOnly.map { case (name, data) => (name, keyOf(data)) }
    assertEquals(names.take(2).map(_._1), Vector("row", "row"))
    assertEquals(names.take(2).flatMap(_._2).sorted, Vector(a, b).sorted)
    assertEquals(names.drop(2), Vector(("caught-up", None), ("row", Some(c))))
  )
