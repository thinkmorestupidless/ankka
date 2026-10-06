package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.{
  ComponentClient,
  DeclaredQuery,
  KeyedView,
  View,
  ViewDescriptor
}
import io.r2dbc.spi.R2dbcException
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

/**
 * How application code queries views.
 *
 * Separate from `ComponentClient` because a view is not addressed by an instance id — the whole
 * point of a view is to be queried by attributes rather than by key.
 */
final class ViewClient private[ankka] (
    database: Database,
    askTimeout: FiniteDuration
)(using system: ActorSystem[?]):

  // Each view's declared queries, checked once: a companion is asked for on every request.
  private val checked = ConcurrentHashMap[ComponentId, Vector[CheckedQuery]]()

  private def checkedFor(
      view: ComponentId,
      queries: => Vector[DeclaredQuery]
  ): Vector[CheckedQuery] =
    checked.computeIfAbsent(
      view,
      _ => QueryCheck.checkedAll(view, ViewDescriptor.tableFor(view), queries)
    )

  /** Queries against a keyed view, resolved from its companion so the row type is carried. */
  def forView[V <: KeyedView[Row], Row](companion: KeyedView.Companion[V, Row]): ViewQueries[Row] =
    ViewQueries(
      companion.componentId.toString,
      ViewDescriptor.tableFor(companion.componentId),
      companion.rowSerializer,
      database,
      askTimeout,
      checkedFor(companion.componentId, companion.descriptor.queries)
    )

  /** Queries against a view, resolved from its companion so the row type is carried. */
  def forView[V <: View[Src, Row], Src, Row](
      companion: View.Companion[V, Src, Row]
  ): ViewQueries[Row] =
    ViewQueries(
      companion.componentId.toString,
      ViewDescriptor.tableFor(companion.componentId),
      companion.rowSerializer,
      database,
      askTimeout,
      checkedFor(companion.componentId, companion.descriptor.queries)
    )

/**
 * Reads from one view's rows.
 *
 * Blocking methods are the primary surface, matching `ComponentClient.invoke`: handlers run on
 * virtual threads, so awaiting is free and sequential code stays readable. The `…Async` variants
 * exist for fanning out.
 */
object ViewQueries:

  /**
   * What a query of a view is called when it is counted as a call. A view has no handler for a
   * query, so these are the names of the ways of asking: fixed, and the same in every language.
   */
  val Get: String     = "get"
  val Where: String   = "where"
  val Ordered: String = "ordered"
  val Count: String   = "count"

  val Names: Set[String] = Set(Get, Where, Ordered, Count)

  /** How many rows a read answers with when the caller does not say. */
  val DefaultLimit: Int = 1000

  /**
   * How long the database lets a declared query run: the caller's wait, less a margin so the caller
   * hears the database's answer rather than its own wait running out, and never under a second.
   */
  def statementTimeout(askTimeout: FiniteDuration): FiniteDuration =
    (askTimeout - 500.millis).max(1.second)

  /** A declared query's statement had no `payload` column to read a row from. */
  private final class NoPayload extends RuntimeException("no payload column")

