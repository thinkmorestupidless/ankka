package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.ViewEffect
import com.thinkmorestupidless.ankka.core.{Metadata, Serializer}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  IncomingMessage,
  JournalRecord,
  MessagePublisher,
  Observability,
  ProjectionSupport,
  SpanOutcome,
  StateRecord,
  Trace,
  ViewStore
}
import com.thinkmorestupidless.ankka.sdk.ViewDescriptor
import org.apache.pekko.Done
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.{
  DeletedDurableState,
  DurableStateChange,
  UpdatedDurableState
}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcSession}
import org.apache.pekko.projection.scaladsl.Handler

import scala.concurrent.{ExecutionContext, Future}

/**
 * Views and consumers whose handlers live in another process.
 *
 * The source resolution, the offset store and the row table are exactly the in-process ones —
 * `ProjectionRuntime` builds the same projections and hands them these handlers instead of
 * `ViewEventHandler` and friends. What differs is the last step: instead of calling a Scala
 * `onChange`, the change crosses the conversation as a `ViewRequest` or `ConsumerRequest` and the
 * process answers with an effect. Rows are the process's own JSON under its declared row manifest,
 * stored as text like every other view's, so `ViewQueries` reads them unchanged.
 *
 * A remote view or consumer has no `ChangeContext` object; the subject and sequence number travel
 * as metadata under `ce-subject` and `ankka.sequence`.
 */
private[ankka] object RemoteProjection:

  /** Discovery carries no parallelism; a remote projection gets the SDK's default. */
  val Parallelism: Int = 4

  val SequenceKey: String = "ankka.sequence"

  def changeMetadata(subject: String, sequence: Long): Metadata =
    Metadata.empty.withSubject(subject).set(SequenceKey, sequence.toString)

  def payloadOf(record: JournalRecord): Payload =
    Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)

  def payloadOf(record: StateRecord): Payload =
    Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)

  private[remote] def toEffect(outcome: ViewOutcome): ViewEffect[Array[Byte]] = outcome match
    case ViewOutcome.UpdateRow(row) => ViewEffect.UpdateRow(row.data)
    case ViewOutcome.DeleteRow      => ViewEffect.DeleteRow
    case ViewOutcome.Ignore         => ViewEffect.Ignore

/** One remote view: what every source-specific handler below delegates to. */
private[ankka] final class RemoteView(
    val descriptor: RemoteViewDescriptor,
    conversation: Conversation,
    observability: Observability
)(using ec: ExecutionContext):
  import RemoteProjection.*

  val table: String = ViewDescriptor.tableFor(descriptor.componentId)

  private val componentRef = observability.names.intern(descriptor.componentId.toString)
  private val handlerRef   = observability.names.intern("on-change")

  /**
   * Asks the process what to do with a change — or with the source's deletion (`change` absent),
   * which the process answers as a Scala view answers `onDelete`: drop the row, or keep it as the
   * tombstone an order history wants.
   */
  def decide(
      subject: String,
      sequence: Long,
      change: Option[Payload],
      row: Option[Array[Byte]]
  ): Future[ViewOutcome] =
    // A projection has no inbound request, so this span is a trace root — as for a Scala view.
    val span = observability.recorder.begin(
      traceId = Trace.mint(),
      parentSpanId = 0L,
      componentRef = componentRef,
      handlerRef = handlerRef
    )
    conversation
      .handleView(
        ViewRequest(
          descriptor.componentId,
          change,
          Trace.into(changeMetadata(subject, sequence), span.traceId, span.id),
          row.map(bytes => Payload(Payload.Json, descriptor.rowManifest, bytes))
        )
      )
      .transform { result =>
        observability.recorder
          .complete(span, if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed)
        result
      }

  /** Applies an outcome through the view's own connection — for the at-least-once sources. */
  def apply(database: Database, subject: String, outcome: ViewOutcome): Future[Done] =
    outcome match
      case ViewOutcome.UpdateRow(row) =>
        database.execute(ViewStore.upsert(table, subject, String(row.data, "UTF-8"))).map(_ => Done)
      case ViewOutcome.DeleteRow =>
        database.execute(ViewStore.delete(table, subject)).map(_ => Done)
      case ViewOutcome.Ignore =>
        Future.successful(Done)

  def loadRow(database: Database, subject: String): Future[Option[Array[Byte]]] =
    database
      .query(ViewStore.selectByKey(table, subject))(r =>
        r.get("payload", classOf[String]).getBytes("UTF-8")
      )
      .map(_.headOption)

