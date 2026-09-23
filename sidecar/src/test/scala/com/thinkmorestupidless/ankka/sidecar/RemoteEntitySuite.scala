package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.event_sourced.EventSourcedIn
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.remote.{Payload, PayloadKeys}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * The remote event sourced host on a real journal: `AnkkaTestKit`'s Postgres, the sidecar's
 * conversation, and the process double at the far end. What the spec's first story and its edge
 * cases promise, proven where the journal is real and the process is scriptable.
 */
class RemoteEntitySuite extends munit.FunSuite:

  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  private val recorder                = ProcessDouble.recorder("conformance", snapshotEvery = 3)
  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(ProcessDouble.DoubleSpec(entities = Vector(recorder)))
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings     = Settings(s"127.0.0.1:$port", 0, 5.seconds, 1.second, 2.seconds, 2.seconds)
    val conversation = GrpcConversation(channel, settings)
    val descriptors  = Discovery.validate(double.toSpec).toOption.get.descriptors
    kit = AnkkaTestKit.start(descriptors, Nil, 60.seconds, _.withConversation(conversation))

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  private val component = ComponentId("conformance")

  private def invokeAsync(id: String, name: String, input: String = ""): Future[String] =
    kit.componentClient.transportRef
      .ask(
        component,
        EntityId(id),
        MethodName(name),
        input.getBytes,
        Metadata.empty
          .set(PayloadKeys.Manifest, "string")
          .set(PayloadKeys.ContentType, Payload.Text)
      )
      .map(bytes => String(bytes))

  private def invoke(id: String, name: String, input: String = ""): Either[CommandError, String] =
    Try(Await.result(invokeAsync(id, name, input), 10.seconds)).toEither.left.map {
      case e: CommandError => e
      case other           => CommandError(other.getMessage, ErrorCode.Internal)
    }

  private def count(id: String): String = invoke(id, "count").toOption.get

  test("S1.1 a command persists and replies; a query sees the events") {
    assertEquals(invoke("a", "record", "one"), Right("done"))
    assertEquals(invoke("a", "record", "two"), Right("done"))
    assertEquals(count("a"), "2")
  }

  test("S1.2 a refusal persists nothing and carries its code") {
    val _       = invoke("b", "record")
    val refused = invoke("b", "refuse")
    assert(refused.left.exists(_.code == ErrorCode.Conflict), refused)
    assertEquals(count("b"), "1")
  }

  test("a no-reply persists; the caller gets no answer, as in-process") {
    val silent = invoke("c", "no-reply")
    assert(silent.isLeft, silent)
    assertEquals(count("c"), "1")
  }

  test(
    "S1.3 the state is recovered from the journal after a restart, replayed through the process"
  ) {
    (1 to 3).foreach(i => assertEquals(invoke("d", "record", s"$i"), Right("done")))
    kit.restartService()
    assertEquals(count("d"), "3")
  }

  test("S1.4 a snapshot is taken at the interval and replay starts after it") {
    (1 to 4).foreach(i => assertEquals(invoke("e", "record", s"$i"), Right("done")))
    kit.restartService()
    val before = double.received.size
    assertEquals(count("e"), "4")
    // The reopened stream received an Init carrying a snapshot, then exactly one replayed event.
    val after = double.received.subList(before, double.received.size)
    val inits = after.toArray.toVector.collect {
      case ProcessDouble.Received(_, in: EventSourcedIn) if in.message.isInit => in.getInit
    }
    assert(inits.exists(_.snapshot.isDefined), "expected an Init with a snapshot")
    val replayed = after.toArray.toVector.count {
      case ProcessDouble.Received(_, in: EventSourcedIn) => in.message.isEvent; case _ => false
    }
    assertEquals(replayed, 1)
  }

  test("delete makes the instance fresh; the id stays usable") {
    assertEquals(invoke("f", "record"), Right("done"))
    assertEquals(invoke("f", "delete"), Right("done"))
    assertEquals(count("f"), "0")
    assertEquals(invoke("f", "record"), Right("done"))
    assertEquals(count("f"), "1")
  }

  test("expiry makes the instance fresh once it has passed") {
    assertEquals(invoke("g", "record"), Right("done"))
    assertEquals(invoke("g", "expire", "500"), Right("done"))
    Thread.sleep(800)
    assertEquals(count("g"), "0")
  }

  test("ten concurrent commands on one instance are serialized, in order") {
    val all = Future.sequence((1 to 10).map(i => invokeAsync("h", "record", s"$i")))
    Await.result(all, 30.seconds)
    assertEquals(count("h"), "10")
    val inputs = double
      .messagesOf {
        case in: EventSourcedIn if in.message.isCommand && in.getCommand.name == "record" =>
          in.getCommand.payload.map(_.data.toStringUtf8).getOrElse("")
      }
      .filter(s => s.nonEmpty && s.forall(_.isDigit))
      .takeRight(10)
      .map(_.toInt)
    assertEquals(inputs.sorted, inputs, "commands were not sent in the order they were accepted")
  }

  test("ten instances at once") {
    val all = Future.sequence((1 to 10).map(i => invokeAsync(s"p$i", "record")))
    Await.result(all, 30.seconds)
    (1 to 10).foreach(i => assertEquals(count(s"p$i"), "1"))
  }

  test("a fault in the process is Failed, not Refused, and the instance answers the next command") {
    val fault = invoke("i", "misbehave")
    assert(fault.left.exists(e => e.code == ErrorCode.Internal && e.message == "boom"), fault)
    assertEquals(invoke("i", "record"), Right("done"))
    assertEquals(count("i"), "1")
  }

  test("a read-only handler that persists is a violation; nothing is written") {
    val illegal = invoke("j", "query-persists")
    assert(illegal.left.exists(_.message.contains("read-only")), illegal)
    assertEquals(count("j"), "0")
  }

  test("the process restarted mid-conversation: the instance re-inits and replays") {
    assertEquals(invoke("k", "record"), Right("done"))
    double.restart()
    // The first command after the restart may find the old stream dead; the one after it works.
    val first = invoke("k", "record")
    if first.isLeft then assertEquals(invoke("k", "record"), Right("done"))
    assert(count("k").toInt >= 2)
  }

  test(
    "a handler that never replies times out, closes the conversation, and the next command works"
  ) {
    double.knobs.neverReply = true
    val timedOut = invoke("l", "record")
    assert(timedOut.left.exists(_.code == ErrorCode.Timeout), timedOut)
    double.knobs.neverReply = false
    assertEquals(invoke("l", "record"), Right("done"))
    assertEquals(count("l"), "1")
  }

  test("the wrong command id and an unrequested snapshot are refused, and nothing is written") {
    double.knobs.wrongCommandId = true
    val wrong = invoke("m", "record")
    assert(wrong.left.exists(_.message.contains("was in flight")), wrong)
    double.knobs.wrongCommandId = false
    double.knobs.unrequestedSnapshot = true
    val unasked = invoke("m", "record")
    assert(unasked.left.exists(_.message.contains("nobody asked")), unasked)
    double.knobs.unrequestedSnapshot = false
    assertEquals(count("m"), "0")
  }

  test("passivation closes the conversation; the next command re-opens it") {
    assertEquals(invoke("n", "record"), Right("done"))
    val streams = double.liveStreams
    // The test kit passivates idle instances after 10s.
    Thread.sleep(13000)
    assert(
      double.liveStreams < streams,
      s"expected the stream to close on passivation ($streams → ${double.liveStreams})"
    )
    assertEquals(count("n"), "1")
  }
