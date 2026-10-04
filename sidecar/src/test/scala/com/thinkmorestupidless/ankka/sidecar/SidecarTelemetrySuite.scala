package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, MethodName}
import com.thinkmorestupidless.ankka.runtime.remote.{Payload, PayloadKeys}
import com.thinkmorestupidless.ankka.telemetry.FakeCollector
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

/**
 * A service in another language exports through the platform's program beside it, with nothing in
 * its own code: the Scala half of "a service exports whatever language it is written in, with no
 * change to its code", on the scriptable process double. The spans are the sidecar's — it hosts the
 * components — named for the service.
 */
class SidecarTelemetrySuite extends munit.FunSuite with LogCapturing:
  import ProcessDouble.*
  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: scala.concurrent.duration.Duration = 3.minutes

  private val collector               = FakeCollector()
  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(DoubleSpec(entities = Vector(ProcessDouble.recorder("conformance"))))
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    val conversation = GrpcConversation(channel, settings)
    val descriptors =
      Discovery
        .validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
        .fold(p => fail(p.mkString("; ")), _.descriptors)
    kit = AnkkaTestKit.start(
      descriptors,
      Nil,
      60.seconds,
      _.withConversation(conversation),
      settings = ConfigFactory.parseString(
        s"""ankka.telemetry.endpoint = "${collector.address}"
           |ankka.telemetry.interval = 100ms
           |ankka.telemetry.service-name = payments
           |ankka.telemetry.project = shop""".stripMargin
      )
    )

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()
    collector.stop()

  test("a process-hosted service's spans are exported by the platform's program, named for it") {
    val reply = Await.result(
      kit.componentClient.transportRef.ask(
        ComponentId("conformance"),
        EntityId("t-1"),
        MethodName("record"),
        "a".getBytes,
        Metadata.empty
          .set(PayloadKeys.Manifest, "string")
          .set(PayloadKeys.ContentType, Payload.Text)
      ),
      10.seconds
    )
    assertEquals(String(reply), "done")
    val deadline = System.nanoTime() + 20.seconds.toNanos
    var found    = Option.empty[FakeCollector.ExportedSpan]
    while found.isEmpty && System.nanoTime() < deadline do
      found = collector.spans.find(_.name == "conformance record")
      if found.isEmpty then Thread.sleep(100)
    val span =
      found.getOrElse(fail(s"no span of conformance record: ${collector.spans.map(_.name)}"))
    assertEquals(span.serviceName, Some("payments"))
    assertEquals(span.resource.get("ankka.project"), Some("shop"))
  }
