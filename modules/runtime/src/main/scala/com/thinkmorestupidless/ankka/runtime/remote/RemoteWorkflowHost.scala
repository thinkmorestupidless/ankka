package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.{Outcome, WorkflowEffect, WorkflowStepEffect}
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  EntityId,
  ErrorCode,
  MethodName,
  Serializer
}
import com.thinkmorestupidless.ankka.sdk.{
  CommandHandle,
  HandlerBinding,
  RawStepHandle,
  StepHandleLike,
  Workflow,
  WorkflowContext,
  WorkflowDescriptor,
  WorkflowSettings
}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * A remote workflow is the in-process `WorkflowEngine` — every transition journaled, steps run,
 * retried and timed out exactly as for a Scala workflow — driving a *proxy* `Workflow[Payload]`
 * whose command handlers and steps cross the conversation to the process instead of running Scala
 * code. The engine is untouched; what it calls is different.
 *
 * The process keeps its copy of the state for the life of a conversation, which is per instance and
 * survives across commands and steps. The engine creates a fresh proxy per command, so the
 * conversations live in a map keyed by workflow id, dropped on a fault or a violation and reopened
 * with the engine's recovered state on the next command.
 *
 * One honest limitation: a workflow *command* handler answers the engine synchronously, so the
 * proxy waits for the process on the entity's thread, bounded by the command timeout. Steps run on
 * the virtual-thread executor as they do for Scala steps, and are where the long work belongs.
 */