/** Exactly-once over an event sourced entity: the row and the offset in one transaction. */
private[ankka] final class RemoteViewEventHandler(view: RemoteView)(using ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[JournalRecord]]:
  import RemoteProjection.*

  def process(session: R2dbcSession, envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event
    record.kind match
      // A TTL being set is a storage fact, not a domain change — nothing to project.
      case JournalRecord.KindExpiry => Future.successful(Done)
      case kind =>
        val change = if kind == JournalRecord.KindDomain then Some(payloadOf(record)) else None
        ProjectionSupport.loadRow(session, view.table, subject, Serializer.bytes).flatMap { row =>
          view.decide(subject, envelope.sequenceNr, change, row).flatMap { outcome =>
            ProjectionSupport
              .applyView(session, view.table, subject, toEffect(outcome), Serializer.bytes)
          }
        }

/** At-least-once over a key value entity's state changes. */
private[ankka] final class RemoteViewStateHandler(view: RemoteView, database: Database)(using
    ec: ExecutionContext
) extends Handler[DurableStateChange[StateRecord]]:
  import RemoteProjection.*

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)
    val (payload, revision) = change match
      case updated: UpdatedDurableState[StateRecord] =>
        (Some(payloadOf(updated.value)), updated.revision)
      case deleted: DeletedDurableState[StateRecord] => (None, deleted.revision)
    view.loadRow(database, subject).flatMap { row =>
      view.decide(subject, revision, payload, row).flatMap(view.apply(database, subject, _))
    }

/** At-least-once over a broker topic; the offset lives with the broker. */
private[ankka] final class RemoteViewTopicHandler(view: RemoteView, database: Database)(using
    ec: ExecutionContext
):

  def process(message: IncomingMessage): Future[Done] =
    message.subject match
      case None =>
        // Without a subject there is no row to key off; failing would redeliver forever.
        Future.successful(Done)
      case Some(subject) =>
        val payload = Payload(
          message.metadata.get(PayloadKeys.ContentType).getOrElse(Payload.Json),
          message.metadata.get(PayloadKeys.Manifest).getOrElse(""),
          message.payload
        )
        view.loadRow(database, subject).flatMap { row =>
          view.decide(subject, 0L, Some(payload), row).flatMap(view.apply(database, subject, _))
        }

/** One remote consumer, over any source. */
private[ankka] final class RemoteConsumer(
    val descriptor: RemoteConsumerDescriptor,
    conversation: Conversation,
    publisher: Option[MessagePublisher],
    observability: Observability
)(using ec: ExecutionContext):
  import RemoteProjection.*

  private val componentRef = observability.names.intern(descriptor.componentId.toString)
  private val handlerRef   = observability.names.intern("on-message")

  /**
   * Hands a change — or the source's deletion, `change` absent — to the process and publishes
   * whatever it produces.
   */
  def handle(subject: String, sequence: Long, change: Option[Payload]): Future[Done] =
    val span = observability.recorder.begin(
      traceId = Trace.mint(),
      parentSpanId = 0L,
      componentRef = componentRef,
      handlerRef = handlerRef
    )
    conversation
      .handleConsumer(
        ConsumerRequest(
          descriptor.componentId,
          change,
          Trace.into(changeMetadata(subject, sequence), span.traceId, span.id)
        )
      )
      .transform { result =>
        observability.recorder
          .complete(span, if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed)
        result
      }
      .flatMap {
        case ConsumerOutcome.Produce(payload, metadata) =>
          (descriptor.producesTo, publisher) match
            case (Some(topic), Some(target)) =>
              // `ce-subject` defaults to the source entity id so per-entity ordering survives
              // the hop onto a partition; the manifest travels so a topic-sourced remote
              // component can decode what it gets.
              val enriched =
                (if metadata.subject.isDefined then metadata else metadata.withSubject(subject))
                  .set(PayloadKeys.Manifest, payload.manifest)
                  .set(PayloadKeys.ContentType, payload.contentType)
              target.publish(topic, payload.data, enriched)
            case _ =>
              // Startup validation rules this out; silently dropping would hide a slip.
              Future.failed(
                IllegalStateException(
                  s"consumer '${descriptor.componentId}' produced a message but has no " +
                    "publish target configured"
                )
              )
        case ConsumerOutcome.Done | ConsumerOutcome.Ignore => Future.successful(Done)
      }

private[ankka] final class RemoteConsumerEventHandler(consumer: RemoteConsumer)
    extends Handler[EventEnvelope[JournalRecord]]:
  import RemoteProjection.*

  def process(envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event
    record.kind match
      case JournalRecord.KindDomain =>
        consumer.handle(subject, envelope.sequenceNr, Some(payloadOf(record)))
      case JournalRecord.KindDeleted => consumer.handle(subject, envelope.sequenceNr, None)
      case _                         => Future.successful(Done)

private[ankka] final class RemoteConsumerStateHandler(consumer: RemoteConsumer)
    extends Handler[DurableStateChange[StateRecord]]:
  import RemoteProjection.*

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)
    change match
      case updated: UpdatedDurableState[StateRecord] =>
        consumer.handle(subject, updated.revision, Some(payloadOf(updated.value)))
      case deleted: DeletedDurableState[StateRecord] =>
        consumer.handle(subject, deleted.revision, None)

private[ankka] final class RemoteConsumerTopicHandler(consumer: RemoteConsumer):

  def process(message: IncomingMessage): Future[Done] =
    val payload = Payload(
      message.metadata.get(PayloadKeys.ContentType).getOrElse(Payload.Json),
      message.metadata.get(PayloadKeys.Manifest).getOrElse(""),
      message.payload
    )
    consumer.handle(message.subject.getOrElse(""), 0L, Some(payload))
