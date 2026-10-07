package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{
  Ankka,
  AnkkaService,
  Database,
  ProjectionRuntime,
  SqlFragment,
  ViewQueries
}
import com.thinkmorestupidless.ankka.sdk.DeclaredQuery
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

/**
 * The steps `features/views/declared-queries.feature` and `recursive-queries.feature` share, run
 * against a real service on a real database.
 *
 * One service serves every scenario of a file: the tree of `ViewsKit`, with the declared queries
 * the scenarios name. Each scenario's keys and values are its own — every quoted value is prefixed
 * with the scenario's number before it reaches the service, and taken off what comes back — so no
 * scenario reads another's rows. A scenario about a service that must not start brings one up
 * beside it, on the same database, declaring the statement the scenario names.
 *
 * The database logs every statement it is sent, which is how "the database is sent nothing" and
 * "was sent one statement" are read: off the database, not argued from the code.
 */
abstract class QuerySteps(feature: String) extends GherkinSuite(feature) with LogCapturing:

  override val munitTimeout: Duration = 5.minutes

  private given ExecutionContext = ExecutionContext.global

  private var kit: AnkkaTestKit = null
  private val scenarios         = AtomicInteger()

  // What the scenario has said and seen.
  private var scope                                = ""
  private var declared: Option[(String, String)]   = None
  private var started: Option[Try[AnkkaService]]   = None
  private var answer: Option[Try[Vector[NodeRow]]] = None
  private var took: FiniteDuration                 = Duration.Zero
  private var loggedBefore                         = 0
  private var asked: Option[String]                = None

  override def beforeAll(): Unit =
    super.beforeAll()
    kit = AnkkaTestKit.start(
      Seq(NodeEntity.descriptor, Nodes.descriptor, Accounts.descriptor),
      Seq(ProjectionRuntime())
    )
    kit.logStatements()

  override def afterAll(): Unit =
    if kit != null then kit.stop()
    super.afterAll()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    scope = s"s${scenarios.incrementAndGet()}"
    declared = None
    started.foreach(_.foreach(_.terminate()))
    started = None
    answer = None
    asked = None

  private def scoped(value: String): String   = s"$scope-$value"
  private def unscoped(value: String): String = value.stripPrefix(s"$scope-")

  private def nodes = kit.service.viewClient.forView(Nodes)

  private def place(key: String, under: Option[String], kind: String = "node"): Unit =
    kit.componentClient
      .forEventSourcedEntity(EntityId(scoped(key)))
      .call(NodeEntity.place)
      .invoke(Node(under.map(scoped), scoped(kind))): Unit

  /** Waits until the view holds the scenario's rows: the view trails its entity. */
  private def projected(keys: Seq[String]): Unit =
    val wanted = keys.map(scoped).toSet
    kit.eventually(s"the rows ${keys.mkString(", ")}")(
      Option.when(wanted.forall(key => nodes.get(key).isDefined))(())
    )

  private def logged(text: String): Int =
    kit.databaseLog.sliding(text.length).count(_ == text)

  private def ask(query: DeclaredQuery, values: (String, String)*): Unit =
    asked = Some(query.name)
    loggedBefore = logged(query.statement.linesIterator.map(_.trim).find(_.nonEmpty).get)
    val began = System.nanoTime()
    answer = Some(Try(nodes.ask(query, values*)))
    took = (System.nanoTime() - began).nanos

  private def rows: Vector[NodeRow] =
    answer
      .getOrElse(fail("nothing was asked"))
      .fold(failure => fail(s"refused: $failure"), identity)

  private def refusal: CommandError =
    answer.getOrElse(fail("nothing was asked")).failed.toOption match
      case Some(error: CommandError) => error
      case other                     => fail(s"expected a refusal, got $other and $answer")

  private def answeredWith(keys: String*): Unit =
    assertEquals(rows.map(r => unscoped(r.key)).sorted, keys.toVector.sorted)

  /** The statement a scenario says `nodes` declares, as `ViewsKit.Refused` holds them. */
  private def refusedStatement(does: String): Unit = declared = Some(
    "broken" -> Refused.statements.find(_._1 == does).getOrElse(fail(s"no statement that $does"))._2
  )

  // ── The service ───────────────────────────────────────────────────────────

  Given("a service {string} with an event sourced entity {string}")((_: String, _: String) => ())
  Given("a view {string} that reads the events of {string} and keeps a row for each entity of it")(
    (_: String, _: String) => ()
  )
  Given("a view {string} of {string}")((_: String, _: String) => ())
  Given("each row of {string} holds the row key of the row it is under, or none")((_: String) => ())

  // ── What `nodes` declares ─────────────────────────────────────────────────

  Given(
    "{string} declares the query {string} with a statement that reads its own table for the rows holding the value {string}"
  )((_: String, query: String, _: String) => assertEquals(query, Nodes.ofKind.name))
  Given(
    "{string} declares the recursive query {string} that takes the value {string} and reads every row under it"
  )((_: String, query: String, _: String) => assertEquals(query, Nodes.under.name))
  Given("{string} declares a recursive query {string} that never ends")(
    (_: String, query: String) => assertEquals(query, Nodes.around.name)
  )
  Given(
    "{string} declares the query {string} with a statement that reads its own table and names another table only in a comment and in a value"
  )((_: String, query: String) => assertEquals(query, Nodes.noted.name))
  Given("{string} declares the query {string} with a statement that writes a row")(
    (_: String, _: String) => refusedStatement("writes a row")
  )
  Given("{string} declares the query {string} with a statement that deletes a row")(
    (_: String, _: String) => refusedStatement("deletes a row")
  )
  Given(
    "{string} declares the query {string} with a statement that holds a second statement after the query"
  )((_: String, _: String) => refusedStatement("holds a second statement after the query"))
  Given(
    "{string} declares the query {string} with a statement that reads a table that is no view's"
  )((_: String, _: String) => refusedStatement("reads a table that is no view's"))
  Given("{string} declares the query {string} with a statement that reads the table of {string}")(
    (_: String, _: String, _: String) => refusedStatement("reads the table of accounts")
  )

  // ── What `nodes` holds ────────────────────────────────────────────────────

  // A state: established before anything is asked, and checked after.
  Given("{string} holds the rows {string} and {string}")((_: String, a: String, b: String) =>
    if answer.isEmpty then
      place(a, None)
      place(b, None)
      projected(Seq(a, b))
    else assert(nodes.get(scoped(a)).isDefined && nodes.get(scoped(b)).isDefined)
  )
  Given("{string} holds the rows {string} and {string}, each holding {string}")(
    (_: String, a: String, b: String, kind: String) =>
      place(a, None, kind)
      place(b, None, kind)
      projected(Seq(a, b))
  )
  Given(
    "{string} holds the rows {string} and {string}, each holding {string}, and the row {string} holding {string}"
  )((_: String, a: String, b: String, kind: String, c: String, other: String) =>
    place(a, None, kind)
    place(b, None, kind)
    place(c, None, other)
    projected(Seq(a, b, c))
  )
  Given("{string} holds these rows")((_: String, table: Seq[Seq[String]]) =>
    val header = table.head
    val keyAt  = header.indexOf("row key")
    val under  = header.indexOf("under")
    val body   = table.tail
    body.foreach(row => place(row(keyAt), Option(row(under)).filter(_.nonEmpty)))
    projected(body.map(_(keyAt)))
  )
  Given("{string} holds {string} rows, the row {string} and {string} rows under it, {string} deep")(
    (_: String, total: String, root: String, below: String, deep: String) =>
      assertEquals(total.toInt, below.toInt + 1)
      // `deep` levels under the root, as even as they divide: each node is under the one `width`
      // before it, so every level holds `width` nodes and the last is `deep` below the root.
      val width = below.toInt / deep.toInt
      place(root, None)
      val keys = (1 to below.toInt).map(i => s"n$i")
      val placing = keys.zipWithIndex.map { (key, i) =>
        scala.concurrent.Future(place(key, Some(if i < width then root else keys(i - width))))
      }
      Await.result(scala.concurrent.Future.sequence(placing), 2.minutes)
      kit.eventually(s"$total rows", 2.minutes)(
        Option.when(
          nodes
            .ask(Nodes.under, ViewQueries.DefaultLimit + 1, "row" -> scoped(root))
            .size == below.toInt
        )(())
      )
  )

  // ── Asking ────────────────────────────────────────────────────────────────

  When("a handler of {string} asks {string} the query {string} with {string} as {string}")(
    (_: String, _: String, query: String, value: String, name: String) =>
      val declaredQuery = Seq(Nodes.under, Nodes.ofKind).find(_.name == query).get
      ask(declaredQuery, name -> scoped(value))
  )
  When(
    "a handler of {string} asks {string} the query {string} with a statement that deletes every row as {string}"
  )((_: String, _: String, query: String, name: String) =>
    assertEquals(query, Nodes.ofKind.name)
    ask(Nodes.ofKind, name -> s"x'; DELETE FROM ${Nodes.table}; --")
  )
  When("a handler of {string} asks {string} the query {string} with no value")(
    (_: String, _: String, query: String) =>
      assertEquals(query, Nodes.ofKind.name)
      ask(Nodes.ofKind)
  )
  When("a handler of {string} asks {string} the query {string}")(
    (_: String, _: String, query: String) =>
      if query == Nodes.around.name then ask(Nodes.around)
      else
        asked = Some(query)
        answer = Some(
          Try(Await.result(nodes.askNamed(query, Map.empty, ViewQueries.DefaultLimit), 30.seconds))
        )
  )
  Then("a handler of {string} that asks {string} the query {string} is answered with rows")(
    (_: String, _: String, query: String) =>
      assertEquals(query, Nodes.noted.name)
      place("noted", None)
      projected(Seq("noted"))
      assert(nodes.ask(Nodes.noted).nonEmpty)
  )

  // ── Starting ──────────────────────────────────────────────────────────────

  When("{string} is started")((_: String) =>
    declared match
      case None => started = Some(Try(kit.service))
      case Some((name, statement)) =>
        val service = Ankka.service.registerAll(
          Seq(NodeEntity.descriptor, nodesDeclaring(name, statement), Accounts.descriptor)
        )
        started = Some(Try(service.start(s"refused-$scope", kit.serviceConfig)))
  )
  Then("{string} does not start")((_: String) =>
    assert(started.exists(_.isFailure), s"it started: $started")
  )
  Then("{string} starts")((_: String) => assert(started.exists(_.isSuccess), s"$started"))

  private def problem: String =
    started.flatMap(_.failed.toOption).map(_.getMessage).getOrElse(fail("nothing failed to start"))

  Then("the developer is told that the query {string} of {string} is refused, and why")(
    (query: String, view: String) =>
      assert(problem.contains(s"'$query'") && problem.contains(s"'$view'"), problem)
      assert(problem.contains("which"), problem)
  )
  Then("the developer is told that the query {string} of {string} reads the table of {string}")(
    (query: String, view: String, other: String) =>
      assert(problem.contains(s"'$query'") && problem.contains(s"'$view'"), problem)
      assert(problem.contains(s"ankka_view_$other"), problem)
  )
  Then("the database is never sent the statement")(() =>
    val statement = declared.getOrElse(fail("no statement was declared"))._2
    val marker    = statement.split("'").find(_.startsWith("sc002-")).get
    assertEquals(logged(marker), 0, s"the database was sent $statement")
  )

  // ── Answers ───────────────────────────────────────────────────────────────

  Then("the handler is answered with the rows {string} and {string} and no other")(
    (a: String, b: String) => answeredWith(a, b)
  )
  Then("the handler is answered with the rows {string}, {string} and {string} and no other")(
    (a: String, b: String, c: String) => answeredWith(a, b, c)
  )
  Then("the handler is answered with no rows")(() => assertEquals(rows, Vector.empty))
  Then("the handler is answered with {string} rows")((count: String) =>
    assertEquals(rows.size, count.toInt)
  )
  Then("the answer arrives within the time a query of a view is given")(() =>
    val allowed =
      kit.service.system.settings.config.getDuration("ankka.ask-timeout").toMillis.millis
    assert(took < allowed, s"took $took, and a query is given $allowed")
  )
  Then("the database was sent one statement")(() =>
    val first = Nodes.under.statement.linesIterator.map(_.trim).find(_.nonEmpty).get
    kit.eventually("the statement in the database's log")(
      Option.when(logged(first) > loggedBefore)(())
    )
    Thread.sleep(300)
    assertEquals(logged(first) - loggedBefore, 1)
  )
  Then("the handler is refused")(() => refusal: Unit)
  Then("the refusal names {string} and {string}")((view: String, query: String) =>
    assert(
      refusal.message.contains(s"'$view'") && refusal.message.contains(s"'$query'"),
      refusal.message
    )
  )
  Then("the refusal names the value {string}")((value: String) =>
    assert(refusal.message.contains(s"'$value'"), refusal.message)
  )
  Then("the database is sent nothing")(() =>
    asked.foreach(name => assert(!kit.databaseLog.contains(name) || name == "of-kind", name))
    assertEquals(refusal.code == ErrorCode.NotFound || refusal.code == ErrorCode.BadRequest, true)
  )
  Then("the handler is told that the time a query of a view is given ran out")(() =>
    assertEquals(refusal.code, ErrorCode.Timeout, refusal.message)
  )
  Then("the database is no longer running the statement")(() =>
    val database = Database()(using kit.service.system)
    val running = Await.result(
      database.query(
        SqlFragment.raw(
          "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND " +
            "pid <> pg_backend_pid() AND query LIKE '%/* around */%'"
        )
      )(_.get(0, classOf[java.lang.Long]).longValue),
      10.seconds
    )
    assertEquals(running, Vector(0L))
  )

/** `features/views/declared-queries.feature`, run as it is written. */
final class DeclaredQueriesFeatures
    extends QuerySteps("../../features/views/declared-queries.feature")

/** `features/views/recursive-queries.feature`, run as it is written. */
final class RecursiveQueriesFeatures
    extends QuerySteps("../../features/views/recursive-queries.feature")
