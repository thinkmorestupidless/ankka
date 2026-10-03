package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, View, ViewDescriptor}
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.duration.FiniteDuration
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

  /** Queries against a view, resolved from its companion so the row type is carried. */
  def forView[V <: View[Src, Row], Src, Row](
      companion: View.Companion[V, Src, Row]
  ): ViewQueries[Row] =
    ViewQueries(
      companion.componentId.toString,
      ViewDescriptor.tableFor(companion.componentId),
      companion.rowSerializer,
      database,
      askTimeout
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

final class ViewQueries[Row] private[ankka] (
    view: String,
    table: String,
    serializer: Serializer[Row],
    database: Database,
    askTimeout: FiniteDuration
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
