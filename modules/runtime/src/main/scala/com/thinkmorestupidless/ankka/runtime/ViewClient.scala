package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.{
  ComponentClient,
  DeclaredQuery,
  KeyedView,
  View,
  ViewDescriptor,
  WatchEvent,
  Watching
}
import io.r2dbc.spi.R2dbcException
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Source

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

  // The instance's watches, made on the first watch: a service that never watches opens no
  // connection for them.
  @volatile private var watching = false
  private lazy val watches: ViewWatches =
    watching = true
    ViewWatches(database, askTimeout)

  /** How many watches this instance holds open, every view together. */
  private[ankka] def openWatches: Int = if watching then watches.openCount else 0

  /** Ends every open watch, telling each the instance is stopping, and closes their connection. */
  private[ankka] def stopWatches(): Unit =
    if watching then scala.concurrent.Await.result(watches.stop(), 10.seconds)

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
      checkedFor(companion.componentId, companion.descriptor.queries),
      Some(() => watches)
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
      checkedFor(companion.componentId, companion.descriptor.queries),
      Some(() => watches)
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
    declared: Vector[CheckedQuery] = Vector.empty,
    watchesOf: Option[() => ViewWatches] = None
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

  /**
   * A stream from the view, counted as one call when it ends, however many rows it gave: ok when it
   * completed or its reader went away, failed when it failed. The origin is taken when the stream
   * is built, on the asking thread; the rows are read later, on another.
   */
  private[ankka] def streamed[A](query: String)(source: => Source[A, NotUsed]): Source[A, NotUsed] =
    val origin = Trace.currentOrigin
    Source
      .lazySource { () =>
        val started = System.nanoTime()
        source.watchTermination() { (_, ended) =>
          ended.onComplete { result =>
            if observability.declared.registered(view) then
              val outcome = if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed
              observability.made(origin, view, query, outcome, System.nanoTime() - started, true)
          }
          NotUsed
        }
      }
      .mapMaterializedValue(_ => NotUsed)

  private def decode(json: String): Row = serializer.fromBytes(json.getBytes("UTF-8"))

  private def fetchSize: Int = system.settings.config.getInt("ankka.view.fetch-size")

  private def statementTimeout: FiniteDuration = ViewQueries.statementTimeout(askTimeout)

  /** The rows of `fragment`, a statement whose `payload` column holds each row, as a stream. */
  private def payloadStream(name: String, fragment: SqlFragment): Source[Row, NotUsed] =
    streamed(name)(
      database
        .stream(statementTimeout, fetchSize)(fragment.render, fragment.params.map(_.value))(
          (row, _) => decode(row.get("payload", classOf[String]))
        )
        .mapError { case failure => refusal(name, statementTimeout, failure) }
    )

  /**
   * Every row of the view as a stream: each row reaches the reader as the database yields it, with
   * none held back for the rest, and no limit unless one is given. The stream runs in one
   * transaction held to reading; a statement the database ends, or a reader that does not read for
   * the statement timeout, fails it, and it is never a shorter answer.
   */
  def allStream(limit: Option[Int] = None): Source[Row, NotUsed] =
    whereStream(SqlFragment.empty, limit)

  /** Every row matching `condition` as a stream, as `allStream` gives every row. */
  def whereStream(condition: SqlFragment, limit: Option[Int] = None): Source[Row, NotUsed] =
    checkedLimit(limit).fold(
      Source.failed,
      _ =>
        limitedTo(payloadStream(ViewQueries.Where, ViewStore.selectRows(table, condition)), limit)
    )

  /** Every row matching `condition`, in `order`, as a stream. */
  def orderedStream(
      condition: SqlFragment,
      order: SqlFragment,
      limit: Option[Int] = None
  ): Source[Row, NotUsed] =
    checkedLimit(limit).fold(
      Source.failed,
      _ =>
        limitedTo(
          payloadStream(ViewQueries.Ordered, ViewStore.selectRowsOrdered(table, condition, order)),
          limit
        )
    )

  /**
   * The rows of one of this view's declared queries as a stream, in the statement's own order and
   * with no limit; each value the query takes is given by name.
   */
  def askStream(query: DeclaredQuery, values: (String, String)*): Source[Row, NotUsed] =
    askStreamUpTo(query, None, values*)

  /** As `askStream`, reading at most `limit` rows. */
  def askStream(query: DeclaredQuery, limit: Int, values: (String, String)*): Source[Row, NotUsed] =
    askStreamUpTo(query, Some(limit), values*)

  private def askStreamUpTo(
      query: DeclaredQuery,
      limit: Option[Int],
      values: (String, String)*
  ): Source[Row, NotUsed] =
    if query.view.toString != view then
      Source.failed(
        CommandError(
          s"the query '${query.name}' is view '${query.view}'s, and this is view '$view'",
          ErrorCode.NotFound
        )
      )
    else askNamedStream(query.name, values.toMap, limit)

  /** A declared query by its name as a stream, refused as `askNamed` refuses. */
  private[ankka] def askNamedStream(
      name: String,
      values: Map[String, String],
      limit: Option[Int]
  ): Source[Row, NotUsed] =
    checkedAsk(name, values, limit.getOrElse(1)) match
      case Left(refused) => Source.failed(refused)
      case Right(query) =>
        val rows = streamed(name)(
          database
            .stream(statementTimeout, fetchSize)(query.sql, query.values.map(values)) {
              (row, metadata) =>
                if !metadata.contains("payload") then throw ViewQueries.NoPayload()
                decode(row.get("payload", classOf[String]))
            }
            .mapError { case failure => refusal(name, statementTimeout, failure) }
        )
        limitedTo(rows, limit)

  /**
   * A watch of one of this view's declared queries, declared `.watched`: every row it matches now,
   * then `WatchEvent.CaughtUp` once, then, for as long as the watcher reads, each row that is
   * written and matches and a removal of each given row that is written so it no longer matches or
   * is deleted. Live and coalesced: never an older version of a row after a newer one, possibly
   * fewer versions than were written, nothing replayed. It completes only when the watcher stops
   * reading, and fails with `WatchEnded` when the view is emptied for a rebuild or the instance
   * stops.
   */
  def watch(query: DeclaredQuery, values: (String, String)*): Source[WatchEvent[Row], NotUsed] =
    watch(query, Watching(), values*)

  /** As `watch`, holding unread rows and overflowing as `watching` says. */
  def watch(
      query: DeclaredQuery,
      watching: Watching,
      values: (String, String)*
  ): Source[WatchEvent[Row], NotUsed] =
    if query.view.toString != view then
      Source.failed(
        CommandError(
          s"the query '${query.name}' is view '${query.view}'s, and this is view '$view'",
          ErrorCode.NotFound
        )
      )
    else watchNamed(query.name, values.toMap, watching)

  /**
   * A declared query by its name, watched, refused as `askNamed` refuses and when not watchable.
   */
  private[ankka] def watchNamed(
      name: String,
      values: Map[String, String],
      watching: Watching
  ): Source[WatchEvent[Row], NotUsed] =
    checkedAsk(name, values, 1) match
      case Left(refused) => Source.failed(refused)
      case Right(query) if !query.watchable =>
        Source.failed(
          CommandError(
            s"view '$view' declares the query '$name' without watched; declare it .watched to " +
              "watch it",
            ErrorCode.BadRequest
          )
        )
      case Right(query) =>
        val binds = query.values.map(values)
        opened(
          name,
          ViewWatches.Target.Named(query.sql, binds),
          watching,
          database.stream(statementTimeout, fetchSize)(query.sql, binds)((row, _) =>
            (row.get("row_key", classOf[String]), row.get("payload", classOf[String]))
          )
        )

  /**
   * A watch of one row by its key: the row now if there is one, then `WatchEvent.CaughtUp`, then
   * each version written and a removal when it is deleted.
   */
  def watchRow(key: String, watching: Watching = Watching()): Source[WatchEvent[Row], NotUsed] =
    val fragment = ViewStore.selectByKey(table, key)
    opened(
      ViewQueries.Get,
      ViewWatches.Target.ByKey(key),
      watching,
      database.stream(statementTimeout, fetchSize)(fragment.render, fragment.params.map(_.value))(
        (row, _) => (key, row.get("payload", classOf[String]))
      )
    )

  private def opened(
      name: String,
      target: ViewWatches.Target,
      watching: Watching,
      rowsNow: Source[(String, String), NotUsed]
  ): Source[WatchEvent[Row], NotUsed] =
    watchesOf match
      case None =>
        Source.failed(
          CommandError(s"view '$view' cannot be watched from here", ErrorCode.Internal)
        )
      case Some(watches) =>
        streamed(name)(
          watches()
            .watch(table, target, watching)(
              rowsNow.mapError { case failure => refusal(name, statementTimeout, failure) }
            )
            .map {
              case WatchEvent.Row(key, json) => WatchEvent.Row(key, decode(json))
              case WatchEvent.Removed(key)   => WatchEvent.Removed(key)
              case WatchEvent.CaughtUp       => WatchEvent.CaughtUp
            }
        )

  private def limitedTo(rows: Source[Row, NotUsed], limit: Option[Int]): Source[Row, NotUsed] =
    limit.fold(rows)(n => rows.take(n.toLong))

  private def checkedLimit(limit: Option[Int]): Either[CommandError, Unit] =
    limit match
      case Some(n) if n < 1 =>
        Left(CommandError(s"a limit is 1 or more, not $n", ErrorCode.BadRequest))
      case _ => Right(())

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
    checkedAsk(name, values, limit) match
      case Left(refused) => Future.failed(refused)
      case Right(query) =>
        val timeout = statementTimeout
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

  /**
   * The declared query `name` names, or why it cannot be asked: the view declares no such query, or
   * the values are not exactly the ones it takes, or the limit is not one or more. Decided before
   * anything is sent.
   */
  private[ankka] def checkedAsk(
      name: String,
      values: Map[String, String],
      limit: Int
  ): Either[CommandError, CheckedQuery] =
    declared.find(_.name == name) match
      case None =>
        Left(CommandError(s"view '$view' declares no query '$name'", ErrorCode.NotFound))
      case Some(query) =>
        val missing = query.values.find(!values.contains(_))
        val extra   = values.keys.toVector.sorted.find(!query.values.contains(_))
        if missing.nonEmpty then
          Left(
            CommandError(
              s"view '$view' query '$name' takes the value '${missing.get}', which was not given",
              ErrorCode.BadRequest
            )
          )
        else if extra.nonEmpty then
          Left(
            CommandError(
              s"view '$view' query '$name' takes no value '${extra.get}'; it takes " +
                (if query.values.isEmpty then "none" else query.values.mkString(", ")),
              ErrorCode.BadRequest
            )
          )
        else if limit < 1 then
          Left(CommandError(s"a limit is 1 or more, not $limit", ErrorCode.BadRequest))
        else Right(query)

  /** What went wrong with a declared query, as the caller is told it. */
  private def refusal(name: String, timeout: FiniteDuration, failure: Throwable): Throwable =
    def causes(t: Throwable): LazyList[Throwable] =
      if t == null then LazyList.empty else t #:: causes(t.getCause)
    val state = causes(failure).collectFirst { case e: R2dbcException => e.getSqlState }
    if failure.isInstanceOf[CommandError] then failure
    else if causes(failure).exists(_.isInstanceOf[Database.NotRead]) then
      CommandError(
        s"view '$view' query '$name' was streamed to a reader that did not read for $timeout, " +
          "and the stream gave up its rows",
        ErrorCode.Timeout
      )
    else if causes(failure).exists(_.isInstanceOf[ViewQueries.NoPayload]) then
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
