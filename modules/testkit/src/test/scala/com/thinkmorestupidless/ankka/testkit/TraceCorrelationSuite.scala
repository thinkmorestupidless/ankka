package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Observability, SpanOutcome, Trace}
import munit.FunSuite

import scala.concurrent.duration.*

/**
 * That a trace is actually a trace: spans from different components, joined.
 *
 * `TraceSuite` in `runtime` proves assembly from records handed to it. This proves the records
 * arrive correlated in the first place — through a real `ComponentClient` call, over sharding, with
 * the identity carried in `Metadata` and nothing added to any protocol type.
 *
 * Without this, every invocation is its own root and the Traces panel shows a flat list of
 * unrelated things, which looks plausible and explains nothing.
 */
final class TraceCorrelationSuite extends FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(ProfileEntity.descriptor)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  private def recorder = Observability(testKit.service.system).recorder

  test("an invocation with no inbound trace is a root, and gets one of its own") {
    val id     = EntityId("root-1")
    val before = recorder.recorded
    val _ = testKit.componentClient
      .forKeyValueEntity(id)
      .call(ProfileEntity.register)
      .invoke(Profile("Ada", "ada@example.com", 1))

    val spans = recorder.snapshot().filter(_.spanId > before)
    assertEquals(spans.size, 1, "one invocation, one span")
    assertEquals(spans.head.parentSpanId, 0L, "nothing called it, so it has no parent")
    assertNotEquals(spans.head.traceId, 0L, "but it still belongs to a trace")
  }

  test("a component calling another component produces one trace, not two") {
    val id = EntityId("nested-1")
    val _ = testKit.componentClient
      .forKeyValueEntity(id)
      .call(ProfileEntity.register)
      .invoke(Profile("Grace", "grace@example.com", 1))

    // Drive a second call from inside the first's trace: `Trace.within` is what a host does
    // around a handler, so this is the same path a nested ComponentClient call takes.
    val outerTrace = Trace.mint()
    val outerSpan  = 4242L
    Trace.within(outerTrace, outerSpan) {
      val _ = testKit.componentClient
        .forKeyValueEntity(id)
        .call(ProfileEntity.rename)
        .invoke("Grace Hopper")
    }

    val spans = recorder.spansOf(outerTrace)
    assertEquals(spans.size, 1, "the callee recorded a span against the caller's trace")
    assertEquals(
      spans.head.parentSpanId,
      outerSpan,
      "and parented it to the caller's span, which is what makes a trace a tree"
    )
    assertEquals(spans.head.outcome, SpanOutcome.Ok)
  }

  test("the trace assembles into a tree with the caller above the callee") {
    val id = EntityId("tree-1")
    val _ = testKit.componentClient
      .forKeyValueEntity(id)
      .call(ProfileEntity.register)
      .invoke(Profile("Alan", "alan@example.com", 1))

    val traceId = Trace.mint()
    // Stand in for the entry point's own span, as HttpServer will once it mints one.
    val entry = recorder.begin(traceId, 0L, componentRef = 99, handlerRef = 99)
    Trace.within(traceId, entry.id) {
      val _ = testKit.componentClient
        .forKeyValueEntity(id)
        .call(ProfileEntity.rename)
        .invoke("Alan Turing")
    }
    recorder.complete(entry, SpanOutcome.Ok)

    val assembled = Trace.assemble(traceId, recorder.spansOf(traceId), recorder.oldestOverwritten)

    assertEquals(assembled.roots.size, 1, "one root: the entry point")
    assertEquals(assembled.roots.head.spanId, entry.id)
    assertEquals(assembled.roots.head.children.size, 1, "with the entity's work beneath it")
    assert(!assembled.roots.head.children.head.parentUnknown, "and the parent is known")
  }

  test("the current trace does not leak past the work it belongs to") {
    assertEquals(Trace.currentTrace, None, "nothing is in flight on this thread")
    Trace.within(1L, 2L) {
      assertEquals(Trace.currentTrace, Some((1L, 2L)))
      Trace.within(3L, 4L)(assertEquals(Trace.currentTrace, Some((3L, 4L))))
      assertEquals(Trace.currentTrace, Some((1L, 2L)), "an inner scope restores the outer one")
    }
    assertEquals(Trace.currentTrace, None, "and the outermost restores nothing-in-flight")
  }
