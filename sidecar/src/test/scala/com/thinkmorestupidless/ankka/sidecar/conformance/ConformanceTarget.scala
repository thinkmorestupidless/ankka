package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{
  InMemoryBroker,
  ProjectionRuntime,
  ServedRoute,
  TimerRuntime,
  TopologyJson
}
import com.thinkmorestupidless.ankka.sidecar.{
  Discovery,
  GrpcConversation,
  Models,
  RemoteAgent,
  RemoteAutonomousAgent,
  RemoteEndpoint,
  Settings,
  SidecarExtension
}
import com.thinkmorestupidless.ankka.sidecar.wasm.{
  GuestInstance,
  HostImports,
  ModuleLoader,
  WasmConversation,
  WasmDiscovery
}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.apache.pekko.actor.typed.ActorSystem

import java.net.URI
import java.nio.file.Path
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

/**
 * What the conformance suite drives: a service reachable over HTTP, restartable, with the journal
 * and the recorder in this JVM and a scripted model the suite can script. Either the Scala
 * reference in-process, the sidecar in this JVM in front of a process that speaks the protocol —
 * chosen by `-Dankka.conformance.target=<host:port>` — or the runtime in this JVM hosting a
 * WebAssembly module, by `-Dankka.conformance.target=wasm:<path>`, in the guest shape
 * `-Dankka.conformance.shape` names (`stateless`, the default, or `stateful`).
 */
trait ConformanceTarget:
  def name: String
  def baseUrl: String
  def system: ActorSystem[?]
  def model: TestModelProvider

  /**
   * Where the target's consumers publish, in this JVM: what a producing consumer wrote can be read
   * back record by record, and a publication made to fail.
   */
  val broker: InMemoryBroker = InMemoryBroker()

  /** A new service on the same database: every instance is gone from memory. */
  def restart(): Unit

  /** True when a service in another language is at the far end: a process, or a module. */
  def isProcess: Boolean

  /** True for a WebAssembly module, which answers every call whole and so cannot stream. */
  def isModule: Boolean = false

  /** What the process was told through `ReportError`; empty in-process. */
  def problems: Vector[String]

  /** The component ids the target hosts, as discovery or the registry says. */
  def componentIds: Set[String]
  def readOnlyHandlers: Set[(String, String)]
  def endpointRoutes: Set[String]

  /** The target's topology, as its own runtime renders it for a console. */
  def topology: String

  /** A discovery with this protocol version; `Left` is the refusal. Process targets only. */
  def discoverWith(protocolVersion: String): Option[Either[Vector[String], Unit]]

  def stop(): Unit

