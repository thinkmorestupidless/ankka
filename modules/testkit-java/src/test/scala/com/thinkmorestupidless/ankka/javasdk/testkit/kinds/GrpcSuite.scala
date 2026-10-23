package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, Settings}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.grpc.stub.{ClientCalls, MetadataUtils, StreamObserver}
import io.grpc.{
  CallOptions,
  Channel,
  ClientInterceptors,
  ManagedChannel,
  ManagedChannelBuilder,
  Metadata,
  Status,
  StatusRuntimeException
}

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** A Java gRPC endpoint, served by the Scala gRPC server, and a Java service's gRPC clients. */
class GrpcSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit       = null
  private var caller: AnkkaTestKit    = null
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Ankka.service("greeter").withGrpcEndpoint(classOf[Greeter]))
    channel = ManagedChannelBuilder.forTarget(kit.grpcAddress()).usePlaintext().build()
    caller = AnkkaTestKit.start(
      Ankka.service("caller").withGrpcClients(),
      Settings.defaults().withConfig("ankka.local-grpc-services.greeter", kit.grpcAddress())
    )

  override def afterAll(): Unit =
    try if channel != null then channel.shutdownNow(): Unit
    finally
      try if caller != null then caller.close()
      finally if kit != null then kit.close()

  private def unary(
      on: Channel,
      method: io.grpc.MethodDescriptor[String, String],
      request: String
  ) =
    ClientCalls.blockingUnaryCall(on, method, CallOptions.DEFAULT, request)

  test("a unary method answers, reading the call's metadata") {
    val headers = Metadata()
    headers.put(Metadata.Key.of("x-greeting", Metadata.ASCII_STRING_MARSHALLER), "the suite")
    val withHeaders =
      ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers))
    assertEquals(unary(withHeaders, Greeter.HELLO, "ada"), "hello ada, from the suite")
  }

  test("a server stream answers each part its publisher publishes") {
    val parts = ClientCalls
      .blockingServerStreamingCall(channel, Greeter.COUNT, CallOptions.DEFAULT, "3")
      .asScala
      .toVector
    assertEquals(parts, Vector("part 1", "part 2", "part 3"))
  }

  private final class Collect extends StreamObserver[String]:
    val values                      = LinkedBlockingQueue[String]()
    val done                        = CompletableFuture[Unit]()
    def onNext(value: String): Unit = values.add(value): Unit
    def onError(t: Throwable): Unit = done.completeExceptionally(t): Unit
    def onCompleted(): Unit         = done.complete(()): Unit

  test("a client stream reads every request before it answers") {
    val answer = Collect()
    val requests = ClientCalls.asyncClientStreamingCall(
      channel.newCall(Greeter.JOIN, CallOptions.DEFAULT),
      answer
    )
    Vector("a", "b", "c").foreach(requests.onNext)
    requests.onCompleted()
    answer.done.get(20, TimeUnit.SECONDS)
    assertEquals(answer.values.asScala.toVector, Vector("a+b+c"))
  }

  test("a bidirectional stream answers each request as it comes") {
    val answers = Collect()
    val requests = ClientCalls.asyncBidiStreamingCall(
      channel.newCall(Greeter.SHOUT, CallOptions.DEFAULT),
      answers
    )
    requests.onNext("one")
    assertEquals(answers.values.poll(20, TimeUnit.SECONDS), "ONE")
    requests.onNext("two")
    assertEquals(answers.values.poll(20, TimeUnit.SECONDS), "TWO")
    requests.onCompleted()
    answers.done.get(20, TimeUnit.SECONDS)
  }

  test("a handler's refusal is the status its code maps to, as a Scala endpoint's is") {
    val e = intercept[StatusRuntimeException](unary(channel, Greeter.FIND, "bob"))
    assertEquals(e.getStatus.getCode, Status.Code.NOT_FOUND)
    assert(e.getStatus.getDescription.contains("no greeting for bob"), e.getStatus.toString)
  }

  test("a Java service calls another service's gRPC through its clients, by name") {
    val answer = unary(caller.grpcClients().channel("greeter"), Greeter.HELLO, "grace")
    assertEquals(answer, "hello grace, from nobody")
  }

  test("a service that did not ask for gRPC clients is told how to") {
    val e = intercept[IllegalStateException](kit.grpcClients().channel("greeter"))
    assert(e.getMessage.contains("withGrpcClients()"), e.getMessage)
  }

  test("a class that is no gRPC endpoint is refused at start, saying so") {
    val notGrpc =
      classOf[Vault].asInstanceOf[Class[com.thinkmorestupidless.ankka.javasdk.grpc.GrpcEndpoint]]
    val e = intercept[ServiceProblems](Ankka.service("broken").withGrpcEndpoint(notGrpc).start())
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("is not a gRPC endpoint: extend GrpcEndpoint"), messages)
  }
