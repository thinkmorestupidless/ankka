package nakka.runtime

import nakka.core.Serializer
import nakka.core.effect.{ConsumerEffect, ViewEffect}
import nakka.sdk.*
import org.apache.pekko.Done
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcSession

import scala.concurrent.{ExecutionContext, Future}

/** Shared steps between the four projection handlers. */
private[nakka] object ProjectionSupport:

  /**
   * Reads a view row inside the projection's own transaction.
   *
   * Reading through the session rather than a separate connection is what makes a view that
   * computes its next row from its current one — `rowState.map(_.withName(...))` — correct under
   * concurrent redelivery.
   */
  // The type parameter is `A`, not `Row`: `io.r2dbc.spi.Row` is in scope here and
  // shadowing it makes the mapper's parameter type unresolvable.
  def loadRow[A](
      session: R2dbcSession,
      table: String,
      subject: String,
      serializer: Serializer[A]
  ): Future[Option[A]] =
    val fragment = ViewStore.selectByKey(table, subject)
    session.selectOne[A](
      Database.bind(session.createStatement(fragment.render), fragment)
    )(row => serializer.fromBytes(row.get("payload", classOf[String]).getBytes("UTF-8")))

  /** Sets up the view's per-change state, runs it, and always clears the context. */
  def runView(
      view: View[Any, Any],
      descriptor: ViewDescriptor[View[Any, Any], Any, Any],
      subject: String,
      sequenceNr: Long,
      row: Option[Any],
      record: JournalRecord
  ): ViewEffect[Any] =
    view._setRow(row)
    view._setContext(Some(SimpleChangeContext(subject, sequenceNr, localOrigin = true)))
    try
      record.kind match
        case JournalRecord.KindDomain =>
          view.onChange(descriptor.source.decoder.fromBytes(record.payload))
        case JournalRecord.KindDeleted => view.onDelete
        // A TTL being set is a storage fact, not a domain change — nothing for a view
        // to project. The row disappears when the deletion itself is journalled.
        case _ => ViewEffect.Ignore
    finally view._setContext(None)

  /** Writes the row change through the projection's transaction. */
  def applyView(
      session: R2dbcSession,
      table: String,
      subject: String,
      effect: ViewEffect[Any],
      serializer: Serializer[Any]
  )(using ec: ExecutionContext): Future[Done] =
    def run(fragment: SqlFragment): Future[Done] =
      session
        .updateOne(Database.bind(session.createStatement(fragment.render), fragment))
        .map(_ => Done)

    effect match
      case ViewEffect.UpdateRow(row) =>
        run(ViewStore.upsert(table, subject, String(serializer.toBytes(row), "UTF-8")))
      case ViewEffect.DeleteRow => run(ViewStore.delete(table, subject))
      case ViewEffect.Ignore    => Future.successful(Done)

  /**
   * Publishes whatever a consumer produced.
   *
   * `ce-subject` is set to the source entity id unless the consumer set it itself, so per-entity
   * ordering survives the hop onto a broker partition.
   */
  def applyConsumer(
      effect: ConsumerEffect[Any],
      subject: String,
      descriptor: ConsumerDescriptor[Consumer[Any, Any], Any, Any],
      publisher: Option[MessagePublisher]
  ): Future[Done] =
    effect match
      case ConsumerEffect.Done | ConsumerEffect.Ignore =>
        Future.successful(Done)

      case ConsumerEffect.Produce(payload, metadata) =>
        (descriptor.produceTo, publisher, descriptor.outputSerializer) match
          case (Some(topic), Some(target), Some(serializer)) =>
            val enriched =
              if metadata.subject.isDefined then metadata else metadata.withSubject(subject)
            target.publish(topic, serializer.toBytes(payload), enriched)

          case _ =>
            // Startup validation rules this out; reaching it means a producing consumer
            // slipped past `rejectUnsupported`, and silently dropping would hide it.
            Future.failed(
              IllegalStateException(
                s"consumer '${descriptor.componentId}' produced a message but has no " +
                  "publish target configured"
              )
            )