object ConformanceTarget:

  /** Rendered by the runtime hosting the target, whatever language the target is written in. */
  private def topologyOf(kit: AnkkaTestKit): String =
    TopologyJson.render(
      "conformance",
      "conformance-1",
      "2026-01-01T00:00:00Z",
      kit.service.registry,
      kit.service.routes
    )

  def fromProperty(model: TestModelProvider): ConformanceTarget =
    sys.props.get("ankka.conformance.target").filter(_.nonEmpty) match
      case Some(module) if module.startsWith("wasm:") =>
        ModuleTarget(
          Path.of(module.stripPrefix("wasm:")),
          sys.props.getOrElse("ankka.conformance.shape", "stateless"),
          model
        )
      case Some(address) => Sidecar(address, model)
      case None          => InProcess(model)

  private val reference = ConformanceReference

  /** The Scala reference service on `AnkkaTestKit`. */
  final class InProcess(val model: TestModelProvider) extends ConformanceTarget:
    private val timers = TimerRuntime(200.millis)
    private val kit = AnkkaTestKit.start(
      reference.descriptors ++ AgentRuntime.descriptors,
      Seq(
        ProjectionRuntime.withBroker(broker, broker),
        timers,
        AgentRuntime.withDefaultModel(model),
        HttpServer.at("127.0.0.1", 0)(
          reference.endpoints(() => timers.timerScheduler, () => Vector.empty)*
        )
      ),
      60.seconds
    )
    def name: String             = "in-process"
    def baseUrl: String          = kit.service.boundAddresses.find(_.startsWith("http")).get
    def system: ActorSystem[?]   = kit.service.system
    def restart(): Unit          = kit.restartService()
    def isProcess: Boolean       = false
    def problems: Vector[String] = Vector.empty
    // Minus the agent runtime's own components, which a process target never declares.
    def componentIds: Set[String] =
      kit.service.registry.components.map(_.componentId.toString).toSet --
        AgentRuntime.descriptors.map(_.componentId.toString)
    def readOnlyHandlers: Set[(String, String)] =
      Set(
        ("shopping-cart", "get-cart"),
        ("conformance", "count"),
        ("profile", "get"),
        ("checkout", "status")
      )
    def endpointRoutes: Set[String] = kit.service.routes.map(r => s"${r.method} ${r.path}").toSet
    def topology: String            = ConformanceTarget.topologyOf(kit)
    def discoverWith(protocolVersion: String): Option[Either[Vector[String], Unit]] = None
    def stop(): Unit                                                                = kit.stop()

  /** The sidecar's wiring in this JVM, on `AnkkaTestKit`'s Postgres, in front of `address`. */
  final class Sidecar(address: String, val model: TestModelProvider) extends ConformanceTarget:
    given ExecutionContext = ExecutionContext.global
    private val channel: ManagedChannel =
      val Array(host, port) = address.split(':')
      ManagedChannelBuilder.forAddress(host, port.toInt).usePlaintext().build()
    private val settings =
      Settings(
        address,
        Settings.DefaultCallbackPort,
        "127.0.0.1",
        60.seconds,
        5.seconds,
        10.seconds,
        10.seconds
      )
    private val discovered =
      Discovery
        .discover(channel, settings, BuildInfo.version)
        .fold(
          problems =>
            throw IllegalStateException(
              problems.mkString("the process was refused:\n  - ", "\n  - ", "")
            ),
          identity
        )
    private val conversation = GrpcConversation(channel, settings)
    private val timers       = TimerRuntime(200.millis)
    private val agents = discovered.agents.map { c =>
      RemoteAgent.descriptor(
        RemoteAgent.spec(c).toOption.get,
        conversation,
        Models.only(Models.Scripted, model),
        settings.commandTimeout
      )
    }
    private val autonomous = discovered.autonomousAgents.map(c =>
      RemoteAutonomousAgent.descriptor(
        c,
        conversation,
        Models.only(Models.Scripted, model),
        settings.commandTimeout
      )
    )
    private val endpoints =
      discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    private val served: Vector[ServedRoute] = endpoints.flatMap(_.served)
    private val kit = AnkkaTestKit.start(
      discovered.descriptors ++ agents ++ autonomous ++ AgentRuntime.descriptors,
      Seq(
        ProjectionRuntime.withBroker(broker, broker),
        timers,
        AgentRuntime.withDefaultModel(model),
        HttpServer.at("127.0.0.1", 0)(endpoints.map(e => _ => e)*),
        SidecarExtension(settings, conversation, timers, served)
      ),
      60.seconds,
      _.withConversation(conversation)
    )
    def name: String           = s"sidecar → $address"
    def baseUrl: String        = kit.service.boundAddresses.find(_.startsWith("http")).get
    def system: ActorSystem[?] = kit.service.system
    def restart(): Unit        = kit.restartService()
    def isProcess: Boolean     = true
    def problems: Vector[String] =
      val client = HttpClient.newHttpClient()
      val r = client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/conformance/problems")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      Json
        .parse(r.body())
        .toOption
        .flatMap(_.asArray)
        .map(_.flatMap(_.asString))
        .getOrElse(Vector.empty)
    def componentIds: Set[String] = discovered.spec.components.map(_.id).toSet
    def readOnlyHandlers: Set[(String, String)] =
      discovered.spec.components
        .flatMap(c => c.handlers.filter(_.readOnly).map(h => (c.id, h.name)))
        .toSet
    def endpointRoutes: Set[String] = served.map(r => s"${r.method} ${r.path}").toSet
    def topology: String            = ConformanceTarget.topologyOf(kit)
    def discoverWith(protocolVersion: String): Option[Either[Vector[String], Unit]] =
      Some(Discovery.discover(channel, settings, BuildInfo.version, protocolVersion).map(_ => ()))
    def stop(): Unit =
      Try(kit.stop())
      channel.shutdownNow(): Unit

  /**
   * The runtime's module mode in this JVM, on `AnkkaTestKit`'s Postgres: the module loaded, its
   * declaration discovered from its exports, every component hosted over `WasmConversation`. The
   * module reads its guest shape from `ANKKA_CONFORMANCE_SHAPE` through the `config` import, set
   * here as an override on the imports rather than in the environment, which a JVM cannot change.
   */
  final class ModuleTarget(path: Path, shape: String, val model: TestModelProvider)
      extends ConformanceTarget:
    private val settings =
      Settings(
        "127.0.0.1:0",
        0,
        "127.0.0.1",
        60.seconds,
        5.seconds,
        10.seconds,
        10.seconds,
        wasmModule = Some(path)
      )
    private val module =
      ModuleLoader.load(path).fold(p => throw IllegalStateException(p.mkString("; ")), identity)
    // The shape the module runs in, and a broker named: the reference registers the components
    // that publish only where there is one, and here there is — `broker`, in this JVM. The value
    // is read by the module alone; nothing connects to it.
    private val overrides = Map(
      "ANKKA_CONFORMANCE_SHAPE"       -> shape,
      "ANKKA_KAFKA_BOOTSTRAP_SERVERS" -> "the conformance suite's in-memory broker"
    )
    private val imports =
      HostImports(
        settings.commandTimeout,
        settings.requestTimeout,
        name => overrides.get(name).orElse(sys.env.get(name))
      )
    @volatile private var lastProblems = Vector.empty[String]

    private def discover(protocolVersion: String) =
      val bootstrap = GuestInstance.build(module, imports.values, settings.wasmMaxMemoryPages)
      val result    = WasmDiscovery.discover(bootstrap, module, BuildInfo.version, protocolVersion)
      lastProblems = result.left.getOrElse(Vector.empty)
      result

    private val discovered = discover(Discovery.ProtocolVersion).fold(
      problems =>
        throw IllegalStateException(
          problems.mkString("the module was refused:\n  - ", "\n  - ", "")
        ),
      identity
    )
    private val conversation =
      WasmConversation(module, settings, imports, discovered.shapeOf)
    private val timers = TimerRuntime(200.millis)
    private val agents = discovered.agents.map { c =>
      RemoteAgent.descriptor(
        RemoteAgent.spec(c).toOption.get,
        conversation,
        Models.only(Models.Scripted, model),
        settings.commandTimeout
      )
    }
    private val autonomous = discovered.autonomousAgents.map(c =>
      RemoteAutonomousAgent.descriptor(
        c,
        conversation,
        Models.only(Models.Scripted, model),
        settings.commandTimeout
      )
    )
    private val endpoints =
      discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    private val served: Vector[ServedRoute] = endpoints.flatMap(_.served)
    private val kit = AnkkaTestKit.start(
      discovered.descriptors ++ agents ++ autonomous ++ AgentRuntime.descriptors,
      Seq(
        ProjectionRuntime.withBroker(broker, broker),
        timers,
        AgentRuntime.withDefaultModel(model),
        HttpServer.at("127.0.0.1", 0)(endpoints.map(e => _ => e)*),
        SidecarExtension(settings, conversation, timers, served, Some(imports))
      ),
      60.seconds,
      _.withConversation(conversation)
    )
    def name: String               = s"module $path ($shape)"
    def baseUrl: String            = kit.service.boundAddresses.find(_.startsWith("http")).get
    def system: ActorSystem[?]     = kit.service.system
    def restart(): Unit            = kit.restartService()
    def isProcess: Boolean         = true
    override def isModule: Boolean = true
    def problems: Vector[String]   = lastProblems
    def componentIds: Set[String]  = discovered.spec.components.map(_.id).toSet
    def readOnlyHandlers: Set[(String, String)] =
      discovered.spec.components
        .flatMap(c => c.handlers.filter(_.readOnly).map(h => (c.id, h.name)))
        .toSet
    def endpointRoutes: Set[String] = served.map(r => s"${r.method} ${r.path}").toSet
    def topology: String            = ConformanceTarget.topologyOf(kit)
    def discoverWith(protocolVersion: String): Option[Either[Vector[String], Unit]] =
      Some(discover(protocolVersion).map(_ => ()))
    def stop(): Unit = Try(kit.stop()): Unit
