package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.agent.AgentRuntime
import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{
  Ankka,
  AnkkaService,
  ClusterConfig,
  ProjectionRuntime,
  ServedRoute,
  TimerRuntime
}
import io.grpc.ManagedChannelBuilder
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/**
 * The sidecar: ankka's runtime booted from a discovery handshake instead of a Scala builder.
 *
 * Discover → validate → build remote descriptors, agents and endpoints → `Ankka.service` with the
 * same extensions an in-process service registers → start. Everything after discovery is the
 * existing runtime: cluster formation, persistence, projections, timers, observability.
 */
object Main:

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    sys.exit(run())

  /** Returns the exit code, so a test can drive it without `sys.exit` killing the JVM. */
  def run(): Int =
    val config   = ClusterConfig.load()
    val settings = Settings.load(config)
    // One ActorSystem for the whole process: the conversation needs a scheduler before the
    // service exists, so the system is created here and handed to the builder.
    val system = ActorSystem(Behaviors.empty[Nothing], "ankka", ClusterConfig.layered(config))

    val channel = ManagedChannelBuilder
      .forAddress(settings.processHost, settings.processPort)
      .usePlaintext()
      .build()

    Discovery.discover(channel, settings, BuildInfo.version) match
      case Left(problems) =>
        log.error("refusing to start: {} problem(s)", problems.size)
        channel.shutdownNow()
        system.terminate()
        1
      case Right(discovered) =>
        try
          val service = build(discovered, settings, channel, system)
          // The process is the node: it lives until the service terminates. Returning here would
          // exit the JVM, and coordinated shutdown would have the node leave the cluster it just
          // joined, while still answering HTTP for a moment — which is exactly what happened.
          scala.concurrent.Await
            .ready(service.whenTerminated, scala.concurrent.duration.Duration.Inf)
          0
        catch
          case e: Throwable =>
            log.error("the sidecar failed to start", e)
            channel.shutdownNow()
            system.terminate()
            1

  /** What `run` and the tests share: the wiring from a discovered spec to a started service. */
  def build(
      discovered: Discovery.Discovered,
      settings: Settings,
      channel: io.grpc.ManagedChannel,
      system: ActorSystem[?]
  ): AnkkaService =
    given ActorSystem[?]   = system
    given ExecutionContext = system.executionContext
    val conversation       = GrpcConversation(channel, settings)
    val timers             = TimerRuntime()
    // A process has no builder to hand a broker to, so the one broker the sidecar knows how to
    // speak is chosen by environment: a producing consumer or a topic-sourced view is refused at
    // startup without it, naming the variable.
    val projections = sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS") match
      case Some(servers) => ProjectionRuntime.withKafka(servers)
      case None          => ProjectionRuntime()
    val endpoints = discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    val served: Vector[ServedRoute] = endpoints.flatMap(_.served)

    val http =
      sys.env.get("ANKKA_HTTP_PORT").map(_.toInt) match
        case Some(port) => HttpServer.at("0.0.0.0", port)(endpoints.map(e => _ => e)*)
        case None       => HttpServer.of(endpoints.map(e => _ => e)*)

    Ankka.service
      .registerAll(discovered.descriptors)
      .withConversation(conversation)
      .withExtension(projections)
      .withExtension(timers)
      .withExtension(AgentRuntime())
      .withExtension(http)
      .withExtension(SidecarExtension(settings, conversation, timers, served))
      .startWith(system)
