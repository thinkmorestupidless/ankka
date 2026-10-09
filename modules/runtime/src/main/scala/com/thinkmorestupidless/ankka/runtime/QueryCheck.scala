package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId}
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteKeyedViewDescriptor,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.{DeclaredQuery, KeyedViewDescriptor, ViewDescriptor}
import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.{Function, JdbcNamedParameter, JdbcParameter}
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.{Statement, StatementVisitor}
import net.sf.jsqlparser.statement.select.{
  ParenthesedSelect,
  PlainSelect,
  Select,
  SetOperationList,
  WithItem
}
import net.sf.jsqlparser.util.TablesNamesFinder

import java.util.Locale
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** A declared query as it is run: the statement with `$n` for each value, and the values' names. */
private[ankka] final case class CheckedQuery(name: String, sql: String, values: Vector[String])

/**
 * What a view's declared statement may be, checked once when the service starts, for a view written
 * in Scala and one discovered from a process or a module alike: `contracts/declared-queries.md`.
 *
 * The check reads the parsed statement, never the text, so a table named in a comment or a string
 * is not a table. It holds the developer's statement to one read of the view's own table; the
 * database is what holds it to reading at all (a declared query runs in a read-only transaction),
 * so this is a guard for the developer who wrote the statement, not a wall against them.
 *
 * A statement the parser cannot read is refused with the parser's words. What the parser does not
 * know is therefore a refusal the developer sees at startup, never a statement let through.
 */
