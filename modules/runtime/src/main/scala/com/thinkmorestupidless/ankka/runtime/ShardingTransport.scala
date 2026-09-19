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
    sharding
      .entityRefFor(EntityKeys.forComponent(componentId), entityId)
      .tell(message.asInstanceOf[EntityProtocol.Command])

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
    val outbound = Trace.currentTrace match
      case Some((traceId, spanId)) => Trace.into(metadata, traceId, spanId)
      case None                    => metadata

    sharding
      .entityRefFor(EntityKeys.forComponent(componentId), entityId)
      .ask[EntityProtocol.Reply](replyTo =>
        EntityProtocol.Invoke(method, payload, MetaEntry.from(outbound), replyTo)
      )
      .transform {
        case Success(EntityProtocol.Succeeded(reply, _)) =>
          Success(reply)

        case Success(rejected: EntityProtocol.Rejected) =>
          Failure(rejected.toCommandError)

        case Failure(_: java.util.concurrent.TimeoutException) =>
          Failure(
            CommandError(
              s"$componentId#$method on '$entityId' did not reply within $askTimeout",
              ErrorCode.Timeout
            )
          )

        case Failure(NonFatal(other)) =>
          Failure(CommandError(other.getMessage, ErrorCode.Unavailable))

        case Failure(fatal) => Failure(fatal)
      }

private[ankka] object ShardingTransport:
  def clientFor(sharding: ClusterSharding, askTimeout: FiniteDuration)(using
      system: ActorSystem[?]
  ): ComponentClient =
    ComponentClient(new ShardingTransport(sharding, askTimeout))
