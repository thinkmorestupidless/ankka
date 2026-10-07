package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.EntityProtocol
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding

/**
 * The actor that keeps one run's worker alive: sharded per run id and remembered, so a run that was
 * working when its node stopped is started again by sharding itself, from the record. It answers
 * one thing, `start`, which wakes the worker; everything else about a run is the record's.
 */
private[ankka] object RunHost:

  val ComponentId: ComponentId = com.thinkmorestupidless.ankka.core.ComponentId("ankka-run-host")

  val Start: String = "start"

  /** How sharding stops a host: the worker stops with it, and the next incarnation carries on. */
  case object Stop extends EntityProtocol.ModuleCommand

  private case object WorkerStopped extends EntityProtocol.ModuleCommand

  def behavior(
      runId: String,
      shard: ActorRef[ClusterSharding.ShardCommand],
      client: ComponentClient,
      registry: BlueprintRegistry
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      val self   = ctx.self
      val worker = RunWorker(runId, client, registry, () => self ! WorkerStopped)
      worker.start()

      Behaviors
        .receiveMessage[EntityProtocol.Command] {
          case invoke: EntityProtocol.Invoke =>
            if invoke.method == Start then
              worker.poke()
              invoke.replyTo ! EntityProtocol.Succeeded(Array.emptyByteArray, Vector.empty)
            else
              invoke.replyTo ! EntityProtocol.Rejected(
                CommandError(s"no call '${invoke.method}' on a run's host", ErrorCode.BadRequest)
              )
            Behaviors.same
          case stream: EntityProtocol.InvokeStream =>
            stream.tokens ! EntityProtocol.StreamFailed(
              CommandError(s"no stream '${stream.method}' on a run's host", ErrorCode.BadRequest)
            )
            Behaviors.same
          case WorkerStopped =>
            // The run has ended: nothing more to do until someone asks about it, and that is the
            // record's to answer.
            shard ! ClusterSharding.Passivate(self)
            Behaviors.same
          case Stop => Behaviors.stopped
          case _    => Behaviors.same
        }
        .receiveSignal { case (_, PostStop) =>
          worker.stop()
          Behaviors.same
        }
    }
