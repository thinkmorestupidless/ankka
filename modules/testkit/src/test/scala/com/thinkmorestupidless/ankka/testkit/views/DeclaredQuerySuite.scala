package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{Ankka, Database, ProjectionRuntime, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * What a declared query does against a real database that a feature cannot say:
 * `contracts/declared-queries.md`, the call (C2–C7), and the two claims of research R8 — the
 * database holds a declared query to reading, and ends one that does not end.
 *
 * The database logs every statement it is sent, so "the database was sent nothing" is read off the
 * database's own log rather than argued from the code: a refused statement carries a marker, and
 * the marker must be absent. A control statement carrying a marker of its own is asked first, and
 * found, so the log is shown to be able to see a statement at all.
 */
class DeclaredQuerySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(NodeEntity.descriptor, Nodes.descriptor, Accounts.descriptor),
      Seq(ProjectionRuntime())
    )
    kit.logStatements()
    val database = Database()(using kit.service.system)
    Await.result(
      database.execute(SqlFragment.raw("CREATE SEQUENCE ankka_test_sequence")),
      10.seconds
    )
    Seq("a" -> None, "b" -> Some("a"), "c" -> Some("b"), "d" -> Some("b"), "e" -> None)
      .foreach((key, under) => place(key, under, if under.isEmpty then "team" else "person"))
    rows("five rows")(_.size == 5): Unit

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def place(key: String, under: Option[String], kind: String): Unit =
    kit.componentClient
      .forEventSourcedEntity(EntityId(key))
      .call(NodeEntity.place)
      .invoke(Node(under, kind)): Unit

  private def nodes = kit.service.viewClient.forView(Nodes)

  private def rows(what: String)(ready: Vector[NodeRow] => Boolean): Vector[NodeRow] =
    kit.eventually(what)(Some(nodes.all()).filter(ready))

  private def refusal(asked: => Any): CommandError =
    Try(asked).failed.get match
      case error: CommandError => error
      case other               => fail(s"expected a refusal, got $other")

  private def logged(marker: String): Int =
    kit.databaseLog.sliding(marker.length).count(_ == marker)

  test("the database log sees a statement the service sends") {
    val before = logged("sc002-control")
    assertEquals(nodes.ask(Nodes.control, "kind" -> "team").map(_.key).sorted, Vector("a", "e"))
    kit.eventually("the control statement in the database's log")(
      Option.when(logged("sc002-control") > before)(())
    )
  }

  test(
    "SC-002: a service declaring a refused statement does not start, and the database is sent none of it"
  ) {
    for (what, statement) <- Refused.statements do
      val marker = statement.split("'").find(_.startsWith("sc002-")).get
      val service = Ankka.service.registerAll(
        Seq(NodeEntity.descriptor, nodesDeclaring("broken", statement), Accounts.descriptor)
      )
      val started = Try(service.start("refused", kit.serviceConfig))
      started.foreach(_.terminate())
      assert(started.isFailure, s"a service declaring a statement that $what started")
      val problem = started.failed.get.getMessage
      assert(problem.contains("'broken'") && problem.contains("'nodes'"), problem)
      assertEquals(logged(marker), 0, s"the statement that $what reached the database")
  }

  test("R8: the database refuses a declared query that writes, whatever the statement looks like") {
    val error = refusal(nodes.ask(Nodes.writes))
    assertEquals(error.code, ErrorCode.Internal)
    assert(error.message.contains("read-only"), error.message)
  }

  test("R8: a declared query that never ends is ended by the database, and answered Timeout") {
    val error = refusal(nodes.ask(Nodes.around))
    assertEquals(error.code, ErrorCode.Timeout, error.message)
    assert(error.message.contains("'around'"), error.message)
    val database = Database()(using kit.service.system)
    val running = Await.result(
      database.query(
        SqlFragment.raw(
          "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid() " +
            "AND query LIKE '%/* around */%'"
        )
      )(_.get(0, classOf[java.lang.Long]).longValue),
      10.seconds
    )
    assertEquals(running, Vector(0L))
  }

  test(
    "C2: a value the query does not take, or one it is not given, is refused and nothing is sent"
  ) {
    val before = logged("sc002-control")
    val extra  = refusal(nodes.ask(Nodes.control, "kind" -> "team", "colour" -> "red"))
    assertEquals(extra.code, ErrorCode.BadRequest)
    assert(extra.message.contains("'colour'"), extra.message)
    val missing = refusal(nodes.ask(Nodes.control))
    assertEquals(missing.code, ErrorCode.BadRequest)
    assert(missing.message.contains("'kind'"), missing.message)
    Thread.sleep(500)
    assertEquals(logged("sc002-control"), before)
  }

  test("C1: a query of another view is refused, naming both") {
    val other =
      nodesDeclaring("elsewhere", "SELECT 1").queries.head.copy(view = Accounts.componentId)
    val error = refusal(nodes.ask(other))
    assertEquals(error.code, ErrorCode.NotFound)
    assert(error.message.contains("'accounts'") && error.message.contains("'nodes'"), error.message)
  }

  test(
    "C4: a limit reads at most that many rows, in the statement's order, and the pool is still whole"
  ) {
    assertEquals(nodes.ask(Nodes.byKey, 2).map(_.key), Vector("a", "b"))
    (1 to 20).foreach(_ => assertEquals(nodes.ask(Nodes.byKey, 1).map(_.key), Vector("a")))
    assertEquals(nodes.ask(Nodes.byKey).map(_.key), Vector("a", "b", "c", "d", "e"))
  }

  test("C6: a statement with no payload column is Internal, naming the view and the query") {
    val error = refusal(nodes.ask(Nodes.keysOnly))
    assertEquals(error.code, ErrorCode.Internal)
    assert(
      error.message.contains("'nodes'") && error.message.contains("'keys-only'"),
      error.message
    )
    assert(error.message.contains("payload"), error.message)
  }

  test("C7: a declared query is counted as a call to the view, under the query's name") {
    val _ = nodes.ask(Nodes.ofKind, "kind" -> "person")
    val address =
      kit.service.observabilityAddress.getOrElse(fail("no local observability endpoint"))
    val http = HttpClient.newHttpClient()
    kit.eventually("the query counted") {
      val body = http
        .send(
          HttpRequest.newBuilder(URI.create(s"$address/observability/topology")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        .body
      Option.when(body.contains("\"of-kind\""))(body)
    }: Unit
  }

  test("asking many at once is answered for each") {
    val asked = (1 to 20).map(_ => nodes.askAsync(Nodes.under, "row" -> "a"))
    Await
      .result(Future.sequence(asked), 30.seconds)
      .foreach(answer => assertEquals(answer.map(_.key).sorted, Vector("b", "c", "d")))
  }
