package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.agent.AgentRuntime
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
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
import com.thinkmorestupidless.ankka.runtime.remote.Conversation
import io.grpc.ManagedChannelBuilder
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

/**
 * The sidecar image: ankka's runtime booted from a discovery handshake instead of a Scala builder.
 *
 * Two modes, one wiring. Given `ANKKA_WASM_MODULE` it loads that WebAssembly module into this
 * process and discovers what it declares from the module's exports; otherwise it is a sidecar and
 * dials the developer's process. Either way: discover → validate → build remote descriptors, agents
 * and endpoints over a `Conversation` → `Ankka.service` with the same extensions an in-process
 * service registers → start. Everything after discovery is the existing runtime: cluster formation,
 * persistence, projections, timers, observability.
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
    if settings.isModuleMode then runModule(settings, system) else runProcess(settings, system)

  /** The process mode: dial the developer's process, discover over gRPC. */
  private def runProcess(settings: Settings, system: ActorSystem[?]): Int =
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
          val conversation = GrpcConversation(channel, settings)(using system.executionContext)
          val service      = build(discovered, settings, conversation, system)
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

  /**
   * The module mode: load the module, discover from one instance of it, host it. A module the
   * runtime refuses — it does not load, or its declaration breaks a rule — is every problem logged
   * and exit 1, since there is no process to report them to.
   */
  private def runModule(settings: Settings, system: ActorSystem[?]): Int =
    val path = settings.wasmModule.get
    val started = for
      module <- wasm.ModuleLoader.load(path)
      imports = wasm.HostImports(settings.commandTimeout, settings.requestTimeout)
      bootstrap <- scala.util
        .Try(wasm.GuestInstance.build(module, imports.values, settings.wasmMaxMemoryPages))
        .toEither
        .left
        .map(e => Vector(s"the module could not be instantiated: ${e.getMessage}"))
      discovered <- wasm.WasmDiscovery.discover(bootstrap, module, BuildInfo.version)
    yield (module, imports, discovered)
    started match
      case Left(problems) =>
        log.error(problems.mkString(s"refusing to host $path:\n  - ", "\n  - ", ""))
        system.terminate()
        1
      case Right((module, imports, discovered)) =>
        try
          val conversation = wasm.WasmConversation(module, settings, imports, discovered.shapeOf)
          val service = build(discovered, settings, conversation, system, imports = Some(imports))
          scala.concurrent.Await
            .ready(service.whenTerminated, scala.concurrent.duration.Duration.Inf)
          0
        catch
          case e: Throwable =>
            log.error("the runtime failed to start", e)
            system.terminate()
            1

  /**
   * What `run` and the tests share: the wiring from a discovered spec, and the conversation that
   * reaches whatever declared it, to a started service. `imports` is the module mode's way back in;
   * absent, the extension serves the process's callback server.
   */
  def build(
      discovered: Discovery.Discovered,
      settings: Settings,
      conversation: Conversation,
      system: ActorSystem[?],
      models: Models = Models.fromEnv(),
      imports: Option[wasm.HostImports] = None
  ): AnkkaService =
    val timers = TimerRuntime()
    // The agent loop is the sidecar's; the process plans, and runs tools. Session memory is an
    // entity of this service, registered only when there is an agent to remember for.
    val agents: Vector[ComponentDescriptor] = discovered.agents.map { c =>
      RemoteAgent
        .spec(c)
        .fold(
          problem => throw IllegalArgumentException(problem),
          spec => RemoteAgent.descriptor(spec, conversation, models, settings.commandTimeout)
        )
    }
    // An autonomous agent's definition is all in discovery: the sidecar runs its loop and keeps its
    // tasks, and asks the process only to run a tool, check a guardrail or check a task rule.
    val autonomousAgents: Vector[ComponentDescriptor] = discovered.autonomousAgents.map(c =>
      RemoteAutonomousAgent.descriptor(c, conversation, models, settings.commandTimeout)
    )
    val agentRuntime = models.default.fold(AgentRuntime())(AgentRuntime.withDefaultModel(_))
    val memory =
      if agents.isEmpty && autonomousAgents.isEmpty then Vector.empty
      else AgentRuntime.descriptors.toVector
    // A process has no builder to hand a broker to, so the one broker the sidecar knows how to
    // speak is chosen by environment: a producing consumer or a topic-sourced view is refused at
    // startup without it, naming the variable.
    val projections = ProjectionRuntime.fromEnv()
    val endpoints   = discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    val served: Vector[ServedRoute] = endpoints.flatMap(_.served)

    val http =
      sys.env.get("ANKKA_HTTP_PORT").map(_.toInt) match
        case Some(port) => HttpServer.at("0.0.0.0", port)(endpoints.map(e => _ => e)*)
        case None       => HttpServer.of(endpoints.map(e => _ => e)*)

    Ankka.service
      .registerAll(discovered.descriptors ++ agents ++ autonomousAgents ++ memory)
      .withConversation(conversation)
      .withExtension(projections)
      .withExtension(timers)
      .withExtension(agentRuntime)
      .withExtension(http)
      .withExtension(SidecarExtension(settings, conversation, timers, served, imports))
      .startWith(system)
