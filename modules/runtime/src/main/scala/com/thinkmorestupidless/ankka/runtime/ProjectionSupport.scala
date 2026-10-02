package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.core.effect.{ConsumerEffect, ViewEffect}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.Done
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcSession

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Shared steps between the four projection handlers. */
private[ankka] object ProjectionSupport:

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

  /**
   * Runs a view's or a consumer's one handler as a span of its own, and as the origin of whatever
   * the handler calls.
   *
   * A trace root, as every projection's work is: it is caught up from a journal or a topic, not
   * continued from whatever wrote the change. Without the span a consumer does not appear in a
   * trace or a metric at all, and without the origin a call it makes is from nobody.
   */
  def handling[A](observability: Observability, component: String, handler: String)(
      body: => A
  ): A =
    val span = observability.recorder.begin(
      traceId = Trace.mint(),
      parentSpanId = 0L,
      componentRef = observability.names.intern(component),
      handlerRef = observability.names.intern(handler)
    )
    var outcome = SpanOutcome.Failed
    try
      val result = Trace.within(span.traceId, span.id, CallOrigin(component, handler))(body)
      outcome = SpanOutcome.Ok
      result
    finally observability.recorder.complete(span, outcome)

  /** Sets up the view's per-change state, runs it, and always clears the context. */
  def runView(
      view: View[Any, Any],
      descriptor: ViewDescriptor[View[Any, Any], Any, Any],
      subject: String,
      sequenceNr: Long,
      row: Option[Any],
      record: JournalRecord,
      observability: Observability
  ): ViewEffect[Any] =
    view._setRow(row)
    view._setContext(Some(SimpleChangeContext(subject, sequenceNr, localOrigin = true)))
    // A projection has no inbound request, so this span is a trace root — correctly so. A view
    // catching up is its own piece of work, not part of whatever wrote the event minutes ago,
    // and threading the writer's trace into it would make one request appear to last for hours.
    try
      handling(observability, descriptor.componentId.toString, ViewDescriptor.OnChange.name) {
        record.kind match
          case JournalRecord.KindDomain =>
            view.onChange(descriptor.source.decoder.fromBytes(record.payload))
          case JournalRecord.KindDeleted => view.onDelete
          // A TTL being set is a storage fact, not a domain change — nothing for a view
          // to project. The row disappears when the deletion itself is journalled.
          case _ => ViewEffect.Ignore
      }
    finally view._setContext(None)

  /** Writes the row change through the projection's transaction. */
  def applyView[A](
      session: R2dbcSession,
      table: String,
      subject: String,
      effect: ViewEffect[A],
      serializer: Serializer[A]
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
   * The most one change's messages may weigh together, as they are published. It is what a
   * sidecar's reply may carry (the transport's limit), applied here too so that how a service is
   * hosted does not decide how much a consumer may produce.
   */
  val MaxResultBytes: Int = 4 * 1024 * 1024

  /** One of several messages, encoded: `key` absent means the message's subject. */
  final case class Encoded(payload: Array[Byte], metadata: Metadata, key: Option[String])

  /**
   * Publishes the several messages a consumer produced for one change.
   *
   * The one place this is done, for a consumer in process, behind a sidecar or in a module. Each
   * message's `ce-subject` defaults to the source's id, as a single message's does, and its record
   * key is the one it names, else that subject. The messages are handed to the publisher in the
   * order given — so records under one key keep their order — and the result completes when the
   * broker has accepted all of them. If any is refused the result fails, the change is not recorded
   * as handled, and it comes again with all its messages. Nothing is published when the result is
   * over the limit or a key is empty.
   */
  def publishAll(
      componentId: ComponentId,
      subject: String,
      topic: String,
      target: MessagePublisher,
      messages: Seq[Encoded]
  ): Future[Done] =
    val enriched = messages.map(m =>
      if m.metadata.subject.isDefined then m else m.copy(metadata = m.metadata.withSubject(subject))
    )
    val size = enriched.iterator.map(weightOf).sum
    if enriched.exists(_.key.contains("")) then
      Future.failed(
        IllegalStateException(
          s"consumer '$componentId' named an empty record key for '$subject'; leave the key " +
            "out to key a message by its subject"
        )
      )
    else if size > MaxResultBytes then
      Future.failed(
        IllegalStateException(
          s"consumer '$componentId' produced ${enriched.size} messages of $size bytes for " +
            s"'$subject'; the messages of one change may be at most $MaxResultBytes bytes (4 MiB)"
        )
      )
    else
      given ExecutionContext = ExecutionContext.parasitic
      val sent = enriched.map { m =>
        try target.publish(topic, m.key, m.payload, m.metadata)
        catch case NonFatal(failure) => Future.failed(failure)
      }
      Future.sequence(sent).map(_ => Done)

  private def weightOf(message: Encoded): Long =
    message.payload.length.toLong + message.key.fold(0)(_.length) +
      message.metadata.toSeq.iterator.map((k, v) => k.length + v.length).sum

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

      case ConsumerEffect.ProduceAll(messages) if messages.isEmpty =>
        Future.successful(Done)

      case ConsumerEffect.ProduceAll(messages) =>
        (descriptor.produceTo, publisher, descriptor.outputSerializer) match
          case (Some(topic), Some(target), Some(serializer)) =>
            publishAll(
              descriptor.componentId,
              subject,
              topic,
              target,
              messages.map(m => Encoded(serializer.toBytes(m.payload), m.metadata, m.key))
            )

          case _ =>
            Future.failed(
              IllegalStateException(
                s"consumer '${descriptor.componentId}' produced ${messages.size} messages but " +
                  "has no publish target configured"
              )
            )

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
