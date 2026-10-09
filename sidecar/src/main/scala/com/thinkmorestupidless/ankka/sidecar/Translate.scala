package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.agent.{
  AgentPlan,
  GuardrailRequest,
  GuardrailResult,
  PlanReply,
  PlanRequest as PbPlanRequest,
  TaskResultVerdict as PbVerdict,
  ToolResult
}
import ankka.protocol.v1.consumer.{ConsumerEffect, ConsumerRequest as PbConsumerRequest}
import ankka.protocol.v1.endpoint.{HttpReply, HttpRequest as PbHttpRequest}
import ankka.protocol.v1.timed_action.{
  TimedActionEffect,
  TimedActionRequest as PbTimedActionRequest
}
import ankka.protocol.v1.view.{RowChange, ViewEffect, ViewRequest as PbViewRequest}
import ankka.protocol.v1.event_sourced.EventSourcedOut
import ankka.protocol.v1.key_value.KeyValueOut
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.workflow.{StepOutcome as PbStepOutcome, StepRef as PbStepRef, WorkflowOut}
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.effect.{Retention, StepOutcome, StepRef}
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.runtime.remote.*

import scala.concurrent.duration.*

/**
 * Between the protocol's generated messages and the runtime's plain values, one way and the other.
 * One copy, shared by every conversation — the gRPC one with a process and the one with a module —
 * so the two cannot disagree about what a message means.
 */
