package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}
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

  /** `ankka.query-resend-after`'s default, for a transport built without a configuration. */
  val DefaultResendAfter: FiniteDuration = 2.seconds

  def clientFor(
      sharding: ClusterSharding,
      askTimeout: FiniteDuration,
      resendAfter: FiniteDuration = DefaultResendAfter
  )(using system: ActorSystem[?]): ComponentClient =
    ComponentClient(new ShardingTransport(sharding, askTimeout, resendAfter))
