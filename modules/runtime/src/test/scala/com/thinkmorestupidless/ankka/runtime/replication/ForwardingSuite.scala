package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{ErrorCode, Metadata, MethodName}
import com.thinkmorestupidless.ankka.runtime.{MetaEntry, Observability, SpanKind}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * A write that reaches a follower is handed to the home once, carrying the caller's metadata, and
 * the home's answer is the caller's; a read is answered where it is (feature 051, T037).
 */
class ForwardingSuite extends CopiesSuite:
  import Copy.*

  private val caller = Vector(MetaEntry("x-caller", "endpoint"))

  /** A follower of vienna at leipzig, holding one step. */
  private def follower(t: ScriptedTransport = ScriptedTransport()): Copy =
    val c = copy("leipzig", t)
    c.follow("vienna", Set("vienna", "leipzig"), "frame")
    c

  test("a write at a follower is forwarded once to the home, and the home's answer is the reply") {
    val t = ScriptedTransport()
    t.forwardReply =
      _ => Future.successful(ForwardedReply.Succeeded("wheels".getBytes(UTF_8), Metadata.empty))
    val c = follower(t)
    c.invoke("add-step", "wheels", caller)
    assertEquals(text(c.replies.receiveMessage()), "wheels")
    val calls = t.forwarded.asScala.toVector
    assertEquals(calls.map(_._1), Vector("vienna"))
    val call = calls.head._2
    assertEquals(call.method, MethodName("add-step"))
    assertEquals(String(call.payload, UTF_8), "wheels")
    assertEquals(call.metadata.get("x-caller"), Some("endpoint"))
    assertEquals(call.metadata.get(HostReplication.ForwardedFrom), Some("leipzig"))
    // Written at the home, not here.
    assertEquals(c.journal.size, 3)
  }

  test("a forward that fails is answered NotHome, naming the home") {
    val t = ScriptedTransport()
    t.forwardReply = _ => Future.failed(IllegalStateException("connection refused"))
    val c = follower(t)
    c.invoke("add-step", "wheels", caller)
    val refused = rejected(c.replies.receiveMessage())
    assertEquals(refused.code, ErrorCode.NotHome)
    assert(refused.message.contains("'vienna'"), refused.message)
  }

  test("a forward is one client span here, and no call handled here") {
    val t = ScriptedTransport()
    t.forwardReply =
      _ => Future.successful(ForwardedReply.Succeeded("wheels".getBytes(UTF_8), Metadata.empty))
    val c = follower(t)
    c.invoke("add-step", "wheels", caller)
    c.replies.receiveMessage()
    val o = Observability(c.kit.system)
    eventually()(Option.when(o.recorder.snapshot().exists(_.kind == SpanKind.Client))(()))
    assertEquals(o.recorder.snapshot().count(_.kind == SpanKind.Client), 1)
    assertEquals(o.calls.snapshot(System.currentTimeMillis()).handled, 0L)
  }

  test("a home that cannot be reached is not called, and the write is refused NotHome") {
    val t = ScriptedTransport()
    t.reachableNow = _ != "vienna"
    val c = follower(t)
    c.invoke("add-step", "wheels", caller)
    val refused = rejected(c.replies.receiveMessage())
    assertEquals(refused.code, ErrorCode.NotHome)
    assert(refused.message.contains("cannot be reached"), refused.message)
    assert(t.forwarded.isEmpty)
  }

  test("a read at a follower is answered there, with no call to the home") {
    val c = follower()
    c.invoke("get", metadata = caller)
    assertEquals(track(c.replies.receiveMessage()), Track(Vector("frame")))
    assert(c.transport.forwarded.isEmpty)
  }

  test("a write already forwarded once is not forwarded again") {
    val c = follower()
    c.invoke("add-step", "wheels", caller :+ MetaEntry(HostReplication.ForwardedFrom, "prague"))
    val refused = rejected(c.replies.receiveMessage())
    assertEquals(refused.code, ErrorCode.NotHome)
    assert(c.transport.forwarded.isEmpty)
  }
