package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.runtime.remote.Conversation
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaService,
  RuntimeExtension,
  ServedRoute,
  TimerRuntime
}
import io.grpc.Server
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicLong

/**
 * What the sidecar adds to the runtime it hosts: the callback server, and an opinion on readiness.
 *
 * Readiness is "discovered and the process is reachable": discovery completed before this extension
 * was even built, and reachability is asked of the conversation at most once a second. A process
 * that stops answering makes the pod un-ready while the sidecar keeps its cluster membership and
 * its shards (FR-013), which is the whole reason the sidecar is a separate container.
 */
final class SidecarExtension(
    settings: Settings,
    conversation: Conversation,
    timers: TimerRuntime,
    servedRoutes: Vector[ServedRoute]
) extends RuntimeExtension:

  private val log                              = LoggerFactory.getLogger(getClass)
  @volatile private var server: Option[Server] = None
  private val lastChecked                      = new AtomicLong(0L)
  @volatile private var lastReachable          = false

  def name: String = "sidecar"

  def start(service: AnkkaService): Unit =
    // The system is the service's: an extension is built before one exists.
    given ActorSystem[?]                    = service.system
    given scala.concurrent.ExecutionContext = service.system.executionContext
    val client = ClientService(service, settings, () => Some(timers.timerScheduler))
    server = Some(CallbackServer.start(client, settings.callbackBind, settings.callbackPort))
    log.info(
      "sidecar hosting {} against the process at {}",
      service.registry,
      settings.processAddress
    )

  override def stop(): Unit =
    server.foreach(_.shutdownNow())
    server = None

  override def readiness: Option[() => Boolean] = Some(() => reachable())

  override def routes: Vector[ServedRoute] = servedRoutes

  private def reachable(): Boolean =
    val now  = System.currentTimeMillis()
    val last = lastChecked.get()
    if now - last >= 1000 && lastChecked.compareAndSet(last, now) then
      lastReachable = conversation.reachable()
    lastReachable
