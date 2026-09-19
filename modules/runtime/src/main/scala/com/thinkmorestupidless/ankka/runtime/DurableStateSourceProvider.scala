package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.{
  DeletedDurableState,
  DurableStateChange,
  Offset,
  UpdatedDurableState
}
import org.apache.pekko.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import org.apache.pekko.persistence.state.DurableStateStoreRegistry
import org.apache.pekko.projection.BySlicesSourceProvider
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}

/**
 * Streams key value entity changes to a projection.
 *
 * Pekko ships an `EventSourcedProvider` but no durable-state equivalent, so this wraps the r2dbc
 * store's own `changesBySlices`. It implements `BySlicesSourceProvider` because the projection's
 * offset store keys offsets by slice range.
 *
 * Only usable with at-least-once projections. That is not a shortcut: a durable state store keeps
 * no history, so it can only ever offer the latest value per entity and intermediate updates may be
 * skipped entirely. Promising exactly-once over a source that cannot replay would be a lie.
 */
private[ankka] final class DurableStateSourceProvider[A](
    entityType: String,
    val minSlice: Int,
    val maxSlice: Int
)(using system: ActorSystem[?])
    extends SourceProvider[Offset, DurableStateChange[A]]
    with BySlicesSourceProvider:

  private given ExecutionContext = system.executionContext

  private val store = DurableStateStoreRegistry(system)
    .durableStateStoreFor[R2dbcDurableStateStore[A]](R2dbcDurableStateStore.Identifier)

  def source(
      offset: () => Future[Option[Offset]]
  ): Future[Source[DurableStateChange[A], NotUsed]] =
    offset().map { stored =>
      store.changesBySlices(entityType, minSlice, maxSlice, stored.getOrElse(Offset.noOffset))
    }

  def extractOffset(envelope: DurableStateChange[A]): Offset = envelope.offset

  def extractCreationTime(envelope: DurableStateChange[A]): Long = envelope match
    case updated: UpdatedDurableState[A] => updated.timestamp
    case deleted: DeletedDurableState[A] => deleted.timestamp

private[ankka] object DurableStateSourceProvider:

  /** Splits the id space into `parallelism` contiguous slice ranges. */
  def sliceRanges(parallelism: Int)(using system: ActorSystem[?]): Seq[Range] =
    DurableStateStoreRegistry(system)
      .durableStateStoreFor[R2dbcDurableStateStore[Any]](R2dbcDurableStateStore.Identifier)
      .sliceRanges(parallelism)
