package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, ServedRoute, TimerRuntime}
import com.thinkmorestupidless.ankka.sidecar.{
  Discovery,
  GrpcConversation,
  Models,
  RemoteAgent,
  RemoteEndpoint,
  Settings,
  SidecarExtension
}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.apache.pekko.actor.typed.ActorSystem

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

/**
 * What the conformance suite drives: a service reachable over HTTP, restartable, with the journal
 * and the recorder in this JVM and a scripted model the suite can script. Either the Scala
 * reference in-process, or the sidecar in this JVM in front of a process that speaks the protocol —
 * chosen by `-Dankka.conformance.target=<host:port>`.
 */
trait ConformanceTarget:
  def name: String
  def baseUrl: String
  def system: ActorSystem[?]
  def model: TestModelProvider

  /** A new service on the same database: every instance is gone from memory. */
  def restart(): Unit

  /** True when a process in another language is at the far end. */
  def isProcess: Boolean

  /** What the process was told through `ReportError`; empty in-process. */
  def problems: Vector[String]

  /** The component ids the target hosts, as discovery or the registry says. */
  def componentIds: Set[String]
  def readOnlyHandlers: Set[(String, String)]
  def endpointRoutes: Set[String]

  /** A discovery with this protocol version; `Left` is the refusal. Process targets only. */
  def discoverWith(protocolVersion: String): Option[Either[Vector[String], Unit]]

  def stop(): Unit

object ConformanceTarget:

  def fromProperty(model: TestModelProvider): ConformanceTarget =
    sys.props.get("ankka.conformance.target").filter(_.nonEmpty) match
      case Some(address) => Sidecar(address, model)
      case None          => InProcess(model)

  private val reference = ConformanceReference

  /** The Scala reference service on `AnkkaTestKit`. */
  final class InProcess(val model: TestModelProvider) extends ConformanceTarget:
    private val timers = TimerRuntime(200.millis)
    private val kit = AnkkaTestKit.start(
      reference.descriptors ++ AgentRuntime.descriptors,
      Seq(
        ProjectionRuntime(),
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
    // Minus the agent runtime's own session memory, which a process target never declares.
    def componentIds: Set[String] =
      kit.service.registry.components.map(_.componentId.toString).toSet - "ankka-session-memory"
    def readOnlyHandlers: Set[(String, String)] =
      Set(
        ("shopping-cart", "get-cart"),
        ("conformance", "count"),
        ("profile", "get"),
        ("checkout", "status")
      )
    def endpointRoutes: Set[String] = kit.service.routes.map(r => s"${r.method} ${r.path}").toSet
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
    private val endpoints =
      discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    private val served: Vector[ServedRoute] = endpoints.flatMap(_.served)
    private val kit = AnkkaTestKit.start(
      discovered.descriptors ++ agents ++ AgentRuntime.descriptors,
      Seq(
        ProjectionRuntime(),
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
    def discoverWith(protocolVersion: String): Option[Either[Vector[String], Unit]] =
      Some(Discovery.discover(channel, settings, BuildInfo.version, protocolVersion).map(_ => ()))
    def stop(): Unit =
      Try(kit.stop())
      channel.shutdownNow(): Unit