final class ViewQueries[Row] private[ankka] (
    view: String,
    table: String,
    serializer: Serializer[Row],
    database: Database,
    askTimeout: FiniteDuration,
    declared: Vector[CheckedQuery] = Vector.empty
)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  // Lazy: an endpoint is built before it serves, and a suite that only lists its routes builds one
  // with no actor system behind it. Nothing is counted until a query is made.
  private lazy val observability = Observability(system)

  /**
   * A query, counted as a call to the view from whoever is asking.
   *
   * Nothing hosts a view's rows: they are read straight from the database, so there is no host to
   * count the call where it lands, and it is counted here where it is made. The origin is taken on
   * the calling thread; the rows come back on another.
   */
  private def counted[A](query: String)(run: => Future[A]): Future[A] =
    val origin  = Trace.currentOrigin
    val started = System.nanoTime()
    run.andThen { result =>
      // The view's name is the caller's to give, and from a process in another language it is
      // whatever was sent: counted only when it is a view this service registered.
      if observability.declared.registered(view) then
        val outcome = if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed
        observability.made(origin, view, query, outcome, System.nanoTime() - started)
    }

  private def decode(json: String): Row = serializer.fromBytes(json.getBytes("UTF-8"))

  private def payloads(fragment: SqlFragment): Future[Vector[Row]] =
    database.query(fragment)(row => decode(row.get("payload", classOf[String]))).map(identity)

  /** The row for a source entity id. */
  def getAsync(key: String): Future[Option[Row]] =
    counted(ViewQueries.Get)(payloads(ViewStore.selectByKey(table, key)).map(_.headOption))

  def get(key: String): Option[Row] = ComponentClient.await(getAsync(key), askTimeout)

  /** Every row matching `condition`, which is built with the `sql"…"` interpolator. */
  def whereAsync(condition: SqlFragment, limit: Int = 1000): Future[Vector[Row]] =
    counted(ViewQueries.Where)(payloads(ViewStore.selectWhere(table, condition, limit)))

  def where(condition: SqlFragment, limit: Int = 1000): Vector[Row] =
    ComponentClient.await(whereAsync(condition, limit), askTimeout)

  def orderedAsync(
      condition: SqlFragment,
      order: SqlFragment,
      limit: Int = 1000
  ): Future[Vector[Row]] =
    counted(ViewQueries.Ordered)(payloads(ViewStore.selectOrdered(table, condition, order, limit)))

  def ordered(condition: SqlFragment, order: SqlFragment, limit: Int = 1000): Vector[Row] =
    ComponentClient.await(orderedAsync(condition, order, limit), askTimeout)

  def allAsync(limit: Int = 1000): Future[Vector[Row]] =
    whereAsync(SqlFragment.empty, limit)

  def all(limit: Int = 1000): Vector[Row] = ComponentClient.await(allAsync(limit), askTimeout)

  def countAsync(condition: SqlFragment = SqlFragment.empty): Future[Long] =
    counted(ViewQueries.Count)(
      database
        .query(ViewStore.countWhere(table, condition))(row =>
          row.get(0, classOf[java.lang.Long]).longValue
        )
        .map(_.headOption.getOrElse(0L))
    )

  def count(condition: SqlFragment = SqlFragment.empty): Long =
    ComponentClient.await(countAsync(condition), askTimeout)

  /**
   * The rows of one of this view's declared queries, at most `ViewQueries.DefaultLimit` of them,
   * with each value the query takes given by name.
   */
  def askAsync(query: DeclaredQuery, values: (String, String)*): Future[Vector[Row]] =
    askAsync(query, ViewQueries.DefaultLimit, values*)

  /** As `askAsync`, reading at most `limit` rows, in the statement's own order. */
  def askAsync(query: DeclaredQuery, limit: Int, values: (String, String)*): Future[Vector[Row]] =
    if query.view.toString != view then
      Future.failed(
        CommandError(
          s"the query '${query.name}' is view '${query.view}'s, and this is view '$view'",
          ErrorCode.NotFound
        )
      )
    else askNamed(query.name, values.toMap, limit)

  def ask(query: DeclaredQuery, values: (String, String)*): Vector[Row] =
    ComponentClient.await(askAsync(query, values*), askTimeout)

  def ask(query: DeclaredQuery, limit: Int, values: (String, String)*): Vector[Row] =
    ComponentClient.await(askAsync(query, limit, values*), askTimeout)

  /**
   * A declared query by its name, as a process or a module asks one. Refused before anything is
   * sent when the view declares no such query, or the values are not exactly the ones it takes.
   */
  private[ankka] def askNamed(
      name: String,
      values: Map[String, String],
      limit: Int
  ): Future[Vector[Row]] =
    declared.find(_.name == name) match
      case None =>
        Future.failed(CommandError(s"view '$view' declares no query '$name'", ErrorCode.NotFound))
      case Some(query) =>
        val missing = query.values.find(!values.contains(_))
        val extra   = values.keys.toVector.sorted.find(!query.values.contains(_))
        if missing.nonEmpty then
          Future.failed(
            CommandError(
              s"view '$view' query '$name' takes the value '${missing.get}', which was not given",
              ErrorCode.BadRequest
            )
          )
        else if extra.nonEmpty then
          Future.failed(
            CommandError(
              s"view '$view' query '$name' takes no value '${extra.get}'; it takes " +
                (if query.values.isEmpty then "none" else query.values.mkString(", ")),
              ErrorCode.BadRequest
            )
          )
        else if limit < 1 then
          Future.failed(
            CommandError(s"a limit is 1 or more, not $limit", ErrorCode.BadRequest)
          )
        else
          val timeout = ViewQueries.statementTimeout(askTimeout)
          counted(name)(
            database
              .readOnly(timeout)(
                _.queryUpTo(query.sql, query.values.map(values), limit) { (row, metadata) =>
                  if !metadata.contains("payload") then throw ViewQueries.NoPayload()
                  decode(row.get("payload", classOf[String]))
                }
              )
              .recoverWith { case failure => Future.failed(refusal(name, timeout, failure)) }
          )

  /** What went wrong with a declared query, as the caller is told it. */
  private def refusal(name: String, timeout: FiniteDuration, failure: Throwable): Throwable =
    def causes(t: Throwable): LazyList[Throwable] =
      if t == null then LazyList.empty else t #:: causes(t.getCause)
    val state = causes(failure).collectFirst { case e: R2dbcException => e.getSqlState }
    if causes(failure).exists(_.isInstanceOf[ViewQueries.NoPayload]) then
      CommandError(
        s"view '$view' query '$name' answers with no payload column; a declared query selects " +
          "the rows' payload",
        ErrorCode.Internal
      )
    else if state.contains("57014") then
      CommandError(
        s"view '$view' query '$name' ran for longer than its $timeout, and the database ended it",
        ErrorCode.Timeout
      )
    else
      CommandError(
        s"view '$view' query '$name' failed: ${Option(failure.getMessage).getOrElse(failure.toString)}",
        ErrorCode.Internal
      )
