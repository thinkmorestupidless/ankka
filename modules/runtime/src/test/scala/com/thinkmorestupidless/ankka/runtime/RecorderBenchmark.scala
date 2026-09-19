package com.thinkmorestupidless.ankka.runtime

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Codecs
import munit.FunSuite

/**
 * The gate on "instrumentation is always on".
 *
 * Feature 007 chose always-on over sampling, and SC-003 is the price agreed for it: throughput and
 * median latency within 5% of the same workload uninstrumented.
 *
 * **If this fails, the correct response is to take the always-on decision back to the user, not to
 * add sampling.** Sampling would leave the spec's assumption and its checklist both reading as
 * though always-on still held, which is the failure mode this project's traps exist to prevent.
 *
 * ==What this measures, and what it deliberately does not==
 *
 * This reports the **absolute cost of recording one span**. It does not assert SC-003's 5%, and
 * that is on purpose: SC-003 says "a *service's* throughput", and a percentage needs a denominator
 * that is a service. Three micro-denominators were tried here first and each gave a different
 * answer — an empty loop said 58%, a jsoniter round-trip said 28%, a testkit entity call said 50%
 * while measuring *faster than the serializer alone*, which is the JIT constant-folding a
 * monomorphic loop rather than any real work happening. A number that moves that much with the
 * shape of the micro-benchmark is measuring the micro-benchmark.
 *
 * So the division of labour is: this suite establishes the cost per span, which is a stable fact
 * and prints the invocation cost at which that fits inside 5%. `ServiceRecordingCostSuite` makes
 * the actual SC-003 assertion, by driving a real service — HTTP in, entity, journal, reply — with
 * recording on and off. That suite can only exist once recording is wired into the hosts, which is
 * why the plan sequenced the gate after the wiring and not before.
 *
 * Off by default: a microbenchmark on a laptop also running Docker and three JVMs measures the
 * laptop. Run it deliberately, on a quiet machine:
 *
 * {{{
 * sbt -Dankka.benchmarks=on 'runtime/testOnly com.thinkmorestupidless.ankka.runtime.RecorderBenchmark'
 * }}}
 */
final class RecorderBenchmark extends FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.benchmarks").contains("on")

  private val Warmup     = 500_000
  private val Iterations = 5_000_000

  /** A small payload of the shape a command carries. */
  private final case class Payload(id: String, quantity: Int, name: String)
  private given JsonValueCodec[Payload] = Codecs.make[Payload]

  /**
   * Nanoseconds per operation, derived from throughput — the clock cannot resolve one operation.
   */
  private def nanosPerOp(warmup: Int, iterations: Int)(body: Int => Unit): Double =
    var i = 0
    while i < warmup do { body(i); i += 1 }
    System.gc()
    Thread.sleep(50)
    i = 0
    val started = System.nanoTime()
    while i < iterations do { body(i); i += 1 }
    (System.nanoTime() - started).toDouble / iterations

  test("the cost of recording one span, and the invocation size it fits inside") {
    val recorder = Recorder(4096)

    val recordingOnly = nanosPerOp(Warmup, Iterations) { i =>
      val span = recorder.begin(i.toLong, 0L, componentRef = 1, handlerRef = 2)
      recorder.complete(span, SpanOutcome.Ok)
    }

    val payload = Payload("cart-1", 2, "Widget")
    val serializerOnly = nanosPerOp(Warmup / 5, Iterations / 10) { _ =>
      val bytes = writeToArray(payload)
      val _     = readFromArray[Payload](bytes)
    }

    println(f"""
         |  recording one span : $recordingOnly%.1f ns
         |  for scale, jsoniter encode + decode of a small payload : $serializerOnly%.1f ns
         |
         |  Recording fits inside 5%% of any invocation costing more than
         |  ${recordingOnly / 0.05}%.0f ns. A deployed request — HTTP parse, actor mailbox,
         |  sharding hop, journal write — is orders of magnitude above that; SC-003 is asserted
         |  against a real service in ServiceRecordingCostSuite, not here.
         |""".stripMargin)

    // The one thing assertable without a service: the cost is a small constant, not a surprise.
    assert(
      recordingOnly < 200.0,
      f"recording a span cost $recordingOnly%.1f ns — that is no longer a cheap constant, " +
        "and the always-on decision should be revisited before anything is built on it."
    )
  }

  test("trace memory is flat in the number of requests (SC-004)") {
    def footprintAfter(requests: Int): Long =
      val recorder = Recorder(4096)
      var i        = 0
      while i < requests do
        val span = recorder.begin(i.toLong, 0L, 1, 2)
        recorder.complete(span, SpanOutcome.Ok)
        i += 1
      System.gc()
      Thread.sleep(50)
      val rt = Runtime.getRuntime
      rt.totalMemory() - rt.freeMemory()

    val small = footprintAfter(10_000)
    val large = footprintAfter(100_000)

    assert(
      large <= small * 1.2,
      s"held memory grew from $small to $large across 10x the requests — the ring is not bounded"
    )
  }
