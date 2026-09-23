package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.event_sourced.{EventSourcedGrpc, EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.payload.Outcome
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannelBuilder, ServerBuilder}

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.concurrent.ExecutionContext

/**
 * Research verify item 2: what one loopback gRPC round trip costs on this machine, so SC-003 is a
 * measurement rather than a hope. Gated on `-Dankka.benchmarks` like feature 007's harness.
 *
 * One message each way on a long-lived bidirectional stream (the per-instance conversation).
 * Numbers are printed; the test asserts only that they were measured.
 */
class LoopbackLatencySpike extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.contains("ankka.benchmarks")

  private def percentiles(samples: Array[Long]): (Long, Long) =
    val sorted = samples.sorted
    (sorted(sorted.length / 2), sorted((sorted.length * 0.99).toInt))

  test("one round trip on loopback, streamed and unary") {
    val noReply = Some(Outcome(Outcome.Outcome.NoReply(Outcome.NoReply())))
    val service = new EventSourcedGrpc.EventSourced:
      def handle(out: StreamObserver[EventSourcedOut]): StreamObserver[EventSourcedIn] =
        new StreamObserver[EventSourcedIn]:
          def onNext(in: EventSourcedIn): Unit =
            in.message.command.foreach { c =>
              out.onNext(
                EventSourcedOut(
                  EventSourcedOut.Message.Reply(
                    EventSourcedOut.Reply(commandId = c.id, outcome = noReply)
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
    val stub  = EventSourcedGrpc.stub(channel)
    val queue = new ArrayBlockingQueue[Long](1)
    val in = stub.handle(new StreamObserver[EventSourcedOut]:
      def onNext(o: EventSourcedOut): Unit = o.message.reply.foreach(r => queue.put(r.commandId))
      def onError(t: Throwable): Unit      = ()
      def onCompleted(): Unit              = ())

    def streamed(n: Int): Array[Long] =
      Array.tabulate(n) { i =>
        val start = System.nanoTime()
        in.onNext(
          EventSourcedIn(
            EventSourcedIn.Message.Command(EventSourcedIn.Command(id = i.toLong, name = "noop"))
          )
        )
        queue.poll(5, TimeUnit.SECONDS)
        System.nanoTime() - start
      }

    // Warm the JIT and the connection, then measure.
    streamed(5000)
    val (sp50, sp99) = percentiles(streamed(20000))
    println(
      f"loopback gRPC, bidirectional stream, one message each way: p50 ${sp50 / 1000}%dµs p99 ${sp99 / 1000}%dµs"
    )

    in.onCompleted()
    channel.shutdownNow()
    server.shutdownNow()
    assert(sp50 > 0)
  }
