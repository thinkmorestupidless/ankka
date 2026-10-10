package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.ViewEffect
import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  EntityViewGuard,
  Database,
  HistoryLines,
  IncomingMessage,
  JournalRecord,
  MessagePublisher,
  Observability,
  ProjectionSupport,
  Span,
  SpanKind,
  SpanOutcome,
  StateRecord,
  SqlFragment,
  Trace,
  TraceContext,
  ViewGuard,
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
import scala.util.control.NonFatal

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

  /** A span for a change: continuing the trace a topic's message carried, else a new trace's. */
  private[remote] def begin(
      observability: Observability,
      componentRef: Int,
      handlerRef: Int,
      parent: Option[TraceContext]
  ): Span =
    parent match
      case Some(p) =>
        observability.recorder.begin(
          p.traceIdHigh,
          p.traceId,
          p.spanId,
          componentRef,
          handlerRef,
          SpanKind.Consumer
        )
      case None => observability.recorder.beginRoot(componentRef, handlerRef)

  /** Discovery carries no parallelism; a remote projection gets the SDK's default. */
  val Parallelism: Int = 4

  val SequenceKey: String = "ankka.sequence"

  def changeMetadata(subject: String, sequence: Long): Metadata =
    Metadata.empty.withSubject(subject).set(SequenceKey, sequence.toString)

  /**
   * What a consumer is told about a change: a view's, and what this runtime speaks, so the process
   * knows whether it may answer with several messages.
   */
  def consumerMetadata(subject: String, sequence: Long): Metadata =
    changeMetadata(subject, sequence).set(WireProtocol.MetadataKey, WireProtocol.Version)

  def payloadOf(record: JournalRecord): Payload =
    Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)

  def payloadOf(record: StateRecord): Payload =
    Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)

  private[remote] def toEffect(outcome: ViewOutcome): ViewEffect[Array[Byte]] = outcome match
    case ViewOutcome.UpdateRow(row) => ViewEffect.UpdateRow(row.data)
    case ViewOutcome.DeleteRow      => ViewEffect.DeleteRow
    case ViewOutcome.Ignore         => ViewEffect.Ignore
    // Only a keyed view answers with rows, and a plain view is never one: a process that does is
    // refused, its change failed and handled again, rather than its rows dropped.
    case ViewOutcome.Rows(_) =>
      throw IllegalStateException("a plain view answered with rows, which only a keyed view may")

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
      row: Option[Array[Byte]],
      parent: Option[TraceContext] = None
  ): Future[ViewOutcome] =
    // A change from a journal is a trace's root, as for a Scala view; a message from a topic
    // continues the trace it carries.
    val span = RemoteProjection.begin(observability, componentRef, handlerRef, parent)
    conversation
      .handleView(
        ViewRequest(
          descriptor.componentId,
          change,
          CallOrigin.into(
            Trace.into(changeMetadata(subject, sequence), span.context),
            CallOrigin(descriptor.componentId.toString, "on-change")
          ),
          row.map(bytes => Payload(Payload.Json, descriptor.rowManifest, bytes))
        )
      )
      .transform { result =>
        observability.recorder
          .complete(span, if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed)
        result
      }

  /** Applies an outcome through the view's own connection — for the at-least-once sources. */
  def apply(
      database: Database,
      subject: String,
      outcome: ViewOutcome,
      write: Option[SqlFragment => Future[Done]] = None
  ): Future[Done] =
    val writing =
      write.getOrElse((fragment: SqlFragment) => database.execute(fragment).map(_ => Done))
    outcome match
      case ViewOutcome.UpdateRow(row) =>
        writing(ViewStore.upsert(table, subject, String(row.data, "UTF-8")))
      case ViewOutcome.DeleteRow =>
        writing(ViewStore.delete(table, subject))
      case ViewOutcome.Ignore =>
        Future.successful(Done)
      case ViewOutcome.Rows(_) =>
        Future.failed(
          IllegalStateException("a plain view answered with rows, which only a keyed view may")
        )

  def loadRow(database: Database, subject: String): Future[Option[Array[Byte]]] =
    database
      .query(ViewStore.selectByKey(table, subject))(r =>
        r.get("payload", classOf[String]).getBytes("UTF-8")
      )
      .map(_.headOption)

/**
 * A keyed view in another process or a module: each change is sent with the component it came from,
 * and no row, and answered with the rows to write and delete by key. Hosted by the same
 * `KeyedViewEventHandler` and `KeyedViewStateHandler` a Scala keyed view is, so the lock, the
 * one-change-at-a-time rule and the all-or-nothing write are one implementation.
 */
