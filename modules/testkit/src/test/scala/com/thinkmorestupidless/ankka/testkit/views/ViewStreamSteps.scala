package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{Done, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer, asSse}
import com.thinkmorestupidless.ankka.runtime.{Database, ProjectionRuntime, SqlFragment, ViewClient}
import com.thinkmorestupidless.ankka.sdk.{ViewDescriptor, WatchEnded, WatchEvent}
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
    // Registered at every start, so a restart declares the version `CartsVersion` says then.
    kit = AnkkaTestKit.start(
      Nil,
      Seq(ProjectionRuntime(), server),
      configure = _.registerAll(
        Seq(OrderEntity.descriptor, Orders.descriptor, CartEntity.descriptor, Carts.descriptor)
      ),
      settings = settings
    )

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  /** What the service is started with: the row-stream scenarios shorten the ask timeout. */
  protected def settings: com.typesafe.config.Config = ConfigFactory.empty()

  // Inline, so it is the running service's: a restart replaces the actor system, and a given val
  // would keep the first one's materializer, shut down with it.
  protected inline given Materializer = Materializer(kit.service.system)

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

/**
 * A handler that reads only when told: what it is given lands in a queue, and it asks for one more
 * element at a time. "Does not read" is then exact — the handler has asked for nothing — rather
 * than a guess about how fast it reads.
 */
final class SlowReader(source: Source[WatchEvent[CartRow], NotUsed])(using Materializer)
    extends org.reactivestreams.Subscriber[WatchEvent[CartRow]]:
  private val subscribed = scala.concurrent.Promise[org.reactivestreams.Subscription]()
  val events             = LinkedBlockingQueue[Either[Option[Throwable], WatchEvent[CartRow]]]()

  def onSubscribe(s: org.reactivestreams.Subscription): Unit = subscribed.success(s): Unit
  def onNext(e: WatchEvent[CartRow]): Unit                   = events.put(Right(e))
  def onError(t: Throwable): Unit                            = events.put(Left(Some(t)))
  def onComplete(): Unit                                     = events.put(Left(None))

  source.runWith(Sink.fromSubscriber(this)): Unit

  private def subscription = Await.result(subscribed.future, 10.seconds)

  /** Asks for one more element and waits for it. */
  def read(
      within: FiniteDuration = 10.seconds
  ): Option[Either[Option[Throwable], WatchEvent[CartRow]]] =
    subscription.request(1)
    Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))

  /** Reads until the caught-up marker, then stops reading. */
  def untilCaughtUp(): Unit =
    def loop(): Unit = read() match
      case Some(Right(WatchEvent.CaughtUp)) => ()
      case Some(Right(_))                   => loop()
      case other => throw AssertionError(s"waited to be caught up, got $other")
    loop()

  /** An end the watch came to without being read: its failure, or none when it completed. */
  def ended(within: FiniteDuration = 10.seconds): Option[Option[Throwable]] =
    val deadline = System.nanoTime() + within.toNanos
    def loop(): Option[Option[Throwable]] =
      val left = deadline - System.nanoTime()
      if left <= 0 then None
      else
        Option(events.poll(left / 1000000, TimeUnit.MILLISECONDS)) match
          case Some(Left(end)) => Some(end)
          case Some(Right(_))  => loop()
          case None            => None
    loop()

  def stop(): Unit = subscription.cancel()

