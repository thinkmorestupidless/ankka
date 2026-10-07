package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import net.sf.jsqlparser.expression.JdbcNamedParameter
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.StatementVisitor
import net.sf.jsqlparser.util.TablesNamesFinder

/**
 * What a view's declared statement may be: `contracts/declared-queries.md`, rules Q1–Q9 and the way
 * values are bound. Pure: nothing here reaches a database.
 *
 * The first cases ask the parser alone, so that a failure there is known to be the parser's and not
 * the check's.
 */
class QueryCheckSuite extends munit.FunSuite:

  private val table = "ankka_view_nodes"
  private val view  = ComponentId("nodes")

  private val recursive =
    s"""WITH RECURSIVE under AS (
       |  SELECT row_key, payload FROM $table WHERE payload::jsonb->>'parent' = :row
       |  UNION ALL
       |  SELECT n.row_key, n.payload FROM $table n JOIN under u ON n.payload::jsonb->>'parent' = u.row_key
       |)
       |SELECT payload FROM under""".stripMargin

  // ── The parser alone (research R7, verify first) ──────────────────────────

  private final class Found extends TablesNamesFinder[Void]:
    init(false)
    val tables = Vector.newBuilder[String]
    val named  = Vector.newBuilder[String]
    override def visit[S](t: Table, context: S): Void =
      tables += t.getFullyQualifiedName
      super.visit(t, context)
    override def visit[S](p: JdbcNamedParameter, context: S): Void =
      named += p.getName
      super.visit(p, context)

  private def parse(sql: String): (Vector[String], Vector[String]) =
    val statement = CCJSqlParserUtil.parse(sql)
    val found     = Found()
    statement.accept(found: StatementVisitor[Void], null): Unit
    (found.tables.result(), found.named.result())

  test("the parser reads a JSON field compared with a named value") {
    val (tables, named) =
      parse(s"SELECT payload FROM $table WHERE payload::jsonb->>'parent' = :row")
    assertEquals(tables, Vector(table))
    assertEquals(named, Vector("row"))
  }

  test("the parser reads containment and a cast value") {
    val (tables, named) = parse(s"SELECT payload FROM $table WHERE payload::jsonb @> :v::jsonb")
    assertEquals(tables, Vector(table))
    assertEquals(named, Vector("v"))
  }

  test("the parser reads a recursive statement that joins its own WITH item") {
    val (tables, named) = parse(recursive)
    assertEquals(tables.toSet, Set(table, "under"))
    assertEquals(named.distinct, Vector("row"))
  }

  test("the parser finds a table in a subquery, a join, a LATERAL and a WITH item's body") {
    def tablesOf(sql: String) = parse(sql)._1.toSet
    assert(tablesOf(s"SELECT payload FROM $table WHERE row_key IN (SELECT k FROM other)")("other"))
    assert(tablesOf(s"SELECT payload FROM $table t JOIN other o ON o.k = t.row_key")("other"))
    assert(
      tablesOf(s"SELECT payload FROM $table t JOIN LATERAL (SELECT 1 FROM other) l ON true")(
        "other"
      )
    )
    assert(tablesOf(s"WITH x AS (SELECT * FROM other) SELECT payload FROM $table")("other"))
  }

  test("the parser reports no table for a name in a comment, a literal or a dollar-quoted string") {
    val sql =
      s"""SELECT payload, $$$$ other $$$$ AS note FROM $table -- other
         |WHERE payload::jsonb->>'n' = 'other' /* other */""".stripMargin
    assertEquals(parse(sql)._1, Vector(table))
  }

  // ── Q1–Q9 ─────────────────────────────────────────────────────────────────

  private def check(statement: String, name: String = "q") =
    QueryCheck.check(view, table, DeclaredQuery(view, name, statement))

  private def refused(statement: String, name: String = "q"): String =
    check(statement, name) match
      case Left(problem) =>
        assert(problem.contains("'nodes'"), problem)
        assert(problem.contains(s"'$name'"), problem)
        problem
      case Right(checked) => fail(s"expected a refusal, got $checked")

  test("Q1: a statement the parser cannot read is refused with the parser's words") {
    assert(refused("SELEC payload FROM ankka_view_nodes").contains("cannot be read"))
  }

  test("Q2: more than one statement is refused") {
    assert(refused(s"SELECT payload FROM $table; DELETE FROM $table").contains("2 statements"))
    assert(refused("").contains("no statement"))
  }

  test("Q3: a statement that is not a select is refused, saying what it is") {
    assert(refused(s"UPDATE $table SET payload = '{}'").contains("UPDATE"))
    assert(refused(s"DELETE FROM $table").contains("DELETE"))
    assert(refused(s"INSERT INTO $table (row_key, payload) VALUES ('a', '{}')").contains("INSERT"))
  }

  test("Q4: a WITH item that is not a select is refused, naming it") {
    val problem =
      refused(s"WITH gone AS (DELETE FROM $table RETURNING payload) SELECT payload FROM gone")
    assert(problem.contains("'gone'"), problem)
    assert(problem.contains("DELETE"), problem)
  }

  test("Q5: SELECT INTO and a locking clause are refused") {
    assert(refused(s"SELECT payload INTO stolen FROM $table").contains("INTO"))
    assert(refused(s"SELECT payload FROM $table FOR UPDATE").contains("FOR UPDATE"))
    assert(refused(s"SELECT payload FROM $table FOR SHARE").contains("FOR SHARE"))
    assert(
      refused(
        s"SELECT payload FROM $table WHERE row_key IN (SELECT row_key FROM $table FOR UPDATE)"
      )
        .contains("FOR UPDATE")
    )
  }

  test("Q6: a table that is not the view's own is refused, and the problem names it") {
    val problem = refused("SELECT payload FROM ankka_view_accounts")
    assert(problem.contains("ankka_view_accounts"), problem)
    assert(problem.contains(table), problem)
    assert(
      refused(s"SELECT payload FROM $table WHERE row_key IN (SELECT k FROM other)").contains(
        "other"
      )
    )
    assert(refused("WITH x AS (SELECT * FROM other) SELECT payload FROM x").contains("other"))
    assert(refused(s"SELECT payload FROM $table, pg_stat_activity").contains("pg_stat_activity"))
  }

  test(
    "Q6: a WITH item is a relation of the statement, and the view's table may be named in any case"
  ) {
    assert(check(recursive).isRight)
    assert(check("SELECT payload FROM ANKKA_VIEW_NODES").isRight)
    assert(check("""SELECT payload FROM "ankka_view_nodes"""").isRight)
    assert(refused("""SELECT payload FROM "ANKKA_VIEW_NODES"""").contains("ANKKA_VIEW_NODES"))
  }

  test("Q7: a table named with a schema is refused, even the view's own") {
    val problem = refused(s"SELECT payload FROM public.$table")
    assert(problem.contains(s"public.$table"), problem)
    assert(problem.contains("name the table alone"), problem)
  }

  test(
    "Q8: a function that reads a query, a relation or a file, or reaches past the statement, is refused"
  ) {
    for function <- Seq(
        "query_to_xml('select 1', true, true, '')",
        "table_to_xml('other', true, true, '')",
        "pg_read_file('/etc/passwd')",
        "dblink('host=x', 'select 1')",
        "lo_get(1)",
        "set_config('statement_timeout', '0', true)",
        "pg_advisory_lock(1)",
        "pg_terminate_backend(1)"
      )
    do
      val name = function.takeWhile(_ != '(')
      assert(refused(s"SELECT $function AS payload FROM $table").contains(name), function)
  }

  test("Q9: a name that is a fixed way of asking, a name used twice, or a bad name is refused") {
    for reserved <- Seq("get", "all", "where", "ordered", "count", "by-id", "by-key") do
      assert(refused(s"SELECT payload FROM $table", reserved).contains("a fixed way of asking"))
    assert(refused(s"SELECT payload FROM $table", "Under").contains("[a-z0-9-]"))
    val twice = QueryCheck.problemsOf(
      view,
      table,
      Vector(
        DeclaredQuery(view, "under", s"SELECT payload FROM $table"),
        DeclaredQuery(view, "under", s"SELECT payload FROM $table")
      )
    )
    assertEquals(twice.size, 1)
    assert(twice.head.contains("twice"), twice.head)
    assert(refused(s"SELECT payload FROM $table WHERE row_key = :Row").contains("Row"))
  }

  test("a statement with a positional or unnamed value is refused") {
    assert(refused(s"SELECT payload FROM $table WHERE row_key = $$1").contains(":name"))
    assert(refused(s"SELECT payload FROM $table WHERE row_key = ?").contains(":name"))
  }

  // ── Values ────────────────────────────────────────────────────────────────

  test("each named value becomes a positional one, in order of first appearance") {
    val checked = check(recursive).toOption.get
    assertEquals(checked.values, Vector("row"))
    assert(checked.sql.contains("= $1"), checked.sql)
    assert(!checked.sql.contains(":row"), checked.sql)
    assertEquals(checked.sql.sliding(2).count(_ == "$1"), 1)

    val two = check(
      s"SELECT payload FROM $table WHERE payload::jsonb->>'a' = :b AND payload::jsonb->>'c' = :a AND (:b) <> ''"
    ).toOption.get
    assertEquals(two.values, Vector("b", "a"))
    assert(two.sql.contains("= $1 AND") && two.sql.contains("= $2 AND ($1)"), two.sql)
  }

  test("nothing is rewritten inside a string, a quoted name, a dollar quote, a comment or a cast") {
    val sql =
      s"""SELECT payload FROM $table -- :not
         |WHERE payload::jsonb->>':not' = :yes /* :not /* nested :not */ still :not */
         |AND $$tag$$ :not $$tag$$ <> '' AND "row_key" <> 'it''s :not' AND (:yes)::text <> ''""".stripMargin
    val checked = check(sql).toOption.get
    assertEquals(checked.values, Vector("yes"))
    assertEquals(checked.sql.sliding(4).count(_ == ":not"), 7)
    assert(checked.sql.contains("payload::jsonb"), checked.sql)
    assert(checked.sql.contains("($1)::text"), checked.sql)
  }

  test("a trailing semicolon is accepted and not sent") {
    val checked = check(s"SELECT payload FROM $table WHERE row_key = :k ;  ").toOption.get
    assert(!checked.sql.contains(";"), checked.sql)
  }

  // ── Every view of a service ──────────────────────────────────────────────

  private val text = Codecs.serializer[String]("text")

  private object Node
      extends View.Companion[NodeView, String, String](
        ComponentId("nodes"),
        ChangeSource.EventSourced(ComponentId("node"), text),
        text
      ):
    val under                    = query("under")(recursive)
    val broken                   = query("broken")("SELECT payload FROM ankka_view_accounts")
    def lateQuery: DeclaredQuery = query("late")(s"SELECT payload FROM ${this.table}")
    def create(ctx: ViewComponentContext) = new NodeView

  private final class NodeView extends View[String, String]:
    def onChange(change: String) = effects.ignore()

  test("a service with a view declaring a refused statement does not start, and says why") {
    val problems = QueryCheck.problems(Seq(Node.descriptor))
    assertEquals(problems.size, 1)
    assert(problems.head.contains("'broken'") && problems.head.contains("ankka_view_accounts"))
    val validated = Ankka.service.register(Node.descriptor).validate
    assert(validated.left.exists(_.exists(_.contains("'broken'"))), validated.toString)
  }

  test("a query declared after the view was registered is a programming error") {
    val _      = Node.descriptor
    val thrown = intercept[IllegalStateException](Node.lateQuery)
    assert(thrown.getMessage.contains("'late'"))
  }
