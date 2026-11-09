package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.workflow.{WorkflowGrpc, WorkflowIn, WorkflowOut}
import com.thinkmorestupidless.ankka.core.{
  ComponentId,
  ComponentKind,
  EntityId,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.remote.{Command, Init, Payload}
import io.grpc.stub.StreamObserver
import io.grpc.{
  CallOptions,
  Channel,
  ClientCall,
  ClientInterceptor,
  ForwardingClientCall,
  ManagedChannel,
  ManagedChannelBuilder,
  MethodDescriptor,
  Server,
  ServerBuilder
}

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A conversation's stream is written by one thread at a time (#87). A workflow's stream carries a
 * command and a step at once, by design: the engine answers a query on the actor's thread while a
 * step is sent from the step's virtual thread. A grpc-java `StreamObserver` is not thread-safe, and
 * two `onNext` calls at once corrupted the framer ("INTERNAL: Failed to frame message") and closed
 * the step's stream. Every send here is held for a moment by an interceptor that counts how many
 * writers are inside at once, so an overlap is seen every time rather than once in a hundred runs.
 */
class ConversationWritesSuite extends munit.FunSuite:

  override val munitTimeout = 1.minute

  private given ExecutionContext = ExecutionContext.global

  private object Overlaps extends ClientInterceptor:
    val inside   = AtomicInteger()
    val overlaps = AtomicInteger()
    val sent     = AtomicInteger()

    private def held(send: => Unit): Unit =
      if inside.incrementAndGet() > 1 then overlaps.incrementAndGet(): Unit
      try
        Thread.sleep(20)
        send
      finally
        inside.decrementAndGet(): Unit
        sent.incrementAndGet(): Unit

    def interceptCall[Q, R](
        method: MethodDescriptor[Q, R],
        options: CallOptions,
        next: Channel
    ): ClientCall[Q, R] =
      new ForwardingClientCall.SimpleForwardingClientCall[Q, R](next.newCall(method, options)):
        override def sendMessage(message: Q): Unit = held(super.sendMessage(message))
        override def halfClose(): Unit             = held(super.halfClose())

  /** A process that reads everything and answers nothing. */
  private object Silent extends WorkflowGrpc.Workflow:
    val seen = ConcurrentLinkedQueue[WorkflowIn.Message]()
    def handle(out: StreamObserver[WorkflowOut]): StreamObserver[WorkflowIn] =
      new StreamObserver[WorkflowIn]:
        def onNext(in: WorkflowIn): Unit = seen.add(in.message): Unit
        def onError(t: Throwable): Unit  = ()
        def onCompleted(): Unit          = out.onCompleted()

  private var server: Server          = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    server = ServerBuilder
      .forPort(0)
      .addService(WorkflowGrpc.bindService(Silent, ExecutionContext.global))
      .build()
      .start()
    channel = ManagedChannelBuilder
      .forAddress("127.0.0.1", server.getPort)
      .usePlaintext()
      .intercept(Overlaps)
      .build()

  override def afterAll(): Unit =
    channel.shutdownNow(): Unit
    server.shutdownNow(): Unit

  test("a workflow's command and step, sent at once, are written one after the other") {
    val settings =
      Settings(
        s"127.0.0.1:${server.getPort}",
        0,
        "127.0.0.1",
        5.seconds,
        1.second,
        30.seconds,
        2.seconds
      )
    val conversation = GrpcConversation(channel, settings)
    val rounds       = 10
    for round <- 1 to rounds do
      val before  = Overlaps.sent.get()
      val session = conversation.open(
        Init(ComponentKind.Workflow, ComponentId("checkout"), EntityId(s"w$round"), None)
      )
      val start = CountDownLatch(1)
      val query = Thread.ofVirtual().start { () =>
        start.await()
        session.command(
          Command(
            round.toLong * 2,
            MethodName("status"),
            Payload(Payload.Json, "unit", "{}".getBytes),
            Metadata.empty,
            snapshotRequested = false
          )
        ): Unit
      }
      val step = Thread.ofVirtual().start { () =>
        start.await()
        session.runStep(round.toLong * 2 + 1, "reserve", None, Metadata.empty): Unit
      }
      start.countDown()
      query.join()
      step.join()
      // The init, the command and the step are on the wire before the stream is closed.
      while Overlaps.sent.get() < before + 3 do Thread.sleep(5)
      session.close()
    assertEquals(Overlaps.overlaps.get(), 0, "two writers were inside one stream at once")
    val kinds = Silent.seen.asScala.toVector.map(_.getClass.getSimpleName)
    assertEquals(kinds.count(_ == "Command"), rounds, kinds)
    assertEquals(kinds.count(_ == "RunStep"), rounds, kinds)
  }