private[ankka] final class RemoteKeyedView(
    val descriptor: RemoteKeyedViewDescriptor,
    conversation: Conversation,
    observability: Observability
)(using ec: ExecutionContext):
  import RemoteProjection.*

  private val componentRef = observability.names.intern(descriptor.componentId.toString)

  def handle(source: ComponentId)(
      subject: String,
      sequence: Long,
      change: Option[(Array[Byte], String)]
  ): Future[Vector[(String, Option[String])]] =
    val handler = source.toString
    val span = observability.recorder.begin(
      traceId = Trace.mint(),
      parentSpanId = 0L,
      componentRef = componentRef,
      handlerRef = observability.names.intern(handler)
    )
    conversation
      .handleView(
        ViewRequest(
          descriptor.componentId,
          change.map((bytes, manifest) =>
            Payload(Payload.contentTypeFor(manifest), manifest, bytes)
          ),
          CallOrigin.into(
            Trace.into(changeMetadata(subject, sequence), span.traceId, span.id),
            CallOrigin(descriptor.componentId.toString, handler)
          ),
          None,
          Some(source)
        )
      )
      .transform { result =>
        observability.recorder
          .complete(span, if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed)
        result
      }
      .flatMap {
        case ViewOutcome.Rows(changes) =>
          Future.successful(changes.map((key, row) => key -> row.map(p => String(p.data, "UTF-8"))))
        case ViewOutcome.Ignore => Future.successful(Vector.empty)
        case other =>
          Future.failed(
            IllegalStateException(
              s"keyed view '${descriptor.componentId}' answered $other; a keyed view answers with rows"
            )
          )
      }

/** Exactly-once over an event sourced entity: the row and the offset in one transaction. */
private[ankka] final class RemoteViewEventHandler(view: RemoteView, guard: EntityViewGuard)(using
    ec: ExecutionContext
) extends R2dbcHandler[EventEnvelope[JournalRecord]]:
  import RemoteProjection.*

  def process(session: R2dbcSession, envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event
    record.kind match
      // A TTL being set is a storage fact, not a domain change — nothing to project.
      case JournalRecord.KindExpiry => Future.successful(Done)
      case kind =>
        val change = if kind == JournalRecord.KindDomain then Some(payloadOf(record)) else None
        guard
          .inSession(session)
          .flatMap(_ => ProjectionSupport.loadRow(session, view.table, subject, Serializer.bytes))
          .flatMap { row =>
            view.decide(subject, envelope.sequenceNr, change, row).flatMap { outcome =>
              ProjectionSupport
                .applyView(session, view.table, subject, toEffect(outcome), Serializer.bytes)
            }
          }

/** At-least-once over a key value entity's state changes. */
private[ankka] final class RemoteViewStateHandler(
    view: RemoteView,
    database: Database,
    guard: EntityViewGuard
)(using
    ec: ExecutionContext
) extends Handler[DurableStateChange[StateRecord]]:
  import RemoteProjection.*

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)
    val (payload, revision) = change match
      // A deletion is a state marked deleted (`KeyValueEntityHost.Stored`).
      case updated: UpdatedDurableState[StateRecord] if updated.value.deleted =>
        (None, updated.revision)
      case updated: UpdatedDurableState[StateRecord] =>
        (Some(payloadOf(updated.value)), updated.revision)
      case deleted: DeletedDurableState[StateRecord] => (None, deleted.revision)
    view.loadRow(database, subject).flatMap { row =>
      view
        .decide(subject, revision, payload, row)
        .flatMap(
          view.apply(
            database,
            subject,
            _,
            Some(fragment =>
              database.inTransaction(tx =>
                guard.inTransaction(tx).flatMap(_ => tx.execute(fragment)).map(_ => Done)
              )
            )
          )
        )
    }

/**
 * At-least-once over a broker topic; the offset lives with the broker. Every write goes through
 * `guard`, as an in-process view's does.
 */
