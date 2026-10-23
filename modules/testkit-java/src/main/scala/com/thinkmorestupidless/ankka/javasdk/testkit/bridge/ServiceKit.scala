package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeys
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.grpc as sgrpc
import com.thinkmorestupidless.ankka.javasdk.bridge.{
  Clients,
  Grpc,
  Mcp,
  JavaServiceBuilder,
  JavaTimers,
  Stores
}
import com.thinkmorestupidless.ankka.javasdk.grpc.GrpcClients
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceResponse, ViewClient}
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.javasdk.bridge.Acls
import com.thinkmorestupidless.ankka.javasdk.http.Caller
import com.thinkmorestupidless.ankka.javasdk.testkit.{
  Published,
  ScriptedService,
  Settings,
  TestSocket
}
import com.thinkmorestupidless.ankka.runtime.RuntimeExtension
import com.thinkmorestupidless.ankka.javasdk.timer.Timers
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.testkit as stk
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.function.Function
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Java service kit over the Scala `AnkkaTestKit`. */
final class ServiceKit private (
    kit: stk.AnkkaTestKit,
    private var read: JavaServiceBuilder.Read,
    scripted: Map[String, stk.ScriptedService],
    private var probe: stk.TimerProbe,
    private var broker: runtime.InMemoryBroker
):
  def timerRuns(): TimerRunsKit = TimerRunsKit(probe.bind(kit))

  def published(topic: String): java.util.List[Published] =
    broker
      .publishedTo(topic)
      .map { d =>
        val headers = java.util.LinkedHashMap[String, String]()
        d.message.metadata.toSeq.foreach((k, v) => headers.putIfAbsent(k, v): Unit)
        Published(d.message.key.toJava, d.text, java.util.Collections.unmodifiableMap(headers))
      }
      .asJava

  private lazy val client = HttpClient.newHttpClient()

  def componentClient(): ComponentClient = Clients.of(kit.componentClient, read.refs)

  // Read from the running service each time: restartService replaces it.
  def viewClient(): ViewClient = read.viewClient(kit.service.viewClient)

  def http(): URI =
    URI.create(
      kit.service.boundAddresses.headOption
        .getOrElse(throw IllegalStateException("the service serves no endpoint"))
    )

  def httpClient(): HttpClient = client

  def grpcAddress(): String =
    kit.service.grpcAddresses.headOption
      .getOrElse(throw IllegalStateException("the service serves no gRPC endpoint"))

  def grpcClients(): GrpcClients =
    kit.service.extension[sgrpc.GrpcClients].fold(Grpc.noClients)(Grpc.clients)

  def restartService(): Unit = kit.restartService()

  /** Stops the service and starts `service` on the same database. */
  def restartService(service: ServiceBuilder): Unit =
    val next = ServiceKit.prepare(service)
    kit.stopService()
    kit.startService(next.extensions, descriptors = next.read.descriptors)
    JavaServiceBuilder.problemsAtStart(kit): Unit
    read = next.read
    probe = next.probe
    broker = next.broker

  // Each read from the running service, as viewClient is.
  def timers(): Timers =
    kit.service
      .extension[runtime.TimerRuntime]
      .fold(JavaTimers.unavailable)(t => read.timers(t.timerScheduler))

  def secrets(): SecretStore = Stores.secrets(kit.service.secrets)

  def clientKeys(): ClientKeys = Mcp.clientKeys(kit.clientKeys)

  def asCaller(caller: Caller): java.util.Map.Entry[String, String] =
    val (name, value) = kit.asCaller(Acls.scalaCaller(caller))
    java.util.Map.entry(name, value)

  def socket(
      path: String,
      headers: java.util.Map[String, String],
      subprotocols: java.util.List[String]
  ): SocketKit =
    kit.socket(path, headers.asScala.toMap, subprotocols.asScala.toVector) match
      case Right(socket) => SocketKit(socket)
      case Left(refused) => throw TestSocket.Refused(refused.status, refused.headers.asJava)

  def storage(): ObjectStorage = Stores.storage(kit.service.storage)

  def eventually(within: Duration, assertion: Runnable): Unit =
    val deadline = System.nanoTime() + within.toNanos
    var passed   = false
    while !passed do
      try
        assertion.run()
        passed = true
      catch
        case e: (AssertionError | RuntimeException) =>
          if System.nanoTime() > deadline then throw e
          Thread.sleep(50)

  def service(name: String): ScriptedService =
    JavaScripted(
      scripted.getOrElse(
        name,
        throw IllegalArgumentException(
          s"no stand-in named '$name'; name it in Settings.withScriptedService"
        )
      )
    )

  def close(): Unit =
    kit.stop()
    scripted.values.foreach(_.stop())