private[ankka] object RemoteWorkflowHost:

  /** The state the engine journals: the process's bytes, or the empty marker before any. */
  val NoState: Payload = Payload(Payload.Json, "", Array.emptyByteArray)

  private val stateSerializer: Serializer[Payload] = new Serializer[Payload]:
    // The manifest the journal records is fixed per kind for the in-process host, but a remote
    // workflow's is the process's; carried inside the bytes so recovery gets it back.
    val manifest = "ankka.remote.workflow"
    def toBytes(value: Payload): Array[Byte] =
      val m = value.manifest.getBytes("UTF-8")
      val c = value.contentType.getBytes("UTF-8")
      java.nio.ByteBuffer
        .allocate(8 + m.length + c.length + value.data.length)
        .putInt(m.length)
        .put(m)
        .putInt(c.length)
        .put(c)
        .put(value.data)
        .array()
    def fromBytes(bytes: Array[Byte]): Payload =
      if bytes.isEmpty then NoState
      else
        val b = java.nio.ByteBuffer.wrap(bytes)
        val m = new Array[Byte](b.getInt); b.get(m)
        val c = new Array[Byte](b.getInt); b.get(c)
        val d = new Array[Byte](b.remaining); b.get(d)
        Payload(String(c, "UTF-8"), String(m, "UTF-8"), d)

  /** One instance's open conversation and the ids it hands out, monotonic per stream. */
  final class Live(val session: InstanceSession):
    private val ids  = new java.util.concurrent.atomic.AtomicLong(1L)
    def nextId: Long = ids.getAndIncrement()

  final class Proxy(
      descriptor: RemoteWorkflowDescriptor,
      context: WorkflowContext,
      sessions: ConcurrentHashMap[EntityId, Live],
      conversation: Conversation,
      commandTimeout: FiniteDuration
  ) extends Workflow[Payload]:

    def emptyState: Payload = NoState

    /** The engine reads these off the instance, as it does for a Scala workflow. */
    override def settings: WorkflowSettings = descriptor.settings

    private def live(): Live =
      sessions.computeIfAbsent(
        context.workflowId,
        _ =>
          val state = currentState
          Live(
            conversation.open(
              Init(
                descriptor.kind,
                descriptor.componentId,
                context.workflowId,
                if state.manifest.isEmpty then None else Some(Snapshot(0L, state))
              )
            )
          )
      )

    private def drop(): Unit =
      Option(sessions.remove(context.workflowId)).foreach(l => Try(l.session.close()))

    private[ankka] def command(
        name: MethodName,
        bytes: Array[Byte]
    ): WorkflowEffect[Payload, Array[Byte]] =
      val handler = descriptor
        .handler(name)
        .getOrElse(throw CommandError(s"no handler '$name'", ErrorCode.NotFound))
      val metadata = commandContext.metadata
      val payload = Payload(
        metadata.get(PayloadKeys.ContentType).getOrElse(Payload.Json),
        metadata.get(PayloadKeys.Manifest).getOrElse(""),
        bytes
      )
      val l  = live()
      val id = l.nextId
      Try(
        Await.result(l.session.command(Command(id, name, payload, metadata, false)), commandTimeout)
      ) match
        case Failure(e) =>
          drop()
          WorkflowEffect.ReadOnly(Outcome.Fail(CommandError(e.getMessage, ErrorCode.Timeout)))
        case Success(Left(failure)) =>
          drop()
          WorkflowEffect.ReadOnly(Outcome.Fail(failure.error))
        case Success(Right(reply)) =>
          RemoteEffect.materialise(reply, handler, id, snapshotRequested = false) match
            case Left(violation) =>
              drop()
              WorkflowEffect.ReadOnly(
                Outcome.Fail(CommandError(violation.getMessage, ErrorCode.Internal))
              )
            case Right(m) =>
              val outcome: Outcome[Payload, Array[Byte]] = m.reply match
                case Left(error) => Outcome.Fail(error)
                case Right(None) => Outcome.NoReply
                case Right(Some((p, meta))) =>
                  Outcome.Reply(
                    _ => p.data,
                    meta
                      .set(PayloadKeys.Manifest, p.manifest)
                      .set(PayloadKeys.ContentType, p.contentType)
                  )
              WorkflowEffect.Changing(
                m.newState,
                reply.transition.map(_.step),
                deleting = false,
                outcome
              )

    /**
     * A fault in the process — the step threw, the process vanished, the reply was for the wrong
     * step — is *thrown* here, so the engine applies the declared recovery (retries, failover) as
     * it does for a Scala step that threw. Only a `fail` the process answered on purpose is the
     * `Fail` outcome, which ends the workflow as it would in Scala.
     */
    private[ankka] def step(name: String, input: Option[Array[Byte]]): WorkflowStepEffect[Payload] =
      val l  = live()
      val id = l.nextId
      // The engine has its own step timeout and fires `StepTimedOut`; this cap only frees the
      // virtual thread if the process vanished without closing the conversation.
      val result = Try(Await.result(l.session.runStep(id, name, input), 10.minutes))
      result.toEither match
        case Left(e) =>
          drop()
          throw CommandError(e.getMessage, ErrorCode.Timeout)
        case Right(Left(failure)) =>
          drop()
          throw failure.error
        case Right(Right(stepReply)) =>
          if stepReply.commandId != id then
            drop()
            throw ProtocolViolation(
              s"step reply for $id expected, got ${stepReply.commandId}"
            )
          else WorkflowStepEffect.Impl(stepReply.newState, stepReply.next)

  /**
   * The real descriptor the engine hosts, with every handler and step going over the conversation.
   */
  def descriptor(
      remote: RemoteWorkflowDescriptor,
      conversation: Conversation,
      commandTimeout: FiniteDuration
  ): WorkflowDescriptor[Proxy, Payload] =
    val sessions = new ConcurrentHashMap[EntityId, Live]()
    val handlers: Map[MethodName, HandlerBinding[Proxy]] =
      remote.handlers.map { (name, h) =>
        name -> new CommandHandle[Proxy, Array[Byte], Array[Byte]](
          remote.componentId,
          name,
          h.readOnly,
          Serializer.bytes,
          Serializer.bytes,
          (proxy, bytes) => proxy.command(name, bytes)
        )
      }
    val steps: Map[String, StepHandleLike[Proxy]] =
      remote.steps.map { step =>
        // The input is whatever the process put in the transition; only the process decodes it.
        step -> new RawStepHandle[Proxy](step, (proxy, input) => proxy.step(step, input))
      }.toMap
    WorkflowDescriptor[Proxy, Payload](
      remote.componentId,
      stateSerializer,
      ctx => new Proxy(remote, ctx, sessions, conversation, commandTimeout),
      handlers,
      steps
    )
