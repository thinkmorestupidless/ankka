package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.event_sourced.{EventSourcedGrpc, EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.payload.Outcome
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannelBuilder, ServerBuilder}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.ExecutionContext

/**
 * Research verify item 1, second half: grpc-netty-shaded starts and serves beside a Pekko
 * ActorSystem in one JVM, on the real EventSourced service. Kept as the smallest proof that the
 * generated stubs work end to end.
 */
class GrpcBesidePekkoSpike extends munit.FunSuite:

  test("a shaded-netty gRPC server serves a bidirectional stream beside an ActorSystem") {
    val system = ActorSystem(Behaviors.empty[Nothing], "spike")
    val service = new EventSourcedGrpc.EventSourced:
      def handle(out: StreamObserver[EventSourcedOut]): StreamObserver[EventSourcedIn] =
        new StreamObserver[EventSourcedIn]:
          def onNext(in: EventSourcedIn): Unit =
            in.message.command.foreach { c =>
              out.onNext(
                EventSourcedOut(
                  EventSourcedOut.Message.Reply(
                    EventSourcedOut.Reply(
                      commandId = c.id,
                      outcome = Some(Outcome(Outcome.Outcome.NoReply(Outcome.NoReply())))
                    )
                  )
                )
              )
            }
          def onError(t: Throwable): Unit = out.onError(t)
          def onCompleted(): Unit         = out.onCompleted()
    val server = ServerBuilder
      .forPort(0)
      .addService(EventSourcedGrpc.bindService(service, ExecutionContext.global))
      .build()
      .start()
    val channel =
      ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort).usePlaintext().build()
    val stub           = EventSourcedGrpc.stub(channel)
    val latch          = new CountDownLatch(3)
    @volatile var seen = Vector.empty[Long]
    val in = stub.handle(new StreamObserver[EventSourcedOut]:
      def onNext(o: EventSourcedOut): Unit =
        o.message.reply.foreach { r =>
          seen :+= r.commandId; latch.countDown()
        }
      def onError(t: Throwable): Unit = ()
      def onCompleted(): Unit         = ())
    (1L to 3L).foreach(i =>
      in.onNext(
        EventSourcedIn(
          EventSourcedIn.Message.Command(EventSourcedIn.Command(id = i, name = "noop"))
        )
      )
    )
    in.onCompleted()
    assert(latch.await(5, TimeUnit.SECONDS))
    assertEquals(seen, Vector(1L, 2L, 3L))
    channel.shutdownNow()
    server.shutdownNow()
    system.terminate()
  }