object ServiceKit:

  def start(service: ServiceBuilder, settings: Settings): ServiceKit =
    startWith(service, settings) { (descriptors, extensions, config, localServices) =>
      stk.AnkkaTestKit.start(
        descriptors,
        extensions = extensions,
        settings = config,
        localServices = localServices,
        storage =
          if settings.storageFromEnvironment then stk.StorageBackendChoice.FromEnvironment()
          else stk.StorageBackendChoice.Memory()
      )
    }

  private final case class Prepared(
      read: JavaServiceBuilder.Read,
      probe: stk.TimerProbe,
      broker: runtime.InMemoryBroker,
      extensions: Seq[RuntimeExtension]
  )

  /** The service read, and the extensions it is started with. */
  private def prepare(service: ServiceBuilder): Prepared =
    val builder = service match
      case b: JavaServiceBuilder => b
      case other => throw IllegalArgumentException(s"$other is not a service from Ankka.service")
    val read = JavaServiceBuilder.read(
      builder.classes,
      builder.serializers,
      builder.endpoints,
      grpcEndpoints = builder.grpcEndpoints,
      mcpEndpoints = builder.mcpEndpoints
    )
    val probe        = stk.TimerProbe()
    val broker       = runtime.InMemoryBroker()
    val timerRuntime = runtime.TimerRuntime(pollInterval = 200.millis, observer = probe)
    val extensions = read.platformExtensionsWith(
      runtime.ProjectionRuntime.withBroker(broker, broker),
      timerRuntime
    ) ++
      (if !read.servesHttp then Nil
       else
         Seq(read.httpServer(Some("127.0.0.1" -> 0), read.endpointTimers(() => timerRuntime)))) ++
      read.grpcExtensions(Some("127.0.0.1" -> 0), builder.grpcClients)
    Prepared(read, probe, broker, extensions)

  /** The service read and its extensions built, then started by `launch`. */
  private[bridge] def startWith(service: ServiceBuilder, settings: Settings)(
      launch: (
          Seq[ComponentDescriptor],
          Seq[RuntimeExtension],
          com.typesafe.config.Config,
          Map[String, String]
      ) => stk.AnkkaTestKit
  ): ServiceKit =
    val Prepared(read, probe, broker, http) = prepare(service)
    val scripted =
      settings.scriptedServices.asScala.map(n => n -> stk.ScriptedService.start()).toMap
    val config = ConfigFactory.parseMap(settings.config)
    val kit =
      try
        JavaServiceBuilder.problemsAtStart(
          launch(
            read.descriptors,
            http,
            config,
            scripted.view.mapValues(_.address).toMap ++
              settings.localServices.asScala.view.mapValues(_.toString)
          )
        )
      catch
        case e: Throwable =>
          scripted.values.foreach(_.stop())
          throw e
    ServiceKit(kit, read, scripted, probe, broker)

private final class JavaScripted(scala: stk.ScriptedService) extends ScriptedService:
  def answer(answer: Function[ScriptedService.Request, ServiceResponse]): Unit =
    scala.answer { r =>
      val response = answer.apply(request(r))
      sdk.ServiceResponse(
        response.status,
        response.contentType,
        response.body,
        response.headers.asScala.toVector.map(e => e.getKey -> e.getValue)
      )
    }
  def failNext(status: Int): Unit = scala.failNext(status)
  def requests(): java.util.List[ScriptedService.Request] =
    scala.requests.map(request).asJava
  def clear(): Unit = scala.clear()

  private def request(r: stk.ScriptedService.Request): ScriptedService.Request =
    ScriptedService.Request(r.method, r.path, r.text, r.headers.toMap.asJava)