private[ankka] object QueryCheck:

  /** The fixed ways of asking a view, in every language; a declared query may not take one. */
  val FixedWays: Vector[String] =
    Vector("get", "all", "where", "ordered", "count", "by-id", "by-key")

  private val QueryName = "[a-z0-9-]+".r
  private val ValueName = "[a-z][a-z0-9_]*".r

  /**
   * Functions that read a query or a relation given as text, read a file, or reach past the one
   * statement — to other connections, to locks that outlive it, or to the settings that bound it.
   * Without this list a statement could be a read of the view's own table as parsed and something
   * else as run.
   */
  private val Refused: Set[String] = Set(
    "pg_read_file",
    "pg_read_binary_file",
    "pg_ls_dir",
    "pg_stat_file",
    "set_config",
    "pg_cancel_backend",
    "pg_terminate_backend",
    "pg_reload_conf"
  )

  private val RefusedPrefixes: Vector[String] = Vector(
    "query_to_xml",
    "table_to_xml",
    "schema_to_xml",
    "database_to_xml",
    "cursor_to_xml",
    "dblink",
    "lo_",
    "pg_advisory",
    "pg_try_advisory"
  )

  /** Every problem with every view's declared queries; empty when a service may start. */
  def problems(descriptors: Seq[ComponentDescriptor]): Vector[String] =
    descriptors.toVector.flatMap {
      case view: ViewDescriptor[?, ?, ?] =>
        problemsOf(view.componentId, view.tableName, view.queries)
      case view: RemoteViewDescriptor =>
        problemsOf(
          view.componentId,
          ViewDescriptor.tableFor(view.componentId),
          view.declaredQueries
        )
      case view: KeyedViewDescriptor[?, ?] =>
        problemsOf(view.componentId, view.tableName, view.queries)
      case view: RemoteKeyedViewDescriptor =>
        problemsOf(
          view.componentId,
          ViewDescriptor.tableFor(view.componentId),
          view.declaredQueries
        )
      case _ => Vector.empty
    }

  /** Every problem with one view's declared queries. */
  def problemsOf(view: ComponentId, table: String, queries: Vector[DeclaredQuery]): Vector[String] =
    val twice = queries
      .groupBy(_.name)
      .collect {
        case (name, same) if same.size > 1 =>
          s"view '$view' declares the query '$name' twice; each query has a name of its own"
      }
      .toVector
      .sorted
    twice ++ queries.distinctBy(_.name).flatMap(check(view, table, _).left.toOption)

  /**
   * One view's queries, checked; for a view the service has already validated, so a problem here is
   * a programming error and throws.
   */
  def checkedAll(
      view: ComponentId,
      table: String,
      queries: Vector[DeclaredQuery]
  ): Vector[CheckedQuery] =
    queries.map(query =>
      check(view, table, query).fold(problem => throw IllegalStateException(problem), identity)
    )

  /** A statement reaching into a personal envelope's ciphertext, which nothing can match. */
  private val PersonalData = "(?s).*->>?\\s*'data'.*".r

  /** One declared query: refused with the first rule it breaks, or ready to run. */
  def check(view: ComponentId, table: String, query: DeclaredQuery): Either[String, CheckedQuery] =
    def refused(what: String) = Left(s"view '$view' declares the query '${query.name}', $what")
    val own                   = table.toLowerCase(Locale.ROOT)

    if !QueryName.matches(query.name) then
      refused("whose name is not made of [a-z0-9-]; a query's name is its wire name")
    else if FixedWays.contains(query.name) then
      refused(
        s"whose name is a fixed way of asking a view (${FixedWays.mkString(", ")}); give it another"
      )
    else if query.statement.trim.isEmpty then refused("which holds no statement")
    else if PersonalData.matches(query.statement) then
      refused(
        "which reads a personal field's ciphertext ('data'); match a personal field by its lookup " +
          "token ('lookup') with a value from Personal.lookupToken"
      )
    else
      Values.scan(query.statement) match
        case Left(why) => refused(why)
        case Right((sql, values)) =>
          parse(query.statement) match
            case Left(why) => refused(why)
            case Right(statement) =>
              val found = Found()
              statement.accept(found: StatementVisitor[Void], null): Unit
              val relations = found.withItems.map(w => fold(w.getAlias.getName)).toSet + own
              val badValue  = values.find(!ValueName.matches(_))
              val modifying = found.withItems.find(!_.getStatement.isInstanceOf[ParenthesedSelect])
              val qualified =
                found.tables.find(t => t.getSchemaName != null || t.getDatabaseName != null)
              val other    = found.tables.find(t => !relations(fold(t.getName)))
              val function = found.functions.find(refusedFunction)
              statement match
                case _: Select =>
                  if modifying.nonEmpty then
                    val item = modifying.get
                    refused(
                      s"whose WITH item '${item.getAlias.getName}' is ${kindOf(item.getStatement)}; " +
                        "a declared query only reads"
                    )
                  else if found.into.nonEmpty then
                    refused("which is a SELECT ... INTO; a declared query only reads")
                  else if found.locking.nonEmpty then
                    refused(
                      s"which locks rows (${found.locking.head}); a declared query only reads"
                    )
                  else if qualified.nonEmpty then
                    refused(
                      s"which reads ${qualified.get.getFullyQualifiedName}; name the table alone, as " +
                        s"$table"
                    )
                  else if other.nonEmpty then
                    refused(
                      s"which reads the table ${other.get.getFullyQualifiedName}; a declared query " +
                        s"reads only its own view's table, $table"
                    )
                  else if function.nonEmpty then
                    refused(
                      s"which calls ${function.get}, which reaches past a read of the view's table"
                    )
                  else if found.unnamed > 0 then
                    refused("which uses ? for a value; name each value as :name")
                  else if badValue.nonEmpty then
                    refused(s"whose value ':${badValue.get}' is not named with [a-z][a-z0-9_]*")
                  else if values.toSet != found.named.toSet then
                    refused(
                      s"whose values were read two ways (the text names " +
                        s"${values.mkString(", ")}; the parser ${found.named.distinct.mkString(", ")}); " +
                        "write each value as :name outside a string or a comment"
                    )
                  else Right(CheckedQuery(query.name, sql, values))
                case other =>
                  refused(s"which is ${kindOf(other)}; a declared query only reads")

  private def parse(text: String): Either[String, Statement] =
    try
      val statements = CCJSqlParserUtil.parseStatements(text).asScala.toVector
      statements.size match
        case 0 => Left("which holds no statement")
        case 1 => Right(statements.head)
        case n => Left(s"which holds $n statements; a declared query is one")
    catch
      case failure: JSQLParserException =>
        val first = Option(failure.getMessage)
          .flatMap(_.linesIterator.map(_.trim).find(_.nonEmpty))
          .getOrElse(failure.getClass.getSimpleName)
          .take(200)
        Left(s"which cannot be read as SQL: $first")

  /** A name as Postgres reads it: folded to lower case unless it is quoted. */
  private def fold(name: String): String =
    if name.length >= 2 && name.startsWith("\"") && name.endsWith("\"") then
      name.substring(1, name.length - 1).replace("\"\"", "\"")
    else name.toLowerCase(Locale.ROOT)

  private def refusedFunction(name: String): Boolean =
    val last = fold(name.split('.').last)
    Refused(last) || RefusedPrefixes.exists(last.startsWith)

  private def kindOf(statement: AnyRef): String =
    val name = statement.getClass.getSimpleName
    if name.contains("Delete") then "a DELETE"
    else if name.contains("Insert") then "an INSERT"
    else if name.contains("Update") then "an UPDATE"
    else if name.contains("Merge") then "a MERGE"
    else s"a ${name.toUpperCase(Locale.ROOT)}"

  /** Everything the check needs from one walk of the parsed statement. */
  private final class Found extends TablesNamesFinder[Void]:
    init(false)
    val tables    = ArrayBuffer.empty[Table]
    val functions = ArrayBuffer.empty[String]
    val named     = ArrayBuffer.empty[String]
    val withItems = ArrayBuffer.empty[WithItem[?]]
    val into      = ArrayBuffer.empty[String]
    val locking   = ArrayBuffer.empty[String]
    var unnamed   = 0

    private def locks(select: Select): Unit =
      Option(select.getForMode).foreach(mode => locking += s"FOR ${mode.getValue}")

    override def visit[S](table: Table, context: S): Void =
      tables += table
      super.visit(table, context)

    override def visit[S](function: Function, context: S): Void =
      functions += function.getName
      super.visit(function, context)

    override def visit[S](parameter: JdbcNamedParameter, context: S): Void =
      named += parameter.getName
      super.visit(parameter, context)

    override def visit[S](parameter: JdbcParameter, context: S): Void =
      unnamed += 1
      super.visit(parameter, context)

    override def visit[S](item: WithItem[?], context: S): Void =
      withItems += item
      super.visit(item, context)

    override def visit[S](select: PlainSelect, context: S): Void =
      Option(select.getIntoTables).filter(!_.isEmpty).foreach(_ => into += "INTO")
      locks(select)
      super.visit(select, context)

    override def visit[S](select: SetOperationList, context: S): Void =
      locks(select)
      super.visit(select, context)

    override def visit[S](select: ParenthesedSelect, context: S): Void =
      locks(select)
      super.visit(select, context)