/** `features/view-streams/watch-rules.feature`. */
final class WatchRulesFeatures
    extends ViewStreamSteps("../../features/view-streams/watch-rules.feature"):

  // A one-second heartbeat: a server learns that a reader of server-sent events went away only
  // when it next writes to the connection, which a quiet watch does once a heartbeat.
  override protected def settings = ConfigFactory.parseString(
    """ankka.view.watch-bound = 4
      |ankka.http.sse.heartbeat = 1s""".stripMargin
  )

  private val scenarios                            = AtomicInteger()
  private var scope                                = ""
  private var peer: Option[AnkkaTestKit.Peer]      = None
  private var watchers: Vector[Watcher]            = Vector.empty
  private var slow: Option[SlowReader]             = None
  private var written: Vector[String]              = Vector.empty
  private var refused: Option[Throwable]           = None
  private var started: Option[scala.util.Try[Any]] = None
  private var held: Vector[Watcher]                = Vector.empty

  override def afterAll(): Unit =
    peer.foreach(_.stop())
    CartsVersion.current = 1
    super.afterAll()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    (watchers ++ held).foreach(_.stop())
    slow.foreach(s => scala.util.Try(s.stop()))
    watchers = Vector.empty
    held = Vector.empty
    slow = None
    written = Vector.empty
    refused = None
    started = None
    peer.foreach(_.stop())
    peer = None
    scope = s"r${scenarios.incrementAndGet()}"
    kit.eventually("the last scenario's watches have ended")(
      Option.when(kit.service.viewClient.openWatches == 0)(())
    )

  private def scoped(key: String): String = s"$scope-$key"
  private def mine(key: String): Boolean  = key.startsWith(s"$scope-")

  private def carts = kit.service.viewClient.forView(Carts)

  private def cart(
      key: String,
      via: com.thinkmorestupidless.ankka.sdk.ComponentClient = kit.componentClient
  ) =
    via.forEventSourcedEntity(EntityId(scoped(key)))

  private def projected(key: String)(ready: Option[CartRow] => Boolean): Unit =
    kit.eventually(s"the row $key")(Option.when(ready(carts.get(scoped(key))))(()))

  /** Writes `key` and waits for the view to hold it, so writes are announced in the order made. */
  private def writeInTurn(key: String): Unit =
    cart(key).call(CartEntity.open).invoke(0L): Unit
    projected(key)(_.isDefined)
    written :+= key

  /** A watcher on `on`, run by `on`'s own actor system, so restarting another leaves it running. */
  private def watching(
      on: com.thinkmorestupidless.ankka.runtime.AnkkaService = kit.service
  ): Watcher =
    val w =
      Watcher(on.viewClient.forView(Carts).watch(Carts.openCarts))(using Materializer(on.system))
    w.untilCaughtUp(): Unit
    watchers :+= w
    w

  private def slowly(watching: com.thinkmorestupidless.ankka.sdk.Watching): SlowReader =
    val reader = SlowReader(carts.watch(Carts.openCarts, watching))
    reader.untilCaughtUp()
    slow = Some(reader)
    reader

  private def reader: SlowReader = slow.getOrElse(fail("no handler is watching slowly"))

  /** The rows of this scenario a reader is given when it reads everything waiting, in order. */
  private def readAll(): Vector[WatchEvent.Row[CartRow]] =
    Iterator
      .continually(reader.read(1500.millis))
      .takeWhile(_.nonEmpty)
      .flatten
      .collect { case Right(row: WatchEvent.Row[CartRow]) if mine(row.key) => row }
      .toVector

  private def ofKind(watcher: Watcher, key: String, within: FiniteDuration = 30.seconds): CartRow =
    val deadline = System.nanoTime() + within.toNanos
    def loop(): CartRow =
      watcher.next((deadline - System.nanoTime()).nanos.max(1.milli)) match
        case Some(Right(WatchEvent.Row(k, row))) if k == scoped(key) => row
        case Some(Right(_))                                          => loop()
        case other => fail(s"expected the row $key, got $other")
    loop()

  private def endedWith(watcher: Watcher): Option[Throwable] =
    val deadline = System.nanoTime() + 10.seconds.toNanos
    def loop(): Option[Throwable] =
      watcher.next((deadline - System.nanoTime()).nanos.max(1.milli)) match
        case Some(Left(end)) => end
        case Some(Right(_))  => loop()
        case None            => fail("the watch did not end")
    loop()

  Given(
    "{string} declares the query {string} with a statement that reads its own table for the rows whose cart is open"
  )((_: String, query: String) => assertEquals(query, Carts.openCarts.name))

  // ── Two instances ─────────────────────────────────────────────────────────

  Given("two instances of {string}")((_: String) =>
    peer = Some(kit.startPeer(Seq(ProjectionRuntime())))
  )

  Given("a handler on the first instance watching {string}")((_: String) => watching(): Unit)

  When("the second instance writes the row {string} for {string}")((key: String, _: String) =>
    cart(key, peer.getOrElse(fail("no second instance")).componentClient)
      .call(CartEntity.open)
      .invoke(0L): Unit
  )

  Then("the handler is given the row {string}")((key: String) => ofKind(watchers.head, key): Unit)

  // ── A watcher that does not read ──────────────────────────────────────────

  Given("a handler of {string} watching {string} that does not read")((_: String, _: String) =>
    slowly(com.thinkmorestupidless.ankka.sdk.Watching()): Unit
  )

  Given(
    "a handler of {string} watching {string} with an unread bound of {string} rows that does not read"
  )((_: String, _: String, bound: String) =>
    slowly(com.thinkmorestupidless.ankka.sdk.Watching(unread = Some(bound.toInt))): Unit
  )

  Given(
    "a handler of {string} watching {string} with an unread bound of {string} rows and the overflow strategy {string} that does not read"
  )((_: String, _: String, bound: String, strategy: String) =>
    assertEquals(strategy, "fail")
    slowly(
      com.thinkmorestupidless.ankka.sdk.Watching(
        unread = Some(bound.toInt),
        overflow = com.thinkmorestupidless.ankka.sdk.Overflow.Fail
      )
    ): Unit
  )

  When("{string} writes the row {string} {string} times")((_: String, key: String, times: String) =>
    cart(key).call(CartEntity.open).invoke(0L): Unit
    (2 to times.toInt).foreach(_ => cart(key).call(CartEntity.addItem).invoke(Done): Unit)
    projected(key)(_.exists(_.items == times.toInt - 1))
    Thread.sleep(1000) // the last write's announcement is read
  )

  When("{string} writes {string} rows for different carts")((_: String, count: String) =>
    (1 to count.toInt).foreach(i => writeInTurn(s"d$i"))
    Thread.sleep(1000)
  )

  When("the handler then reads")(() => ())

  Then("the handler is given the last version of {string}")((key: String) =>
    val rows = readAll().filter(_.key == scoped(key))
    assert(rows.nonEmpty, "nothing was given")
    assertEquals(
      rows.last.row.items,
      49,
      s"the last version was not given: ${rows.map(_.row.version)}"
    )
    written = rows.map(_.row.version.toString)
  )

  Then("the handler is given fewer than {string} versions of {string}")(
    (count: String, _: String) =>
      assert(written.size < count.toInt, s"${written.size} versions were given")
  )

  Then("the handler is given the {string} rows written last")((count: String) =>
    val read = readAll().map(_.key)
    assertEquals(read.toSet, written.takeRight(count.toInt).map(scoped).toSet)
  )

  Then("the handler is not given the {string} rows written first until they are written again")(
    (count: String) =>
      assertEquals(readAll(), Vector.empty)
      val first = written.take(count.toInt)
      first.foreach(key => cart(key).call(CartEntity.addItem).invoke(Done): Unit)
      first.foreach(key => projected(key)(_.exists(_.items == 1)))
      val again = Iterator
        .continually(reader.read(30.seconds))
        .takeWhile(_.nonEmpty)
        .flatten
        .collect { case Right(row: WatchEvent.Row[CartRow]) if mine(row.key) => row.key }
        .take(count.toInt)
        .toVector
      assertEquals(again.toSet, first.map(scoped).toSet)
  )

  Then("the watch ends")(() =>
    slow match
      case Some(r) => refused = r.ended().getOrElse(fail("the watch did not end"))
      case None    => refused = endedWith(watchers.head)
  )

  Then("the watcher is told the watch ended unread")(() =>
    assertEquals(refused, Some(WatchEnded(com.thinkmorestupidless.ankka.sdk.WatchEnd.Unread)))
  )

  // ── Rebuilds and stops ────────────────────────────────────────────────────

  Given("handlers of {string} watching {string}")((_: String, _: String) =>
    // On a second instance, which stays up while the first is restarted at the higher version.
    val second = kit.startPeer(Seq(ProjectionRuntime()))
    peer = Some(second)
    held = Vector(watching(second.service), watching(second.service))
    watchers = Vector.empty
  )

  When("{string} restarts with {string} at a higher version")((_: String, _: String) =>
    CartsVersion.current += 1
    server = HttpServer.at("127.0.0.1", 0)(clients => CartsEndpoint(clients.viewClient))
    kit.restartService(extensions = Seq(ProjectionRuntime(), server))
  )

  When("{string} is emptied")((_: String) =>
    kit.eventually("the view is recorded at the higher version")(
      Option.when(
        await(
          database.query(
            SqlFragment.raw(
              s"SELECT version FROM ankka_view_versions WHERE component_id = 'carts'"
            )
          )(_.get("version", classOf[Integer]).intValue)
        ).contains(CartsVersion.current)
      )(())
    )
  )

  Then("every watch of {string} ends")((_: String) =>
    val ends = held.map(endedWith)
    refused = ends.headOption.flatten
    assert(ends.forall(_.nonEmpty), s"a watch did not end with a reason: $ends")
    assertEquals(ends.distinct.size, 1, s"$ends")
  )

  Then("each watcher is told the view was rebuilt")(() =>
    assertEquals(refused, Some(WatchEnded(com.thinkmorestupidless.ankka.sdk.WatchEnd.Rebuilt)))
  )

  Given("a handler watching {string} served by the first instance")((_: String) =>
    val first = peer.getOrElse(fail("no second instance"))
    held = Vector(watching(first.service))
    watchers = Vector.empty
  )

  When("the first instance stops")(() =>
    peer.foreach(_.stop())
    peer = None
    watchers = held
    held = Vector.empty
  )

  Then("the watcher is told the instance stopped")(() =>
    assertEquals(
      refused,
      Some(WatchEnded(com.thinkmorestupidless.ankka.sdk.WatchEnd.InstanceStopping))
    )
  )

  // ── The bound ─────────────────────────────────────────────────────────────

  Given("an instance of {string} with as many open watches as the installation's watch bound")(
    (_: String) =>
      (1 to 4).foreach(_ => watching(): Unit)
      assertEquals(kit.service.viewClient.openWatches, 4)
  )

  When("a handler of {string} watches {string}")((_: String, _: String) =>
    val w = Watcher(carts.watch(Carts.openCarts))
    watchers :+= w
    refused = endedWith(w)
  )

  Then("the handler is refused")(() => assert(refused.nonEmpty, "the watch was not refused"))

  Then("the refusal names the watch bound")(() =>
    refused match
      case Some(e: CommandError) =>
        assertEquals(e.code, ErrorCode.Unavailable)
        assert(e.getMessage.contains("4 open watches, its watch bound"), e.getMessage)
      case other => fail(s"expected a refusal naming the bound, got $other")
  )

  // ── The topology ──────────────────────────────────────────────────────────

  Given("a handler of {string} that watched {string} and stopped")((_: String, _: String) =>
    // The handler is the endpoint's route serving the watch as server-sent events. Read on a
    // socket of the test's own, closed once caught up: a page going away closes its connection,
    // and the JDK's client keeps a connection open after its body is closed.
    val port   = server.boundPort.getOrElse(fail("the server did not bind"))
    val socket = java.net.Socket("127.0.0.1", port)
    socket.getOutputStream.write(
      s"GET /carts/open HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: text/event-stream\r\n\r\n".getBytes
    )
    val lines = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream))
    while lines.readLine() match
        case null              => fail("the stream ended before it was caught up")
        case "event:caught-up" => false
        case _                 => true
    do ()
    socket.close()
    kit.eventually("the watch has ended")(Option.when(kit.service.viewClient.openWatches == 0)(()))
  )

  When("a developer reads the service's topology")(() => ())

  Then("the topology shows one observed call from the handler to {string}, handled as ok")(
    (view: String) =>
      val address = kit.service.observabilityAddress.getOrElse(fail("no observability endpoint"))
      val body = kit.eventually("the watch is counted") {
        val response = HttpClient
          .newHttpClient()
          .send(
            HttpRequest.newBuilder(URI.create(s"$address/observability/topology")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
          )
        Option.when(response.body.contains(""""callee":"open-carts""""))(response.body)
      }
      val document =
        com.github.plokhotnyuk.jsoniter_scala.core.readFromString[TopologyDocument](body)
      // From the route that served it; other scenarios' watches, made by the test itself, are the
      // unknown caller's.
      val calls = document.calls
        .filter(c => c.to == view && c.from.startsWith("endpoint:"))
        .flatMap(_.pairs)
        .filter(p => p.callee == "open-carts" && p.caller == "GET /carts/open")
      assertEquals(calls.size, 1, body)
      assert(calls.head.streaming, s"the call is not marked a stream: $body")
      assertEquals(calls.head.handled.ok, 1L, body)
      assertEquals(calls.head.handled.failed, 0L, body)
  )

  // ── Evaluated on writes ───────────────────────────────────────────────────

  Given("{string} declares the query {string} with a statement whose match depends on the time")(
    (_: String, query: String) => assertEquals(query, Carts.dueCarts.name)
  )

  Given("a handler of {string} watching {string}")((_: String, _: String) =>
    val w = Watcher(carts.watch(Carts.dueCarts))
    w.untilCaughtUp(): Unit
    watchers :+= w
  )

  When("the row {string} comes to match {string} by the passing of time alone")(
    (key: String, _: String) =>
      cart(key).call(CartEntity.open).invoke(System.currentTimeMillis() + 3000): Unit
      projected(key)(_.isDefined)
      Thread.sleep(4000)
      assert(
        carts.ask(Carts.dueCarts).exists(_.cartId == scoped(key)),
        "the row does not match by now"
      )
  )

  Then("the handler is given nothing for {string} until {string} writes the row {string} again")(
    (key: String, _: String, _: String) =>
      val w = watchers.head
      def mineNext(within: FiniteDuration) =
        Iterator
          .continually(w.next(within))
          .takeWhile(_.nonEmpty)
          .flatten
          .find {
            case Right(WatchEvent.Row(k, _))  => mine(k)
            case Right(WatchEvent.Removed(k)) => mine(k)
            case _                            => true
          }
      assertEquals(mineNext(1.second), None)
      cart(key).call(CartEntity.touch).invoke(Done): Unit
      ofKind(w, key): Unit
  )

  // ── Refused at start ──────────────────────────────────────────────────────

  Given("{string} declares the query {string} with a statement that counts its rows")(
    (_: String, query: String) => assertEquals(query, Carts.cartCount.name)
  )

  Given(
    "{string} declares the query {string} with a statement that reads its own table for the newest {string} rows"
  )((_: String, query: String, _: String) => assertEquals(query, Carts.newestCarts.name))

  Given("a handler of {string} that watches {string} and a handler that watches {string}")(
    (_: String, _: String, _: String) => ()
  )

  When("{string} is started")((_: String) =>
    val service = com.thinkmorestupidless.ankka.runtime.Ankka.service.registerAll(
      Seq(
        CartEntity.descriptor,
        cartsWatching(
          Carts.cartCount.name   -> Carts.cartCount.statement,
          Carts.newestCarts.name -> Carts.newestCarts.statement
        )
      )
    )
    started = Some(scala.util.Try(service.start(s"refused-$scope", kit.serviceConfig)))
    started.foreach(_.foreach {
      case running: com.thinkmorestupidless.ankka.runtime.AnkkaService => running.terminate()
      case _                                                           => ()
    })
  )

  Then("{string} does not start")((_: String) =>
    assert(started.exists(_.isFailure), s"it started: $started")
  )

  Then("the developer is told that a watched query gives the view's rows and has no limit")(() =>
    val problem = started.flatMap(_.failed.toOption).map(_.getMessage).getOrElse("")
    assert(
      problem.contains("'cart-count', which aggregates; a watched query gives the view's rows"),
      problem
    )
    assert(problem.contains("'newest-carts', which has a limit"), problem)
  )

  Then("{string} and {string} can still be asked whole")((_: String, _: String) =>
    assertEquals(carts.ask(Carts.cartCount).map(_.cartId), Vector("count"))
    carts.ask(Carts.newestCarts): Unit
  )

/** The parts of the topology document a watch-rules scenario reads. */
final case class TopologyHandled(ok: Long, failed: Long)
final case class TopologyPair(
    caller: String,
    callee: String,
    handled: TopologyHandled,
    streaming: Boolean
)
final case class TopologyCall(from: String, to: String, pairs: Vector[TopologyPair])
final case class TopologyDocument(calls: Vector[TopologyCall])

object TopologyDocument:
  given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[TopologyDocument] =
    JsonCodecMaker.make