private[sidecar] object Translate:
  def toCode(code: ErrorCode): pb.ErrorCode = code match
    case ErrorCode.BadRequest   => pb.ErrorCode.BAD_REQUEST
    case ErrorCode.Unauthorized => pb.ErrorCode.UNAUTHORIZED
    case ErrorCode.Forbidden    => pb.ErrorCode.FORBIDDEN
    case ErrorCode.NotFound     => pb.ErrorCode.NOT_FOUND
    case ErrorCode.Conflict     => pb.ErrorCode.CONFLICT
    case ErrorCode.Timeout      => pb.ErrorCode.TIMEOUT
    case ErrorCode.Unavailable  => pb.ErrorCode.UNAVAILABLE
    case ErrorCode.Internal     => pb.ErrorCode.INTERNAL
  def toPayload(p: Payload): pb.Payload =
    pb.Payload(p.contentType, p.manifest, ByteString.copyFrom(p.data))
  def fromPayload(p: pb.Payload): Payload = Payload(p.contentType, p.manifest, p.data.toByteArray)
  def toMetadata(m: Metadata): pb.Metadata =
    pb.Metadata(m.toSeq.map((k, v) => pb.Metadata.Entry(k, v)))
  def fromMetadata(m: Option[pb.Metadata]): Metadata =
    Metadata(m.toSeq.flatMap(_.entries).map(e => e.key -> e.value).toVector)
  def fromCode(code: pb.ErrorCode): ErrorCode = code match
    case pb.ErrorCode.BAD_REQUEST  => ErrorCode.BadRequest
    case pb.ErrorCode.UNAUTHORIZED => ErrorCode.Unauthorized
    case pb.ErrorCode.FORBIDDEN    => ErrorCode.Forbidden
    case pb.ErrorCode.NOT_FOUND    => ErrorCode.NotFound
    case pb.ErrorCode.CONFLICT     => ErrorCode.Conflict
    case pb.ErrorCode.TIMEOUT      => ErrorCode.Timeout
    case pb.ErrorCode.UNAVAILABLE  => ErrorCode.Unavailable
    case _                         => ErrorCode.Internal
  def fromError(e: pb.Error): CommandError = CommandError(e.message, fromCode(e.code))
  def fromFailure(f: pb.Failure): ProcessFailure =
    ProcessFailure(
      f.commandId,
      f.error.map(fromError).getOrElse(CommandError("unspecified failure", ErrorCode.Internal))
    )
  def fromRetention(r: Option[pb.Retention]): Option[Retention] = r.flatMap { r =>
    r.retention match
      case pb.Retention.Retention.DeleteNow(_)   => Some(Retention.DeleteNow)
      case pb.Retention.Retention.ExpireAfter(e) => Some(Retention.ExpireAfter(e.millis.millis))
      case pb.Retention.Retention.Empty          => None
  }
  def fromOutcome(o: Option[pb.Outcome]): RemoteOutcome = o.map(_.outcome) match
    case Some(pb.Outcome.Outcome.Reply(r)) =>
      RemoteOutcome.Reply(
        r.payload
          .map(fromPayload)
          .getOrElse(Payload(Payload.Binary, "unit", Array.emptyByteArray)),
        fromMetadata(r.metadata)
      )
    case Some(pb.Outcome.Outcome.Error(e)) => RemoteOutcome.Error(fromError(e))
    case _                                 => RemoteOutcome.NoReply
  def fromStepRef(s: PbStepRef): StepOutcome.TransitionTo =
    StepOutcome.TransitionTo(StepRef(s.step, s.input.map(_.toByteArray)))
  def fromStepOutcome(o: Option[PbStepOutcome]): StepOutcome = o.map(_.outcome) match
    case Some(PbStepOutcome.Outcome.TransitionTo(s)) => fromStepRef(s)
    case Some(PbStepOutcome.Outcome.Pause(p)) =>
      StepOutcome.Pause(
        p.afterMillis.map(_.millis),
        p.onTimeout.map(s => StepRef(s.step, s.input.map(_.toByteArray)))
      )
    case Some(PbStepOutcome.Outcome.Fail(e)) => StepOutcome.Fail(fromError(e))
    case _                                   => StepOutcome.End
  def fromEventSourcedReply(r: EventSourcedOut.Reply): Reply =
    Reply(
      r.commandId,
      r.events.map(fromPayload).toVector,
      None,
      None,
      fromRetention(r.retention),
      fromOutcome(r.outcome),
      r.snapshot.map(fromPayload)
    )
  def fromKeyValueReply(r: KeyValueOut.Reply): Reply =
    Reply(
      r.commandId,
      Vector.empty,
      r.newState.map(fromPayload),
      None,
      fromRetention(r.retention),
      fromOutcome(r.outcome),
      None
    )
  def fromWorkflowReply(r: WorkflowOut.Reply): Reply =
    Reply(
      r.commandId,
      Vector.empty,
      r.newState.map(fromPayload),
      r.transition.map(fromStepRef),
      None,
      fromOutcome(r.outcome),
      None
    )
  def fromStepReply(r: WorkflowOut.StepReply): StepReply =
    StepReply(r.commandId, r.newState.map(fromPayload), fromStepOutcome(r.next))
  def toHttpRequest(r: HttpForward): PbHttpRequest =
    PbHttpRequest(
      r.endpointId,
      r.routeId,
      r.pathArgs,
      r.query.map((n, v) => PbHttpRequest.Pair(n, v)),
      r.headers.map((n, v) => PbHttpRequest.Pair(n, v)),
      r.contentType,
      ByteString.copyFrom(r.body),
      r.principal.map(p =>
        ankka.protocol.v1.endpoint
          .Principal(p.subject, p.name, p.email, p.emailVerified, p.roles.toSeq, p.claims, p.issuer)
      ),
      Some(toMetadata(r.metadata)),
      Some(toCaller(r.caller))
    )

  /**
   * The request that opened a socket, as the process is sent it first. Its metadata states the
   * protocol, as a consumer's request does, so a later SDK knows what this runtime accepts on the
   * socket before it sends anything newer.
   */
  def toSocketOpen(r: HttpForward): PbHttpRequest =
    toHttpRequest(
      r.copy(metadata = r.metadata.set(WireProtocol.MetadataKey, WireProtocol.Version))
    )

  private def toCaller(c: RemoteCaller): ankka.protocol.v1.endpoint.Caller =
    import ankka.protocol.v1.endpoint.{Caller as PbCaller, MachineCaller, ServiceCaller}
    import ankka.protocol.v1.payload.Empty
    c match
      case RemoteCaller.Gateway => PbCaller(PbCaller.Kind.Gateway(Empty()))
      case RemoteCaller.Service(p, name) =>
        PbCaller(PbCaller.Kind.Service(ServiceCaller(p, name)))
      case RemoteCaller.Machine(organization, name) =>
        PbCaller(PbCaller.Kind.Machine(MachineCaller(organization, name)))
      case RemoteCaller.Local => PbCaller(PbCaller.Kind.Local(Empty()))

  // ── The stateless calls: what is sent, and what the answer means ──────────

  def toViewRequest(request: ViewRequest): PbViewRequest =
    PbViewRequest(
      componentId = request.componentId,
      event = request.event.map(toPayload),
      metadata = Some(toMetadata(request.metadata)),
      row = request.row.map(toPayload),
      deleted = request.event.isEmpty,
      sourceId = request.sourceId.map(_.toString)
    )

  def fromViewEffect(effect: ViewEffect): ViewOutcome = effect.effect match
    case ViewEffect.Effect.UpdateRow(row) => ViewOutcome.UpdateRow(fromPayload(row))
    case ViewEffect.Effect.DeleteRow(_)   => ViewOutcome.DeleteRow
    case ViewEffect.Effect.Ignore(_)      => ViewOutcome.Ignore
    case ViewEffect.Effect.Rows(rows) =>
      ViewOutcome.Rows(rows.changes.toVector.map { change =>
        change.key -> (change.change match
          case RowChange.Change.Upsert(row) => Some(fromPayload(row))
          case _                            => None)
      })
    case ViewEffect.Effect.Empty => ViewOutcome.Ignore

  def toConsumerRequest(request: ConsumerRequest): PbConsumerRequest =
    PbConsumerRequest(
      componentId = request.componentId,
      message = request.message.map(toPayload),
      metadata = Some(toMetadata(request.metadata)),
      deleted = request.message.isEmpty
    )

  def fromConsumerEffect(effect: ConsumerEffect): ConsumerOutcome = effect.effect match
    case ConsumerEffect.Effect.Produce(p) =>
      ConsumerOutcome.Produce(fromPayload(p.getPayload), fromMetadata(p.metadata))
    case ConsumerEffect.Effect.ProduceAll(all) =>
      ConsumerOutcome.ProduceAll(
        all.messages
          .map(m => ProducedMessage(fromPayload(m.getPayload), fromMetadata(m.metadata), m.key))
          .toVector
      )
    case ConsumerEffect.Effect.Done(_)   => ConsumerOutcome.Done
    case ConsumerEffect.Effect.Ignore(_) => ConsumerOutcome.Ignore
    // A case this runtime does not know arrives as no case at all, and is read as "ignore". It is
    // why a runtime says what it speaks on the request (`WireProtocol.MetadataKey`) and an SDK
    // must not answer with a case the runtime did not say it accepts.
    case ConsumerEffect.Effect.Empty => ConsumerOutcome.Ignore

  def toTimedActionRequest(request: TimedActionRequest): PbTimedActionRequest =
    PbTimedActionRequest(
      request.componentId,
      request.name,
      Some(if request.payload.isEmpty then pb.Payload() else pb.Payload.parseFrom(request.payload)),
      Some(toMetadata(request.metadata))
    )

  def fromTimedActionEffect(effect: TimedActionEffect): Either[CommandError, Unit] =
    effect.effect match
      case TimedActionEffect.Effect.Fail(e) => Left(fromError(e))
      case _                                => Right(())

  def toPlanRequest(request: PlanRequest): PbPlanRequest =
    PbPlanRequest(
      request.componentId,
      request.sessionId,
      request.name,
      Some(toPayload(request.payload)),
      Some(toMetadata(request.metadata))
    )

  def fromPlanReply(reply: PlanReply): Either[ProcessFailure, RemotePlan] = reply.message match
    case PlanReply.Message.Plan(p)    => Right(fromPlan(p))
    case PlanReply.Message.Failure(f) => Left(fromFailure(f))
    case PlanReply.Message.Empty =>
      Left(ProcessFailure(0L, CommandError("the process answered no plan", ErrorCode.Internal)))

  def fromPlan(p: AgentPlan): RemotePlan =
    RemotePlan(
      model = p.model,
      system = p.system,
      user = p.user,
      context = p.context.toVector,
      sessionMemory = p.memory == AgentPlan.Memory.SESSION,
      tools = p.tools.toVector,
      jsonShape = p.responseShape.flatMap(_.shape.json).map(_.schemaHint),
      guardrails = p.guardrails.toVector,
      failure = p.failure.map(fromError)
    )

  def fromToolResult(result: ToolResult): Either[String, String] = result.result match
    case ToolResult.Result.Ok(text)    => Right(text)
    case ToolResult.Result.Error(text) => Left(text)
    case ToolResult.Result.Empty       => Right("")

  def toGuardrailRequest(
      componentId: ComponentId,
      sessionId: String,
      guardrail: String,
      stage: GuardrailStage,
      text: String,
      metadata: Metadata
  ): GuardrailRequest =
    GuardrailRequest(
      componentId = componentId,
      sessionId = sessionId,
      guardrail = guardrail,
      stage = stage match
        case GuardrailStage.Input     => GuardrailRequest.Stage.INPUT
        case GuardrailStage.Output    => GuardrailRequest.Stage.OUTPUT
        case GuardrailStage.Result(_) => GuardrailRequest.Stage.RESULT
      ,
      text = text,
      metadata = Some(toMetadata(metadata)),
      tool = stage match
        case GuardrailStage.Result(tool) => tool
        case _                           => None
    )

  def fromGuardrailResult(result: GuardrailResult): Either[String, Unit] = result.result match
    case GuardrailResult.Result.Block(reason) => Left(reason)
    case _                                    => Right(())

  /** What a process or a module answered about a task's result: decoded, then held to its rules. */
  def fromTaskResultVerdict(answer: PbVerdict): TaskResultVerdict = answer.verdict match
    case PbVerdict.Verdict.Malformed(problem) => TaskResultVerdict.Malformed(problem)
    case PbVerdict.Verdict.Reject(r)          => TaskResultVerdict.Reject(r.rule, r.reason)
    case _                                    => TaskResultVerdict.Accept

  def fromHttpReply(reply: HttpReply): Either[ProcessFailure, HttpResult] = reply.message match
    case HttpReply.Message.Response(r) =>
      Right(
        HttpResult(
          r.status,
          r.contentType,
          r.body.toByteArray,
          r.headers.map(p => p.name -> p.value).toVector
        )
      )
    case HttpReply.Message.Failure(f) => Left(fromFailure(f))
    case HttpReply.Message.Empty =>
      Left(
        ProcessFailure(0L, CommandError("empty HTTP reply from the process", ErrorCode.Internal))
      )
