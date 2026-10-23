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
import com.thinkmorestupidless.ankka.javasdk.testkit.{ScriptedService, Settings}
import com.thinkmorestupidless.ankka.javasdk.timer.Timers
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.testkit as stk
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.function.Function
import scala.jdk.CollectionConverters.*

/** The Java service kit over the Scala `AnkkaTestKit`. */
final class ServiceKit private (
    kit: stk.AnkkaTestKit,
    read: JavaServiceBuilder.Read,
    scripted: Map[String, stk.ScriptedService]
):
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

  // Each read from the running service, as viewClient is.
  def timers(): Timers =
    kit.service
      .extension[runtime.TimerRuntime]
      .fold(JavaTimers.unavailable)(t => read.timers(t.timerScheduler))

  def secrets(): SecretStore = Stores.secrets(kit.service.secrets)

  def clientKeys(): ClientKeys = Mcp.clientKeys(kit.clientKeys)

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
    val scripted =
      settings.scriptedServices.asScala.map(n => n -> stk.ScriptedService.start()).toMap
    val config = ConfigFactory.parseMap(settings.config)
    val http = read.platformExtensions ++
      (if !read.servesHttp then Nil else Seq(read.httpServer(Some("127.0.0.1" -> 0)))) ++
      read.grpcExtensions(Some("127.0.0.1" -> 0), builder.grpcClients)
    val kit =
      try
        JavaServiceBuilder.problemsAtStart(
          stk.AnkkaTestKit.start(
            read.descriptors,
            extensions = http,
            settings = config,
            localServices = scripted.view.mapValues(_.address).toMap ++
              settings.localServices.asScala.view.mapValues(_.toString)
          )
        )
      catch
        case e: Throwable =>
          scripted.values.foreach(_.stop())
          throw e
    ServiceKit(kit, read, scripted)

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
