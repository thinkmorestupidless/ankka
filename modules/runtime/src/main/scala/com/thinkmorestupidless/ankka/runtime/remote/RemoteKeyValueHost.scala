package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, MethodName}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Span,
  EntityProtocol,
  MetaEntry,
  Observability,
  SpanOutcome,
  StateRecord,
  Trace
}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{Behavior, PostStop}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.state.scaladsl.{
  DurableStateBehavior,
  Effect as PekkoEffect,
  ReplyEffect
}
import org.apache.pekko.persistence.typed.SnapshotAdapter

import scala.util.{Failure, Success, Try}

/**
 * Hosts one remote key value entity kind on cluster sharding: `RemoteEventSourcedHost` without the
 * journal. The durable state is the process's latest bytes, stored as the same `StateRecord` the
 * in-process host writes, so the two read each other's.
 *
 * The same explicit queue and `PostStop` answer as the event sourced host; see there for why.
 */
private[ankka] object RemoteKeyValueHost:

  final case class RemoteState(value: Option[Payload], deleted: Boolean, expiryMillis: Long):
    def expired(nowMillis: Long): Boolean = expiryMillis > 0 && nowMillis >= expiryMillis
    def fresh(nowMillis: Long): Boolean   = deleted || value.isEmpty || expired(nowMillis)

  private final case class Replied(
      id: Long,
      invoke: EntityProtocol.Invoke,
      handler: RemoteHandler,
      span: com.thinkmorestupidless.ankka.runtime.Span,
      result: Try[Either[ProcessFailure, Reply]]
  ) extends EntityProtocol.ModuleCommand

  def behavior(
      descriptor: RemoteKeyValueDescriptor,
      entityId: EntityId,
      conversation: Conversation
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      val observability = Observability(ctx.system)
      val componentRef  = observability.names.intern(descriptor.componentId.toString)

      var session: Option[InstanceSession]        = None
      var inFlight: Option[EntityProtocol.Invoke] = None
      var queued: Vector[EntityProtocol.Invoke]   = Vector.empty
      var nextId                                  = 1L

      val component = descriptor.componentId.toString
      // When the command in flight was sent: there is at most one, so one number is enough.
      var sentNanos = 0L

      /** Answered here, by the host: no handler ran, so the call was not delivered. */
      def unavailable(invoke: EntityProtocol.Invoke, why: String): Unit =
        observability.undelivered(
          MetaEntry.toMetadata(invoke.metadata),
          component,
          descriptor.handler(MethodName(invoke.method)).map(_ => invoke.method)
        )
        invoke.replyTo ! EntityProtocol.Rejected(CommandError(why, ErrorCode.Unavailable))

      /** The span's ending, and the same ending counted as a call from whoever sent it. */
      def finish(span: Span, invoke: EntityProtocol.Invoke, outcome: SpanOutcome): Unit =
        observability.recorder.complete(span, outcome)
        observability.handled(
          MetaEntry.toMetadata(invoke.metadata),
          component,
          invoke.method,
          outcome,
          System.nanoTime() - sentNanos
        )

      def dropSession(): Unit =
        session.foreach(s => Try(s.close()))
        session = None

      def open(state: RemoteState): InstanceSession =
        val now = System.currentTimeMillis()
        val s = conversation.open(
          Init(
            descriptor.kind,
            descriptor.componentId,
            entityId,
            if state.fresh(now) then None else state.value.map(p => Snapshot(0L, p))
          )
        )
        session = Some(s)
        s

      def send(invoke: EntityProtocol.Invoke, state: RemoteState): Unit =
        val s = session.getOrElse(open(state))
        descriptor.handler(MethodName(invoke.method)) match
          case None =>
            observability.undelivered(MetaEntry.toMetadata(invoke.metadata), component, None)
            invoke.replyTo ! EntityProtocol.Rejected(
              CommandError(
                s"no handler '${invoke.method}' on component '${descriptor.componentId}'",
                ErrorCode.NotFound
              )
            )
            drain(state)
          case Some(handler) =>
            val id = nextId
            nextId += 1
            inFlight = Some(invoke)
            sentNanos = System.nanoTime()
            val metadata = MetaEntry.toMetadata(invoke.metadata)
            val span = observability.recorder.begin(
              traceId = Trace.traceIdOf(metadata).getOrElse(Trace.mint()),
              parentSpanId = Trace.parentSpanIdOf(metadata).getOrElse(0L),
              componentRef = componentRef,
              handlerRef = observability.names.intern(invoke.method)
            )
            val payload = Payload(
              metadata.get(PayloadKeys.ContentType).getOrElse(Payload.Json),
              metadata.get(PayloadKeys.Manifest).getOrElse(""),
              invoke.payload
            )
            // The span is the parent of anything the process calls back for, and this handler is
            // who such a call is from: not whoever called this one.
            val carried = CallOrigin.into(
              Trace.into(metadata, span.traceId, span.id),
              CallOrigin(component, invoke.method)
            )
            val command = Command(id, handler.name, payload, carried, false)
            ctx.pipeToSelf(s.command(command))(Replied(id, invoke, handler, span, _))

      def drain(state: RemoteState): Unit =
        if inFlight.isEmpty then
          queued.headOption.foreach { next =>
            queued = queued.tail
            send(next, state)
          }

      def onCommand(state: RemoteState, command: EntityProtocol.Command): ReplyEffect[RemoteState] =
        command match
          case invoke: EntityProtocol.Invoke =>
            if state.expired(System.currentTimeMillis()) && session.isDefined && inFlight.isEmpty
            then dropSession()
            if inFlight.isDefined then queued :+= invoke
            else send(invoke, state)
            PekkoEffect.none[RemoteState].thenNoReply()

          case Replied(id, invoke, handler, span, result) =>
            inFlight = None
            result match
              case Failure(e) =>
                finish(span, invoke, SpanOutcome.Failed)
                dropSession()
                PekkoEffect
                  .none[RemoteState]
                  .thenRun((s: RemoteState) => drain(s))
                  .thenReply(invoke.replyTo)(_ =>
                    EntityProtocol.Rejected(CommandError(e.getMessage, ErrorCode.Internal))
                  )
              case Success(Left(failure)) =>
                val outcome =
                  if failure.error.code == ErrorCode.Timeout then SpanOutcome.TimedOut
                  else SpanOutcome.Failed
                finish(span, invoke, outcome)
                if failure.error.code == ErrorCode.Timeout ||
                  failure.error.code == ErrorCode.Unavailable
                then dropSession()
                PekkoEffect
                  .none[RemoteState]
                  .thenRun((s: RemoteState) => drain(s))
                  .thenReply(invoke.replyTo)(_ => EntityProtocol.Rejected(failure.error))
              case Success(Right(reply)) =>
                RemoteEffect.materialise(reply, handler, id, snapshotRequested = false) match
                  case Left(violation) =>
                    ctx.log.warn(
                      "{}/{}: protocol violation from the process: {}",
                      descriptor.componentId,
                      entityId,
                      violation.getMessage
                    )
                    finish(span, invoke, SpanOutcome.Failed)
                    dropSession()
                    PekkoEffect
                      .none[RemoteState]
                      .thenRun((s: RemoteState) => drain(s))
                      .thenReply(invoke.replyTo)(_ =>
                        EntityProtocol.Rejected(
                          CommandError(violation.getMessage, ErrorCode.Internal)
                        )
                      )
                  case Right(m) =>
                    m.reply match
                      case Left(error) =>
                        finish(span, invoke, SpanOutcome.Refused)
                        PekkoEffect
                          .none[RemoteState]
                          .thenRun((s: RemoteState) => drain(s))
                          .thenReply(invoke.replyTo)(_ => EntityProtocol.Rejected(error))
                      case Right(answer) =>
                        finish(span, invoke, SpanOutcome.Ok)
                        val stored = m.retention match
                          // A recorded state, not a removed row: the deletion is a change at
                          // the next revision, which views and consumers are told of, and the
                          // revisions of an entity created again go on from it.
                          case Some(Retention.DeleteNow) =>
                            PekkoEffect.persist(RemoteState(None, deleted = true, 0L))
                          case Some(Retention.ExpireAfter(d)) =>
                            PekkoEffect.persist(
                              RemoteState(
                                m.newState.orElse(state.value),
                                deleted = false,
                                System.currentTimeMillis() + d.toMillis
                              )
                            )
                          case None =>
                            m.newState match
                              case Some(value) =>
                                PekkoEffect.persist(RemoteState(Some(value), false, 0L))
                              case None => PekkoEffect.none[RemoteState]
                        val next = stored.thenRun { (after: RemoteState) =>
                          if m.retention.contains(Retention.DeleteNow) then dropSession()
                          drain(after)
                        }
                        answer match
                          case Some((payload, meta)) =>
                            next.thenReply(invoke.replyTo)(_ =>
                              EntityProtocol.Succeeded(
                                payload.data,
                                MetaEntry.from(
                                  meta
                                    .set(PayloadKeys.Manifest, payload.manifest)
                                    .set(PayloadKeys.ContentType, payload.contentType)
                                )
                              )
                            )
                          case None => next.thenNoReply()

          case request: EntityProtocol.InvokeStream =>
            request.tokens ! EntityProtocol.StreamFailed(
              CommandError(
                s"entity '${descriptor.componentId}' does not support streaming",
                ErrorCode.BadRequest
              )
            )
            PekkoEffect.noReply

          case _ => PekkoEffect.unhandled.thenNoReply()

      DurableStateBehavior
        .withEnforcedReplies[EntityProtocol.Command, RemoteState](
          persistenceId = PersistenceId(descriptor.componentId, entityId),
          emptyState = RemoteState(None, deleted = false, expiryMillis = 0L),
          commandHandler = onCommand
        )
        .snapshotAdapter(snapshotAdapter)
        .receiveSignal { case (_, PostStop) =>
          (inFlight.toVector ++ queued).foreach(unavailable(_, "the instance is stopping"))
          inFlight = None
          queued = Vector.empty
          dropSession()
        }
    }

  private val snapshotAdapter: SnapshotAdapter[RemoteState] =
    new SnapshotAdapter[RemoteState]:
      def toJournal(state: RemoteState): Any =
        StateRecord(
          state.value.map(_.manifest).getOrElse(""),
          state.value.map(_.data).getOrElse(Array.emptyByteArray),
          state.deleted,
          state.expiryMillis
        )

      def fromJournal(from: Any): RemoteState =
        from match
          case record: StateRecord =>
            RemoteState(
              if record.manifest.isEmpty then None
              else
                Some(
                  Payload(Payload.contentTypeFor(record.manifest), record.manifest, record.payload)
                )
              ,
              record.deleted,
              record.expiryMillis
            )
          case other =>
            throw IllegalStateException(s"unexpected state record ${other.getClass.getName}")
