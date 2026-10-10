package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient, WorkflowLifecycle}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import org.apache.pekko.util.Timeout

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/** Caches sharding type keys, which are cheap but not free to build. */
private[ankka] object EntityKeys:
  private val cache = ConcurrentHashMap[String, EntityTypeKey[EntityProtocol.Command]]()

  def forComponent(componentId: ComponentId): EntityTypeKey[EntityProtocol.Command] =
    cache.computeIfAbsent(componentId, id => EntityTypeKey[EntityProtocol.Command](id))

/**
 * Routes component calls through cluster sharding.
 *
 * The only place in ankka that knows a component might live on a different node. Every typed
 * call-site guarantee is established in `ankka-sdk`; this just moves bytes.
 *
 * `resendAfter` is how long a query waits for its answer before it is sent again; see `askQuery`.
 */
private[ankka] final class ShardingTransport(
    sharding: ClusterSharding,
    val askTimeout: FiniteDuration,
    resendAfter: FiniteDuration = ShardingTransport.DefaultResendAfter
)(using system: ActorSystem[?])
    extends CallTransport:

  private given ExecutionContext = system.executionContext

  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit =
    // A stream is a call like any other, and says who made it the same way.
    val command = message.asInstanceOf[EntityProtocol.Command] match
      case stream: EntityProtocol.InvokeStream =>
        stream.copy(metadata =
          MetaEntry.from(Trace.outbound(MetaEntry.toMetadata(stream.metadata)))
        )
      case other => other
    sharding
      .entityRefFor(EntityKeys.forComponent(componentId), entityId)
      .tell(command)

  def ask(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] =
    askWithMetadata(componentId, entityId, method, payload, metadata).map(_._1)(using
      ExecutionContext.parasitic
    )

  override def askWithMetadata(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[(Array[Byte], Metadata)] =
    val call = Call(componentId, entityId, method, payload, metadata)
    answered(call, send(call, askTimeout))

  /**
   * Sends the query again each time `resendAfter` passes with no answer, until `askTimeout` has
   * passed since the first: sharding drops a message that reaches the old node's region after a
   * hand-off began (`ShardRegion` on `HandOff`, "to avoid re-ordering"), so during a rollout a
   * query can be lost with nobody to say so. Every copy sent may run; a query changes nothing, so
   * that costs only the work. The last attempt waits for whatever time is left.
   *
   * An attempt given up on is not counted as unanswered — the caller's call is not over — and the
   * call is counted once, by how it ends.
   */
  override def askQuery(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] =
    val call     = Call(componentId, entityId, method, payload, metadata)
    val deadline = askTimeout.fromNow
    def attempt(): Future[EntityProtocol.Reply] =
      val left = deadline.timeLeft
      if left < resendAfter * 2 then send(call, left.max(1.milli))
      else
        send(call, resendAfter).recoverWith { case _: java.util.concurrent.TimeoutException =>
          attempt()
        }
    answered(call, attempt()).map(_._1)(using ExecutionContext.parasitic)

  /**
   * Waits for a workflow's end by asking it to hold the ask until it ends, and asking again each
   * time it says "not yet" or an ask goes unanswered, until the caller's own deadline. A hold is
   * never longer than half an ask, so the engine answers before the ask gives up on a healthy wait;
   * an ask that is not answered — the workflow's actor stopped, its shard moved, the message was
   * dropped at a hand-off — costs one ask and is asked again wherever the workflow is by then.
   * Nothing about a wait is journalled.
   *
   * One call however many asks it takes: one span from the first ask to the answer, recorded whole
   * when the wait ends, and counted once — handled by the workflow that answered it, or unanswered
   * here when the deadline passes.
   */
  override def awaitEnd(
      componentId: ComponentId,
      entityId: EntityId,
      timeout: FiniteDuration,
      metadata: Metadata
  ): Future[(Array[Byte], Metadata)] =
    if timeout <= Duration.Zero then
      return Future.failed(
        CommandError(
          s"a wait for a workflow's end needs a timeout of more than zero, not $timeout",
          ErrorCode.BadRequest
        )
      )
    val call =
      Call(componentId, entityId, WorkflowLifecycle.AwaitEnd, Array.emptyByteArray, metadata)
    val observability = Observability(system)
    val recorder      = observability.recorder
    val parent        = Trace.currentContext
    val span = recorder.reserve(
      parent.fold(Trace.mintHigh())(_.traceIdHigh),
      parent.fold(Trace.mint())(_.traceId),
      observability.names.intern(componentId.toString),
      observability.names.intern(WorkflowLifecycle.AwaitEnd)
    )
    val parentSpanId = parent.fold(Recorder.UnknownCaller)(_.spanId)
    val deadline     = timeout.fromNow

    def timedOut(): Future[(Array[Byte], Metadata)] =
      observability.unanswered(
        call.origin,
        componentId,
        WorkflowLifecycle.AwaitEnd,
        Unanswered.TimedOut
      )
      Future.failed(
        CommandError(
          s"workflow $componentId '$entityId' had not ended within $timeout",
          ErrorCode.Timeout
        )
      )

    def attempt(): Future[(Array[Byte], Metadata)] =
      val left = deadline.timeLeft
      if left <= Duration.Zero then timedOut()
      else
        val within = left.min(askTimeout)
        sharding
          .entityRefFor(EntityKeys.forComponent(componentId), entityId)
          .ask[EntityProtocol.Reply](replyTo =>
            EntityProtocol.Invoke(
              call.method,
              left.toMillis.max(1L).toString.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              MetaEntry.from(call.carried),
              replyTo
            )
          )(using Timeout(within))
          .transformWith {
            case Success(EntityProtocol.Succeeded(bytes, replyMetadata)) =>
              Future.successful((bytes, MetaEntry.toMetadata(replyMetadata)))
            case Success(failed: EntityProtocol.WorkflowFailed) =>
              Future.failed(failed.toCommandError(componentId.toString, entityId.toString))
            case Success(_: EntityProtocol.NotYet) => attempt()
            case Success(rejected: EntityProtocol.Rejected) =>
              Future.failed(
                if ShardingTransport.fromBeforeAwaiting(rejected, componentId) then
                  CommandError(
                    s"the instance hosting workflow $componentId '$entityId' is from before waiting " +
                      "for a workflow's end; every instance must be at this release",
                    ErrorCode.Unavailable
                  )
                else rejected.toCommandError
              )
            case Failure(_: java.util.concurrent.TimeoutException) => attempt()
            case Failure(NonFatal(other)) =>
              observability.unanswered(
                call.origin,
                componentId,
                WorkflowLifecycle.AwaitEnd,
                Unanswered.Undelivered
              )
              Future.failed(CommandError(other.getMessage, ErrorCode.Unavailable))
            case Failure(fatal) => Future.failed(fatal)
          }

    attempt().andThen { result =>
      val outcome = result match
        case Success(_) => SpanOutcome.Ok
        case Failure(error: CommandError) if error.code == ErrorCode.WorkflowFailed =>
          SpanOutcome.Ok
        case Failure(error: CommandError) => Observability.outcomeOf(error)
        case Failure(_)                   => SpanOutcome.Failed
      recorder.record(span, parentSpanId, SpanKind.Client, outcome)
    }

  /** One call, as made on the calling thread: what it carries, and who made it. */
  private final class Call(
      val componentId: ComponentId,
      val entityId: EntityId,
      val method: MethodName,
      val payload: Array[Byte],
      metadata: Metadata
  ):
    // The caller's span becomes the callee's parent, carried in the metadata that already
    // crosses the sharding boundary. Nothing about the protocol changes: MetaEntry is already
    // serialized, so a trace spans nodes for free.
    val carried: Metadata = Trace.outbound(metadata)
    // Taken here, on the calling thread: the reply is handled on another, which has no origin.
    val origin: Option[CallOrigin] = Trace.currentOrigin

  private def send(call: Call, within: FiniteDuration): Future[EntityProtocol.Reply] =
    sharding
      .entityRefFor(EntityKeys.forComponent(call.componentId), call.entityId)
      .ask[EntityProtocol.Reply](replyTo =>
        EntityProtocol.Invoke(call.method, call.payload, MetaEntry.from(call.carried), replyTo)
      )(using Timeout(within))

  /** The reply's bytes, with the metadata it carried; a refusal or no answer as a failure. */
  private def answered(
      call: Call,
      reply: Future[EntityProtocol.Reply]
  ): Future[(Array[Byte], Metadata)] =
    val observability = Observability(system)
    reply.transform {
      case Success(EntityProtocol.Succeeded(reply, replyMetadata)) =>
        Success((reply, MetaEntry.toMetadata(replyMetadata)))

      case Success(rejected: EntityProtocol.Rejected) =>
        Failure(rejected.toCommandError)

      // Only a wait is answered these, and a wait never comes this way.
      case Success(other @ (_: EntityProtocol.NotYet | _: EntityProtocol.WorkflowFailed)) =>
        Failure(CommandError(s"unexpected reply to a call: $other", ErrorCode.Internal))

      case Failure(_: java.util.concurrent.TimeoutException) =>
        // Only the caller can see this. If a handler ran and threw, its host counted that too,
        // as a failure: one call, seen from both ends, and the two are never added together.
        observability.unanswered(call.origin, call.componentId, call.method, Unanswered.TimedOut)
        Failure(
          CommandError(
            s"${call.componentId}#${call.method} on '${call.entityId}' did not reply within $askTimeout",
            ErrorCode.Timeout
          )
        )

      case Failure(NonFatal(other)) =>
        // No host was reached, so none counted it. Each attempt is one: a caller that tries
        // three times made three calls that were not delivered.
        observability.unanswered(call.origin, call.componentId, call.method, Unanswered.Undelivered)
        Failure(CommandError(other.getMessage, ErrorCode.Unavailable))

      case Failure(fatal) => Failure(fatal)
    }

private[ankka] object ShardingTransport:

  /**
   * Whether `rejected` is how an instance from before waiting answers a wait: the reserved method
   * is an unknown handler to it. Matched on that release's words, which a test holds to the
   * engine's.
   */
  private[ankka] def fromBeforeAwaiting(
      rejected: EntityProtocol.Rejected,
      componentId: ComponentId
  ): Boolean =
    rejected.code == ErrorCode.NotFound.toString &&
      rejected.message == s"no handler '${WorkflowLifecycle.AwaitEnd}' on workflow '$componentId'"

  /** `ankka.query-resend-after`'s default, for a transport built without a configuration. */
  val DefaultResendAfter: FiniteDuration = 2.seconds

  def clientFor(
      sharding: ClusterSharding,
      askTimeout: FiniteDuration,
      resendAfter: FiniteDuration = DefaultResendAfter
  )(using system: ActorSystem[?]): ComponentClient =
    ComponentClient(new ShardingTransport(sharding, askTimeout, resendAfter))
