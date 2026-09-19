package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Observability, Recorder, SpanOutcome}
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
 * **No toggle is needed to measure the delta.** Recording costs a known, stable 22ns per span
 * (`RecorderBenchmark`, measured over five million iterations), and an invocation records exactly
 * one span at the entity. So the fraction is that constant over the measured cost of one invocation
 * — arithmetic, rather than a second build with instrumentation compiled out, which the always-on
 * decision means does not exist.
 *
 * Needs Docker, like every integration suite here, and is off unless benchmarks are asked for.
 */
final class ServiceRecordingCostSuite extends FunSuite:

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

    // Measured independently and stable across runs; see RecorderBenchmark.
    val recordingCost = 22.0
    val fraction      = recordingCost / perInvocation

    println(f"""
         |  one real invocation : $perInvocation%,.0f ns
         |                        (ComponentClient -> sharding -> entity -> durable write -> reply)
         |  recording one span  : $recordingCost%.1f ns
         |  cost of always-on   : ${fraction * 100}%.4f%% of an invocation    (budget 5%%)
         |""".stripMargin)

    assert(
      fraction <= 0.05,
      f"recording costs ${fraction * 100}%.2f%% of a real invocation (budget 5%%). " +
        "Do not add sampling to pass this — take the always-on decision back to the user."
    )
  }

  test("a refusal is recorded as refused, not as a success and not as a failure") {
    val client   = testKit.componentClient.forKeyValueEntity(EntityId("refused"))
    val recorder = Observability(testKit.service.system).recorder

    // `rename` on a profile that was never registered is a refusal: the platform working
    // correctly, saying no. It arrives as a value, not an exception.
    val before = recorder.snapshot().size
    val failed =
      try
        val _ = client.call(ProfileEntity.rename).invoke("nobody")
        false
      catch case _: Throwable => true

    assert(failed, "the call should have been refused")

    val span = recorder.snapshot().take(recorder.snapshot().size - before).head
    assertEquals(
      span.outcome,
      SpanOutcome.Refused,
      "a refusal recorded as Ok would show a working ACL as a success; recorded as Failed it " +
        "would show it as a fault. Either teaches the console's reader to ignore the column."
    )
  }

  test("the service actually recorded what it was asked to (the measurement is of real work)") {
    val client = testKit.componentClient.forKeyValueEntity(EntityId("proof"))
    val _  = client.call(ProfileEntity.register).invoke(Profile("Grace", "grace@example.com", 1))
    val _2 = client.call(ProfileEntity.rename).invoke("recorded")

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
