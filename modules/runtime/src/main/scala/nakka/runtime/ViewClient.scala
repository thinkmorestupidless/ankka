package nakka.runtime

import nakka.core.Serializer
import nakka.sdk.{ComponentClient, View, ViewDescriptor}
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * How application code queries views.
 *
 * Separate from `ComponentClient` because a view is not addressed by an instance id — the whole
 * point of a view is to be queried by attributes rather than by key.
 */
final class ViewClient private[nakka] (
    database: Database,
    askTimeout: FiniteDuration
)(using system: ActorSystem[?]):

  /** Queries against a view, resolved from its companion so the row type is carried. */
  def forView[V <: View[Src, Row], Src, Row](
      companion: View.Companion[V, Src, Row]
  ): ViewQueries[Row] =
    ViewQueries(
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
final class ViewQueries[Row] private[nakka] (
    table: String,
    serializer: Serializer[Row],
    database: Database,
    askTimeout: FiniteDuration
)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  private def decode(json: String): Row = serializer.fromBytes(json.getBytes("UTF-8"))

  private def payloads(fragment: SqlFragment): Future[Vector[Row]] =
    database.query(fragment)(row => decode(row.get("payload", classOf[String]))).map(identity)

  /** The row for a source entity id. */
  def getAsync(key: String): Future[Option[Row]] =
    payloads(ViewStore.selectByKey(table, key)).map(_.headOption)

  def get(key: String): Option[Row] = ComponentClient.await(getAsync(key), askTimeout)

  /** Every row matching `condition`, which is built with the `sql"…"` interpolator. */
  def whereAsync(condition: SqlFragment, limit: Int = 1000): Future[Vector[Row]] =
    payloads(ViewStore.selectWhere(table, condition, limit))

  def where(condition: SqlFragment, limit: Int = 1000): Vector[Row] =
    ComponentClient.await(whereAsync(condition, limit), askTimeout)

  def orderedAsync(
      condition: SqlFragment,
      order: SqlFragment,
      limit: Int = 1000
  ): Future[Vector[Row]] =
    payloads(ViewStore.selectOrdered(table, condition, order, limit))

  def ordered(condition: SqlFragment, order: SqlFragment, limit: Int = 1000): Vector[Row] =
    ComponentClient.await(orderedAsync(condition, order, limit), askTimeout)

  def allAsync(limit: Int = 1000): Future[Vector[Row]] =
    whereAsync(SqlFragment.empty, limit)

  def all(limit: Int = 1000): Vector[Row] = ComponentClient.await(allAsync(limit), askTimeout)

  def countAsync(condition: SqlFragment = SqlFragment.empty): Future[Long] =
    database
      .query(ViewStore.countWhere(table, condition))(row =>
        row.get(0, classOf[java.lang.Long]).longValue
      )
      .map(_.headOption.getOrElse(0L))

  def count(condition: SqlFragment = SqlFragment.empty): Long =
    ComponentClient.await(countAsync(condition), askTimeout)
