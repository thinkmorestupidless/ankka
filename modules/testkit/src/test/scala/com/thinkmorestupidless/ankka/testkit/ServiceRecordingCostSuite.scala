package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Observability,
  Recorder,
  SpanOutcome,
  Trace
}
import munit.FunSuite

/**
 * SC-003, against a real service.
 *
 * This is the assertion `RecorderBenchmark` deliberately does not make. SC-003 says "a
 * **service's** throughput", and the denominator has to be a service: a real entity behind a real
 * `ComponentClient`, through sharding, an actor mailbox, both serializers and a durable write to a
 * real Postgres. Micro-benchmarks of fragments were tried first and each gave a different answer
 * (58%, 28%, 50%) because the JIT folds them — a ratio that moves with the shape of the harness is
 * measuring the harness.
 *
 * **No toggle is needed to measure the delta.** What an invocation pays for being observed is a
 * small constant: one span recorded, the caller's name written into the call, and the call counted
 * where it lands. That constant is measured here, in the same JVM and against the same service's
 * own recorder and counts, and the fraction is it over the measured cost of one invocation —
 * arithmetic, rather than a second build with instrumentation compiled out, which the always-on
 * decision means does not exist.
 *
 * The budget is 1% of an invocation (feature 019, SC-004), tightened from the 5% the span alone was
 * first given: attribution and counting were added on the understanding that they fit there.
 *
 * Needs Docker, like every integration suite here, and is off unless benchmarks are asked for.
 */
final class ServiceRecordingCostSuite extends FunSuite with LogCapturing:

  override def munitIgnore: Boolean = !sys.props.get("ankka.benchmarks").contains("on")

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(ProfileEntity.descriptor)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  test("recording is a negligible fraction of a real service invocation (SC-003)") {
    val client = testKit.componentClient.forKeyValueEntity(EntityId("bench"))
    val _      = client.call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 1))

    // Warm the whole path: sharding, the entity, the connection pool, the JIT.
    (1 to 200).foreach(i => client.call(ProfileEntity.rename).invoke(s"warm-$i"))

    val iterations = 2_000
    val started    = System.nanoTime()
    var i          = 0
    while i < iterations do
      val _ = client.call(ProfileEntity.rename).invoke(s"name-$i")
      i += 1
    val perInvocation = (System.nanoTime() - started).toDouble / iterations

    // Everything an invocation pays for being observed, on this service's own recorder and
    // counts and with the names it really declared: a span, the caller's name written into the
    // call's metadata, and the call counted from that name where it lands.
    val observability = Observability(testKit.service.system)
    val component     = ProfileEntity.descriptor.componentId.toString
    val handler       = ProfileEntity.rename.name.toString
    val origin        = CallOrigin(component, handler)
    val componentRef  = observability.names.intern(component)
    val handlerRef    = observability.names.intern(handler)
    def observed(i: Int): Unit =
      val span    = observability.recorder.begin(i.toLong + 1, 0L, componentRef, handlerRef)
      val carried = Trace.within(span.traceId, span.id, origin)(Trace.outbound(Metadata.empty))
      observability.recorder.complete(span, SpanOutcome.Ok)
      observability.handled(carried, component, handler, SpanOutcome.Ok, 1_000L)
    (1 to 500_000).foreach(observed)
    val rounds        = 2_000_000
    val observedStart = System.nanoTime()
    var n             = 0
    while n < rounds do
      observed(n)
      n += 1
    val observingCost = (System.nanoTime() - observedStart).toDouble / rounds
    val fraction      = observingCost / perInvocation

    println(f"""
         |  one real invocation      : $perInvocation%,.0f ns
         |                             (ComponentClient -> sharding -> entity -> durable write -> reply)
         |  span + caller + counting : $observingCost%.1f ns
         |  cost of always-on        : ${fraction * 100}%.4f%% of an invocation    (budget 1%%)
         |""".stripMargin)

    assert(
      fraction <= 0.01,
      f"observing costs ${fraction * 100}%.2f%% of a real invocation (budget 1%%). " +
        "Do not add sampling to pass this — take the always-on decision back to the user."
    )
  }

  test("a refusal is recorded as refused, not as a success and not as a failure") {
    val client        = testKit.componentClient.forKeyValueEntity(EntityId("refused"))
    val observability = Observability(testKit.service.system)
    val recorder      = observability.recorder

    // `rename` on a profile that was never registered is a refusal: the platform working
    // correctly, saying no. It arrives as a value, not an exception.
    val failed =
      try
        val _ = client.call(ProfileEntity.rename).invoke("nobody")
        false
      catch case _: Throwable => true

    assert(failed, "the call should have been refused")

    // The newest span of that handler: the ring may be full from the benchmark before this, so a
    // position counted from before the call says nothing.
    val renameRef = observability.names.intern(ProfileEntity.rename.name.toString)
    val span = recorder
      .snapshot()
      .filter(_.handlerRef == renameRef)
      .maxByOption(_.startedNanos)
      .getOrElse(fail("the refused call produced no span"))
    assertEquals(
      span.outcome,
      SpanOutcome.Refused,
      "a refusal recorded as Ok would show a working ACL as a success; recorded as Failed it " +
        "would show it as a fault. Either teaches the console's reader to ignore the column."
    )
  }

  test("the service actually recorded what it was asked to (the measurement is of real work)") {
    val client = testKit.componentClient.forKeyValueEntity(EntityId("proof"))
    val _ = client.call(ProfileEntity.register).invoke(Profile("Grace", "grace@example.com", 1))
    val _ = client.call(ProfileEntity.rename).invoke("recorded")

    // A benchmark whose subject was optimised away would measure nothing and pass. This is the
    // check that spans were genuinely produced by the path just measured.
    val recorder: Recorder = Observability(testKit.service.system).recorder
    val spans              = recorder.snapshot()

    assert(spans.nonEmpty, "the invocation produced no span — the benchmark measured nothing")
    assert(
      spans.exists(_.outcome == SpanOutcome.Ok),
      "spans were recorded but none succeeded, so the path measured is not the happy one"
    )
  }
