package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import org.apache.pekko.util.Timeout

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
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
 */
private[ankka] final class ShardingTransport(
    sharding: ClusterSharding,
    val askTimeout: FiniteDuration
)(using system: ActorSystem[?])
    extends CallTransport:

  private given ExecutionContext = system.executionContext
  private given Timeout          = Timeout(askTimeout)

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
    // The caller's span becomes the callee's parent, carried in the metadata that already
    // crosses the sharding boundary. Nothing about the protocol changes: MetaEntry is already
    // serialized, so a trace spans nodes for free.
    val carried = Trace.outbound(metadata)
    // Taken here, on the calling thread: the reply is handled on another, which has no origin.
    val origin        = Trace.currentOrigin
    val observability = Observability(system)

    sharding
      .entityRefFor(EntityKeys.forComponent(componentId), entityId)
      .ask[EntityProtocol.Reply](replyTo =>
        EntityProtocol.Invoke(method, payload, MetaEntry.from(carried), replyTo)
      )
      .transform {
        case Success(EntityProtocol.Succeeded(reply, _)) =>
          Success(reply)

        case Success(rejected: EntityProtocol.Rejected) =>
          Failure(rejected.toCommandError)

        case Failure(_: java.util.concurrent.TimeoutException) =>
          // Only the caller can see this. If a handler ran and threw, its host counted that too,
          // as a failure: one call, seen from both ends, and the two are never added together.
          observability.unanswered(origin, componentId, method, Unanswered.TimedOut)
          Failure(
            CommandError(
              s"$componentId#$method on '$entityId' did not reply within $askTimeout",
              ErrorCode.Timeout
            )
          )

        case Failure(NonFatal(other)) =>
          // No host was reached, so none counted it. Each attempt is one: a caller that tries
          // three times made three calls that were not delivered.
          observability.unanswered(origin, componentId, method, Unanswered.Undelivered)
          Failure(CommandError(other.getMessage, ErrorCode.Unavailable))

        case Failure(fatal) => Failure(fatal)
      }

private[ankka] object ShardingTransport:
  def clientFor(sharding: ClusterSharding, askTimeout: FiniteDuration)(using
      system: ActorSystem[?]
  ): ComponentClient =
    ComponentClient(new ShardingTransport(sharding, askTimeout))
