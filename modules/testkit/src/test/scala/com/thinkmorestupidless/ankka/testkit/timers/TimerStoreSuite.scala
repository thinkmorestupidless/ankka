package com.thinkmorestupidless.ankka.testkit.timers

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, MethodName}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  DatabaseTimerScheduler,
  FiredTimer,
  Observability,
  SqlFragment,
  Sweep,
  TimerObserver,
  TimerStore
}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.sdk.DeferredCall
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, OrderEntity, OrderTimers}
import org.apache.pekko.actor.typed.ActorSystem

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * The timer table's statements, one by one, against the real schema — and against the previous
 * release's schema and statements, which a runtime from before recurring timers still runs.
 *
 * No timer runtime is registered, so no sweeper touches a row: every change to the table here is
 * one this suite made, and what it asserts is what the statement did.
 */
class TimerStoreSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Seq(OrderEntity.descriptor, OrderTimers.descriptor))

  override def afterAll(): Unit = if kit != null then kit.stop()

  // Each case starts from the table as the platform's schema makes it; a case about an older
  // schema replaces it with that one.
  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    run(SqlFragment.raw("DROP TABLE IF EXISTS ankka_timers")): Unit
    currentSchema.foreach(run(_): Unit)

  /** The statements of `30-timers-postgres.sql` as this branch ships it, comments removed. */
  private lazy val currentSchema: Vector[SqlFragment] =
    val text = scala.io.Source
      .fromInputStream(getClass.getResourceAsStream("/ankka/ddl/30-timers-postgres.sql"))
      .mkString
    text.linesIterator
      .filterNot(_.trim.startsWith("--"))
      .mkString("\n")
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(SqlFragment.raw)
      .toVector

  private def legacySchema(): Unit =
    run(SqlFragment.raw("DROP TABLE IF EXISTS ankka_timers")): Unit
    LegacyTimers.createTable.foreach(run(_): Unit)

  private def database: Database =
    given ActorSystem[?] = kit.service.system
    Database()

  private def run(fragment: SqlFragment): Long = await(database.execute(fragment))

  private def await[A](future: Future[A]): A = Await.result(future, 10.seconds)

  private val call =
    DeferredCall(ComponentId("order-timers"), MethodName("expire-order"), Array[Byte](1))

  private def column[A](name: String, col: String, as: Class[A]): Option[A] =
    await(
      database.queryOne(
        SqlFragment.raw(s"SELECT $col FROM ankka_timers WHERE timer_name = ") ++ sql"$name"
      )(row => Option(row.get(col, as)))
    ).flatten

  private def dueAt(name: String): Option[Instant]  = column(name, "due_at", classOf[Instant])
  private def dueFor(name: String): Option[Instant] = column(name, "due_for", classOf[Instant])
  private def fireAt(name: String): Option[Instant] = column(name, "fire_at", classOf[Instant])
  private def attempts(name: String): Option[Int] =
    column(name, "attempts", classOf[Integer]).map(_.intValue)

  private def isRecurring(name: String): Boolean =
    await(
      database.queryOne(
        sql"SELECT due_at = 'infinity'::timestamptz AS r FROM ankka_timers WHERE timer_name = $name"
      )(_.get("r", classOf[java.lang.Boolean]).booleanValue)
    ).getOrElse(fail(s"no row for '$name'"))

  private def schedule(name: String, due: Instant): Unit =
    run(TimerStore.upsert(name, call, due)): Unit

  private def readDue(name: String): Instant =
    dueAt(name).getOrElse(fail(s"no row for '$name'"))

  private def firstDue: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(60)

  private def recurring(
      name: String,
      period: Long = 2_000L,
      first: Instant = firstDue,
      target: DeferredCall = call
  ): Unit =
    run(TimerStore.upsertRecurring(name, target, period, first)): Unit

  private def dueNames(now: Instant = Instant.now()): Vector[(String, Option[Instant])] =
    await(
      database.query(TimerStore.due(now, 10))(row =>
        row.get("timer_name", classOf[String]) -> Option(row.get("due_for", classOf[Instant]))
      )
    )

  // ── the sweeper changes only the row it read ──────────────────────────────────

  test("a due time read from the table and bound back matches its row") {
    schedule("t", Instant.now().plusSeconds(60))
    val read = readDue("t")
    val matched = await(
      database.query(
        sql"SELECT timer_name FROM ankka_timers WHERE timer_name = ${"t"} AND due_at = $read"
      )(_.get("timer_name", classOf[String]))
    )
    assertEquals(matched, Vector("t"))
  }

  test("a delete matched on the due time read removes the row it read") {
    schedule("t", Instant.now().plusSeconds(60))
    assertEquals(run(TimerStore.deleteRan("t", readDue("t"))), 1L)
    assertEquals(dueAt("t"), None)
  }

  test("a delete matched on the due time read leaves a row rewritten since it was read") {
    schedule("t", Instant.now().plusSeconds(60))
    val read = readDue("t")
    schedule("t", Instant.now().plusSeconds(120)) // what a handler setting its own timer again does
    assertEquals(run(TimerStore.deleteRan("t", read)), 0L)
    assert(dueAt("t").isDefined, "the rewritten row was deleted")
  }

  test("a backoff matched on the due time read leaves a row rewritten since it was read") {
    schedule("t", Instant.now().plusSeconds(60))
    val read = readDue("t")
    val next = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MILLIS)
    schedule("t", next)
    assertEquals(run(TimerStore.reschedule("t", 0, read, read)), 0L)
    assertEquals(readDue("t"), next)
    assertEquals(attempts("t"), Some(0))
  }

  test("a backoff matched on the due time read backs off the row it read") {
    schedule("t", Instant.now().minusSeconds(1))
    val read = readDue("t")
    assertEquals(run(TimerStore.reschedule("t", 0, read, read)), 1L)
    assertEquals(attempts("t"), Some(1))
    assert(readDue("t").isAfter(Instant.now()), "the backoff did not move the due time on")
  }

  test("two matched deletes of one row have one effect") {
    schedule("t", Instant.now().plusSeconds(60))
    val read = readDue("t")
    assertEquals(run(TimerStore.deleteRan("t", read)) + run(TimerStore.deleteRan("t", read)), 1L)
  }

  // ── what the driver does with the new columns ──────────────────────────────────

  test("a recurring row is read without the driver being asked to decode 'infinity'") {
    run(
      SqlFragment.raw(
        "INSERT INTO ankka_timers (timer_name, component_id, method, payload, due_at, " +
          "period_millis, fire_at, due_for) " +
          "VALUES ('r', 'c', 'm', ''::bytea, 'infinity', 2000, now(), now())"
      )
    ): Unit
    val read = await(
      database.query(TimerStore.scheduled("r"))(row =>
        Option(row.get("due_at", classOf[Instant])) ->
          Option(row.get("period_millis", classOf[java.lang.Long])).map(_.longValue)
      )
    )
    assertEquals(read, Vector(None -> Some(2_000L)))
  }

  test("a period binds as a Long and reads back; the new columns of a one-shot read as absent") {
    recurring("r")
    assertEquals(
      column("r", "period_millis", classOf[java.lang.Long]).map(_.longValue),
      Some(2_000L)
    )
    schedule("o", Instant.now().plusSeconds(60))
    assertEquals(column("o", "period_millis", classOf[java.lang.Long]), None)
    assertEquals(fireAt("o"), None)
  }

  // ── the statements of the data model ───────────────────────────────────────────

  test(
    "a timer that fires once keeps its due time as the due it is for, over a row of either kind"
  ) {
    val due = firstDue
    schedule("t", due)
    assertEquals(dueFor("t"), Some(due))
    recurring("t")
    schedule("t", due.plusSeconds(1))
    assertEquals(dueFor("t"), Some(due.plusSeconds(1)))
    assert(!isRecurring("t"))
    assertEquals(column("t", "period_millis", classOf[java.lang.Long]), None)
    assertEquals(fireAt("t"), None)
  }

  test(
    "a recurring timer set again unchanged keeps when it fires and its attempts, and takes the payload"
  ) {
    val first = firstDue
    recurring("r", first = first)
    run(SqlFragment.raw("UPDATE ankka_timers SET attempts = 2 WHERE timer_name = 'r'")): Unit
    val again = DeferredCall(call.componentId, call.method, Array[Byte](9))
    recurring("r", first = first.plusSeconds(500), target = again)
    assertEquals(dueFor("r"), Some(first))
    assertEquals(fireAt("r"), Some(first))
    assertEquals(attempts("r"), Some(2))
    assertEquals(column("r", "payload", classOf[Array[Byte]]).map(_.toSeq), Some(Seq[Byte](9)))
  }

  test(
    "a recurring timer set again with another period, another handler, or over a one-shot, is replaced"
  ) {
    val first = firstDue
    val later = first.plusSeconds(500)
    def replacedBy(set: => Unit): Unit =
      run(SqlFragment.raw("UPDATE ankka_timers SET attempts = 2 WHERE timer_name = 'r'")): Unit
      set
      assertEquals(dueFor("r"), Some(later))
      assertEquals(fireAt("r"), Some(later))
      assertEquals(attempts("r"), Some(0))
      assert(isRecurring("r"))
    recurring("r", first = first)
    replacedBy(recurring("r", period = 3_000L, first = later))
    recurring("r", first = first)
    replacedBy(
      recurring(
        "r",
        first = later,
        target = DeferredCall(call.componentId, MethodName("other"), Array.emptyByteArray)
      )
    )
    schedule("r", first)
    replacedBy(recurring("r", first = later))
  }

  test("two instances setting one recurring timer at once leave one row, as one of them set it") {
    given ExecutionContext = ExecutionContext.global
    for round <- 1 to 20 do
      run(TimerStore.delete("r")): Unit
      val a = firstDue
      val b = a.plusSeconds(7)
      val both = Future.sequence(
        Seq(
          database.execute(TimerStore.upsertRecurring("r", call, 2_000L, a)),
          database.execute(TimerStore.upsertRecurring("r", call, 2_000L, b))
        )
      )
      await(both): Unit
      val kept = dueFor("r").getOrElse(fail(s"round $round left no row"))
      assert(kept == a || kept == b, s"round $round kept $kept")
      assertEquals(fireAt("r"), Some(kept))
  }

  test(
    "the due query answers overdue timers of both kinds, oldest first, and not one still to come"
  ) {
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    schedule("once", now.minusSeconds(3))
    recurring("every", first = now.minusSeconds(5))
    recurring("later", first = now.plusSeconds(60))
    assertEquals(dueNames().map(_._1), Vector("every", "once"))
    assertEquals(dueNames().head._2, Some(now.minusSeconds(5)))
  }

  test("the previous release's due query answers a one-shot and never a recurring timer") {
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    schedule("once", now.minusSeconds(3))
    recurring("every", first = now.minusSeconds(5))
    def legacyDue = await(
      database.query(LegacyTimers.Store.due(Instant.now(), 100))(
        _.get("timer_name", classOf[String])
      )
    )
    assertEquals(legacyDue, Vector("once"))
    // What makes this a check: once the recurring row's due_at is finite, the old query sees it.
    run(
      SqlFragment.raw(
        "UPDATE ankka_timers SET due_at = now() - interval '5 seconds' WHERE timer_name = 'every'"
      )
    ): Unit
    assertEquals(legacyDue.toSet, Set("once", "every"))
  }

  test(
    "the previous release sees a recurring timer, replaces one with a one-shot, and cancels one"
  ) {
    recurring("r")
    assertEquals(
      await(database.query(LegacyTimers.Store.byName("r"))(_.get("timer_name", classOf[String]))),
      Vector("r")
    )
    run(LegacyTimers.Store.upsert("r", call, Instant.now().minusSeconds(1))): Unit
    assert(!isRecurring("r"), "the old upsert left the row recurring")
    assertEquals(dueNames().map(_._1), Vector("r"))
    recurring("r2")
    run(LegacyTimers.Store.delete("r2")): Unit
    assertEquals(dueFor("r2"), None)
  }

  test("an advance and a recurring backoff are no-ops on a row changed since it was read") {
    val first = firstDue
    val next  = first.plusSeconds(2)
    // cancelled
    recurring("r", first = first)
    run(TimerStore.delete("r")): Unit
    assertEquals(run(TimerStore.advance("r", first, 2_000L, next)), 0L)
    // replaced by a one-shot
    schedule("r", first)
    assertEquals(run(TimerStore.advance("r", first, 2_000L, next)), 0L)
    assertEquals(run(TimerStore.rescheduleRecurring("r", 0, first, 2_000L)), 0L)
    // given another period
    run(TimerStore.delete("r")): Unit
    recurring("r", first = first)
    recurring("r", period = 3_000L, first = first)
    assertEquals(run(TimerStore.advance("r", first, 2_000L, next)), 0L)
    // unchanged: one advance takes, a second (another sweeper, in a handoff) does not
    run(TimerStore.delete("r")): Unit
    recurring("r", first = first)
    val twice = run(TimerStore.advance("r", first, 2_000L, next)) +
      run(TimerStore.advance("r", first, 2_000L, next))
    assertEquals(twice, 1L)
    assertEquals(dueFor("r"), Some(next))
    assertEquals(fireAt("r"), Some(next))
  }

  test(
    "a recurring backoff moves when it runs and raises its attempts, and keeps the due it is for"
  ) {
    val first = firstDue
    recurring("r", first = first)
    assertEquals(run(TimerStore.rescheduleRecurring("r", 0, first, 2_000L)), 1L)
    assertEquals(attempts("r"), Some(1))
    assertEquals(dueFor("r"), Some(first))
    val retry = fireAt("r").get
    assert(
      retry.isAfter(Instant.now().plusSeconds(2)) && retry.isBefore(Instant.now().plusSeconds(4)),
      s"the retry is at $retry"
    )
    // A success forgets the failures.
    assertEquals(run(TimerStore.advance("r", first, 2_000L, first.plusSeconds(2))), 1L)
    assertEquals(attempts("r"), Some(0))
  }

  test("a deferral looks at a recurring timer again later and changes nothing else") {
    val first = firstDue
    recurring("r", first = first)
    run(SqlFragment.raw("UPDATE ankka_timers SET attempts = 1 WHERE timer_name = 'r'")): Unit
    assertEquals(run(TimerStore.defer("r", first, 2_000L)), 1L)
    assertEquals(attempts("r"), Some(1))
    assertEquals(dueFor("r"), Some(first))
    val later = fireAt("r").get
    assert(later.isAfter(Instant.now().plusSeconds(TimerStore.DeferSeconds - 2)), s"$later")
    run(TimerStore.delete("r")): Unit
    assertEquals(run(TimerStore.defer("r", first, 2_000L)), 0L)
  }

  test("a one-shot's backoff keeps the due it was for") {
    val due = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(1)
    schedule("t", due)
    assertEquals(run(TimerStore.reschedule("t", 0, readDue("t"), due)), 1L)
    assertEquals(dueFor("t"), Some(due))
    assert(readDue("t").isAfter(Instant.now()))
  }

  // ── the schema change, on a database the previous release made ─────────────────

  test("the schema applies to the previous release's table, twice, while its sweeper polls") {
    legacySchema()
    run(LegacyTimers.Store.upsert("old", call, Instant.now().minusSeconds(1))): Unit
    @volatile var polling                    = true
    @volatile var failure: Option[Throwable] = None
    val poller = Thread.ofVirtual().start { () =>
      while polling do
        try
          await(
            database.query(LegacyTimers.Store.due(Instant.now(), 100))(
              _.get("timer_name", classOf[String])
            )
          ): Unit
        catch
          case e: Throwable =>
            failure = Some(e)
            polling = false
    }
    try
      val started = System.nanoTime()
      currentSchema.foreach(run(_): Unit)
      currentSchema.foreach(run(_): Unit)
      assert((System.nanoTime() - started).nanos < 10.seconds, "applying the schema stalled")
    finally
      polling = false
      poller.join()
    failure.foreach(e => fail("the old sweeper's query failed while the schema was applied", e))
    assertEquals(dueFor("old"), None) // a row the old runtime wrote has no due_for, and keeps none
    assertEquals(dueNames().map(_._1), Vector("old"))
  }

  test(
    "on the previous release's table, unaltered, a recurring timer is refused for want of a column"
  ) {
    legacySchema()
    val refused = intercept[io.r2dbc.spi.R2dbcException](
      await(database.execute(TimerStore.upsertRecurring("r", call, 2_000L, Instant.now())))
    )
    assertEquals(refused.getSqlState, "42703")
  }

  test("the scheduler says what to do about a table from before recurring timers, not SQL") {
    legacySchema()
    val scheduler = DatabaseTimerScheduler(database)
    val refused = intercept[CommandError](
      scheduler.createRecurringTimer("r", Duration.Zero, 2.seconds, call)
    )
    assert(refused.message.contains("30-timers-postgres.sql"), refused.message)
    assert(refused.message.contains("recreate a local database"), refused.message)
  }

  test("the scheduler refuses a period out of bounds before anything is stored") {
    val scheduler = DatabaseTimerScheduler(database)
    val refused = intercept[IllegalArgumentException](
      scheduler.createRecurringTimer("sweep-carts", 1.second, Duration.Zero, call)
    )
    assert(refused.getMessage.contains("sweep-carts"), refused.getMessage)
    assertEquals(dueFor("sweep-carts"), None)
  }

  test("a delay of zero or less is due at once, for either kind, in whole milliseconds") {
    val scheduler = DatabaseTimerScheduler(database)
    val before    = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    scheduler.createSingleTimer("once", (-5).seconds, call)
    scheduler.createRecurringTimer("every", (-5).seconds, 2.seconds, call)
    val after = Instant.now()
    for due <- Seq(dueFor("once").get, dueFor("every").get) do
      assert(!due.isBefore(before) && !due.isAfter(after), s"$due is not now")
      assertEquals(due, due.truncatedTo(ChronoUnit.MILLIS))
  }

  // ── a local database whose timers table predates recurring timers ──────────────
  //
  // Postgres applies a schema only to a new volume, so a developer's local database keeps the
  // previous release's table until the file is applied. Timers that fire once go on working there;
  // only a recurring timer needs the new columns.

  /** One batch of this release's sweeper over the kit's database, with what it ran recorded. */
  private def sweepOnce(): Vector[FiredTimer] =
    val service = kit.service
    val fired   = java.util.concurrent.ConcurrentLinkedQueue[FiredTimer]()
    val sweep = Sweep(
      database,
      Map(OrderTimers.componentId -> OrderTimers.descriptor),
      service.componentClient,
      service.secrets,
      service.services,
      None,
      service.system.executionContext,
      Observability(service.system),
      (timer => fired.add(timer): Unit): TimerObserver
    )
    await(sweep.runBatch()): Unit
    scala.jdk.CollectionConverters.ListHasAsScala(java.util.List.copyOf(fired)).asScala.toVector

  test("on the previous release's table, a timer that fires once is still set, run and removed") {
    legacySchema()
    val scheduler = DatabaseTimerScheduler(database)
    scheduler.createSingleTimer("once", Duration.Zero, OrderTimers.expireOrder.deferred("o-legacy"))
    assert(scheduler.exists("once"), "the timer was not set")
    val fired = sweepOnce()
    assertEquals(fired.map(f => f.name -> f.outcome), Vector("once" -> FiredTimer.Outcome.Done))
    assert(!scheduler.exists("once"), "the timer was not removed after it ran")
  }

  test(
    "on the previous release's table, a timer that fires once and fails is backed off, not lost"
  ) {
    legacySchema()
    val scheduler = DatabaseTimerScheduler(database)
    scheduler.createSingleTimer(
      "flaky",
      Duration.Zero,
      OrderTimers.alwaysFails.deferred("o-legacy")
    )
    val fired = sweepOnce()
    assertEquals(fired.map(_.outcome), Vector(FiredTimer.Outcome.Failed))
    assertEquals(attempts("flaky"), Some(1))
    assert(readDue("flaky").isAfter(Instant.now()), "the failed timer was not moved on")
  }

  test("on the previous release's table, a recurring timer is still refused, naming the remedy") {
    legacySchema()
    val refused = intercept[CommandError](
      DatabaseTimerScheduler(database).createRecurringTimer("r", Duration.Zero, 2.seconds, call)
    )
    assert(refused.message.contains("30-timers-postgres.sql"), refused.message)
  }