/**
 * Finds a statement's `:name` values and writes each as the positional `$n` the database binds, in
 * order of first appearance; a name used twice is one `$n`.
 *
 * A scanner of the text, which skips strings, quoted names, dollar-quoted strings, comments and
 * `::` casts, rather than a rendering of the parsed statement: the database is sent the developer's
 * own text, not the parser's re-rendering of it. The names found here must be the names the parser
 * found, or the statement is refused, so the two readings cannot disagree unnoticed.
 */
private[runtime] object Values:

  private val DollarTag = "\\$([A-Za-z_][A-Za-z0-9_]*)?\\$".r

  private def identStart(c: Char) = c.isLetter || c == '_'
  private def ident(c: Char)      = c.isLetterOrDigit || c == '_'

  def scan(text: String): Either[String, (String, Vector[String])] =
    val out   = StringBuilder()
    val names = ArrayBuffer.empty[String]
    val n     = text.length
    var i     = 0
    var bad   = Option.empty[String]

    def copyTo(end: Int): Unit =
      val stop = end min n
      out.append(text.substring(i, stop)): Unit
      i = stop

    while i < n && bad.isEmpty do
      val c    = text(i)
      val next = if i + 1 < n then text(i + 1) else '\u0000'
      if c == '\'' then
        val escapes =
          i > 0 && (text(i - 1) == 'E' || text(i - 1) == 'e') && (i < 2 || !ident(text(i - 2)))
        var j    = i + 1
        var done = false
        while j < n && !done do
          if escapes && text(j) == '\\' then j += 2
          else if text(j) == '\'' then
            if j + 1 < n && text(j + 1) == '\'' then j += 2 else { j += 1; done = true }
          else j += 1
        copyTo(j)
      else if c == '"' then
        var j    = i + 1
        var done = false
        while j < n && !done do
          if text(j) == '"' then
            if j + 1 < n && text(j + 1) == '"' then j += 2 else { j += 1; done = true }
          else j += 1
        copyTo(j)
      else if c == '-' && next == '-' then
        val end = text.indexOf('\n', i)
        copyTo(if end < 0 then n else end)
      else if c == '/' && next == '*' then
        var depth = 1
        var j     = i + 2
        while j < n && depth > 0 do
          if text(j) == '/' && j + 1 < n && text(j + 1) == '*' then { depth += 1; j += 2 }
          else if text(j) == '*' && j + 1 < n && text(j + 1) == '/' then { depth -= 1; j += 2 }
          else j += 1
        copyTo(j)
      else if c == '$' && (i == 0 || !ident(text(i - 1))) && next.isDigit then
        bad = Some(
          s"which uses a positional value (${text.substring(i).takeWhile(ch => ch == '$' || ch.isDigit)}); name each value as :name"
        )
      else if c == '$' && (i == 0 || !ident(text(i - 1))) then
        DollarTag.findPrefixOf(text.substring(i)) match
          case Some(tag) =>
            val close = text.indexOf(tag, i + tag.length)
            copyTo(if close < 0 then n else close + tag.length)
          case None => copyTo(i + 1)
      else if c == ':' && next == ':' then copyTo(i + 2)
      else if c == ':' && identStart(next) then
        var j = i + 1
        while j < n && ident(text(j)) do j += 1
        val name = text.substring(i + 1, j)
        val index = names.indexOf(name) match
          case -1 =>
            names += name
            names.size
          case found => found + 1
        out.append('$').append(index): Unit
        i = j
      else copyTo(i + 1)

    bad match
      case Some(why) => Left(why)
      case None =>
        val sql = out.toString.reverse.dropWhile(ch => ch.isWhitespace || ch == ';').reverse
        Right((sql, names.toVector))