private[ankka] final class RemoteViewTopicHandler(
    view: RemoteView,
    database: Database,
    guard: ViewGuard
)(using
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
          view
            .decide(subject, 0L, Some(payload), row, ProjectionSupport.carried(message))
            .flatMap(view.apply(database, subject, _, Some(guard.write)))
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
  def handle(
      subject: String,
      sequence: Long,
      change: Option[Payload],
      parent: Option[TraceContext] = None,
      eventId: Option[String] = None
  ): Future[Done] =
    val span    = RemoteProjection.begin(observability, componentRef, handlerRef, parent)
    val context = Some(span.context)
    conversation
      .handleConsumer(
        ConsumerRequest(
          descriptor.componentId,
          change,
          CallOrigin.into(
            Trace.into(consumerMetadata(subject, sequence), span.context),
            CallOrigin(descriptor.componentId.toString, "on-message")
          )
        )
      )
      .transform { result =>
        observability.recorder
          .complete(span, if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed)
        result
      }
      // Whatever went wrong between here and the process — a reply too large for the transport
      // among them — say whose change it was: the projection only logs what it is given.
      .recoverWith { case NonFatal(failure) =>
        Future.failed(
          IllegalStateException(
            s"consumer '${descriptor.componentId}' could not handle the change of '$subject' " +
              s"at sequence $sequence: ${failure.getMessage}",
            failure
          )
        )
      }
      .flatMap {
        case ConsumerOutcome.ProduceAll(messages) if messages.isEmpty => Future.successful(Done)
        case ConsumerOutcome.ProduceAll(messages) =>
          (descriptor.producesTo, publisher) match
            case (Some(topic), Some(target)) =>
              ProjectionSupport.publishAll(
                descriptor.componentId,
                subject,
                topic,
                target,
                messages.zipWithIndex.map((m, index) =>
                  ProjectionSupport.Encoded(
                    m.payload.data,
                    ProjectionSupport.stamped(
                      ProjectionSupport.typed(
                        ProjectionSupport
                          .withEventId(m.metadata, eventId, Some(index))
                          .set(PayloadKeys.Manifest, m.payload.manifest)
                          .set(PayloadKeys.ContentType, m.payload.contentType),
                        descriptor.publication.flatMap(_.contract)
                      ),
                      context
                    ),
                    m.key
                  )
                )
              )
            case _ =>
              Future.failed(
                IllegalStateException(
                  s"consumer '${descriptor.componentId}' produced ${messages.size} messages " +
                    "but has no publish target configured"
                )
              )
        case ConsumerOutcome.Produce(payload, metadata) =>
          (descriptor.producesTo, publisher) match
            case (Some(topic), Some(target)) =>
              // `ce-subject` defaults to the source entity id so per-entity ordering survives
              // the hop onto a partition; the manifest travels so a topic-sourced remote
              // component can decode what it gets.
              val enriched = ProjectionSupport.typed(
                ProjectionSupport
                  .withEventId(
                    if metadata.subject.isDefined then metadata else metadata.withSubject(subject),
                    eventId,
                    None
                  )
                  .set(PayloadKeys.Manifest, payload.manifest)
                  .set(PayloadKeys.ContentType, payload.contentType),
                descriptor.publication.flatMap(_.contract)
              )
              target.publish(topic, payload.data, ProjectionSupport.stamped(enriched, context))
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

private[ankka] final class RemoteConsumerEventHandler(
    consumer: RemoteConsumer,
    lines: HistoryLines
) extends Handler[EventEnvelope[JournalRecord]]:
  import RemoteProjection.*

  def process(envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event
    val eventId = lines
      .lineOf(java.time.Instant.ofEpochMilli(envelope.timestamp))
      .map(HistoryLines.messageId(_, envelope.persistenceId, envelope.sequenceNr))
    record.kind match
      case JournalRecord.KindDomain =>
        consumer.handle(subject, envelope.sequenceNr, Some(payloadOf(record)), eventId = eventId)
      case JournalRecord.KindDeleted =>
        consumer.handle(subject, envelope.sequenceNr, None, eventId = eventId)
      case _ => Future.successful(Done)

private[ankka] final class RemoteConsumerStateHandler(
    consumer: RemoteConsumer,
    lines: HistoryLines
) extends Handler[DurableStateChange[StateRecord]]:
  import RemoteProjection.*

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)
    def eventId(revision: Long) = lines
      .lineOf(HistoryLines.writtenAt(change))
      .map(HistoryLines.messageId(_, change.persistenceId, revision))
    change match
      // A deletion is a state marked deleted (`KeyValueEntityHost.Stored`).
      case updated: UpdatedDurableState[StateRecord] if updated.value.deleted =>
        consumer.handle(subject, updated.revision, None, eventId = eventId(updated.revision))
      case updated: UpdatedDurableState[StateRecord] =>
        consumer.handle(
          subject,
          updated.revision,
          Some(payloadOf(updated.value)),
          eventId = eventId(updated.revision)
        )
      case deleted: DeletedDurableState[StateRecord] =>
        consumer.handle(subject, deleted.revision, None, eventId = eventId(deleted.revision))

private[ankka] final class RemoteConsumerTopicHandler(consumer: RemoteConsumer):

  def process(message: IncomingMessage): Future[Done] =
    val payload = Payload(
      message.metadata.get(PayloadKeys.ContentType).getOrElse(Payload.Json),
      message.metadata.get(PayloadKeys.Manifest).getOrElse(""),
      message.payload
    )
    consumer.handle(
      message.subject.getOrElse(""),
      0L,
      Some(payload),
      ProjectionSupport.carried(message)
    )
