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
 * What the sidecar adds to the runtime it hosts: the way back in for the service's own code, and an
 * opinion on readiness.
 *
 * The way back in is the loopback callback server for a process, or — for a module, given `imports`
 * — the `ankka1` imports, bound to the same `ClientLogic` once the service exists. There is no
 * server in the module mode: nothing outside this process calls back.
 *
 * Readiness is "discovered and the process is reachable": discovery completed before this extension
 * was even built, and reachability is asked of the conversation at most once a second. A process
 * that stops answering makes the pod un-ready while the sidecar keeps its cluster membership and
 * its shards (FR-013), which is the whole reason the sidecar is a separate container. A module is
 * in this process, so it is reachable whenever the runtime is.
 */
final class SidecarExtension(
    settings: Settings,
    conversation: Conversation,
    timers: TimerRuntime,
    servedRoutes: Vector[ServedRoute],
    imports: Option[wasm.HostImports] = None,
    /** The protocol the process or module declared in discovery: what it may be served. */
    declaredProtocol: Option[String] = None
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
    val scheduler                           = () => Some(timers.timerScheduler)
    imports match
      case Some(hostImports) =>
        hostImports.bind(ClientLogic(service, settings, scheduler, declaredProtocol))
        log.info(
          "hosting {} from the module {}",
          service.registry,
          settings.wasmModule.getOrElse("")
        )
      case None =>
        val client = ClientService(service, settings, scheduler, declaredProtocol)
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
