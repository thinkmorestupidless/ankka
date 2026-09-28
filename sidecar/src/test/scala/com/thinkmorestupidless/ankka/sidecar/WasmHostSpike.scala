package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{Kind, SidecarInfo, Spec}
import ankka.protocol.v1.event_sourced.{EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.payload.Payload
import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.{
  HostFunction,
  ImportValues,
  Instance,
  Machine,
  WasmFunctionHandle
}
import com.dylibso.chicory.wasm.types.{FunctionType, ValType}
import com.dylibso.chicory.wasm.{Parser, WasmModule}
import com.google.protobuf.{ByteString, CodedInputStream, CodedOutputStream, WireFormat}
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors

import java.io.ByteArrayOutputStream
import java.util.concurrent.{Executors, TimeUnit}
import java.util.function.Function
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The spike docs/design/wasm-hosting.md says a specification waits on: two guests speaking the
 * protocol's own messages over linear memory, loaded into this JVM through Chicory. One is Rust
 * (`src/test/rust/spike-guest`, built to `wasm32-unknown-unknown`), the other Go under TinyGo
 * (`src/test/go/spike-guest`, target `wasm-unknown`, protowire for the envelope); each `build.sh`
 * writes its module under `src/test/resources/wasm/`, and the modules are committed.
 *
 * The first case is a correctness test and always runs, against both guests: discovery, a command
 * from the empty state, and the state fed back in — the stateless shape, end to end, with the
 * guest's JSON carrying the encoding's `"type"` discriminator. The measurements are gated on
 * `-Dankka.benchmarks` like `LoopbackLatencySpike`, whose number they are read against.
 */
class WasmHostSpike extends munit.FunSuite:

  override def munitTimeout: Duration = 10.minutes

  final case class Language(name: String, resource: String):
    lazy val module: WasmModule = Parser.parse(getClass.getResourceAsStream(resource))
    lazy val compiled: Function[Instance, Machine] = MachineFactoryCompiler.compile(module)

  private val languages = Seq(
    Language("rust", "/wasm/spike-guest.wasm"),
    Language("go", "/wasm/spike-guest-go.wasm")
  )

  /**
   * One loaded instance and the calls the ABI sketch names. `invoke` is what the host answers the
   * guest's `ankka1::invoke` import with, on the guest's calling thread. No factory means Chicory's
   * interpreter.
   */
  final class Guest(
      language: Language,
      factory: Option[Function[Instance, Machine]],
      invoke: Array[Byte] => Array[Byte] = identity
  ):
    private val host = new HostFunction(
      "ankka1",
      "invoke",
      FunctionType.of(List(ValType.I32, ValType.I32).asJava, List(ValType.I64).asJava),
      new WasmFunctionHandle:
        def apply(inst: Instance, args: Long*): Array[Long] =
          val request = inst.memory().readBytes(args(0).toInt, args(1).toInt)
          val answer  = invoke(request)
          val ptr     = inst.`export`("ankka1_alloc").apply(answer.length.toLong)(0).toInt
          inst.memory().write(ptr, answer)
          Array(packed(ptr, answer.length))
    )
    val instance: Instance =
      val builder = Instance
        .builder(language.module)
        .withImportValues(ImportValues.builder().addFunction(host).build())
      val built = factory.fold(builder)(builder.withMachineFactory).build()
      // A reactor's own initialisation (TinyGo exports one; Rust needs none), before any call.
      Try(built.`export`("_initialize")).foreach(_.apply())
      built
    private val alloc = instance.`export`("ankka1_alloc")
    private val free  = instance.`export`("ankka1_free")
    private val fns = Map(
      "discover" -> instance.`export`("ankka1_discover"),
      "handle"   -> instance.`export`("ankka1_handle"),
      "fold"     -> instance.`export`("ankka1_fold"),
      "call_out" -> instance.`export`("ankka1_call_out")
    )

    /** Bytes in through `alloc`, which the guest frees; bytes out, which the host frees. */
    def call(name: String, bytes: Array[Byte]): Array[Byte] =
      val ptr = alloc.apply(bytes.length.toLong)(0).toInt
      instance.memory().write(ptr, bytes)
      val out  = fns(name).apply(ptr.toLong, bytes.length.toLong)(0)
      val rptr = (out >>> 32).toInt
      val rlen = (out & 0xffffffffL).toInt
      val res  = instance.memory().readBytes(rptr, rlen)
      free.apply(rptr.toLong, rlen.toLong)
      res

    def discover(): Spec =
      Spec.parseFrom(call("discover", SidecarInfo("1.0", "0.0.0").toByteArray))

    def handle(
        state: Option[Payload],
        command: EventSourcedIn.Command
    ): (EventSourcedOut.Reply, Payload) =
      val reply    = call("handle", envelope(state.map(_.toByteArray), Some(command.toByteArray)))
      val (f1, f2) = fields(reply)
      (EventSourcedOut.Reply.parseFrom(f1.get), Payload.parseFrom(f2.get))

    def fold(state: Option[Payload], event: Payload): Payload =
      Payload.parseFrom(call("fold", envelope(state.map(_.toByteArray), Some(event.toByteArray))))

    def callOut(bytes: Array[Byte]): Array[Byte] = call("call_out", bytes)

  private def packed(ptr: Int, len: Int): Long = (ptr.toLong << 32) | (len.toLong & 0xffffffffL)

  /** The spike's envelope: two length-delimited fields, the protocol's messages inside. */
  private def envelope(f1: Option[Array[Byte]], f2: Option[Array[Byte]]): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out   = CodedOutputStream.newInstance(bytes)
    f1.foreach(b => out.writeByteArray(1, b))
    f2.foreach(b => out.writeByteArray(2, b))
    out.flush()
    bytes.toByteArray

  private def fields(bytes: Array[Byte]): (Option[Array[Byte]], Option[Array[Byte]]) =
    val in                          = CodedInputStream.newInstance(bytes)
    var f1, f2: Option[Array[Byte]] = None
    var tag                         = in.readTag()
    while tag != 0 do
      require(WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED)
      val b = in.readBytes().toByteArray
      WireFormat.getTagFieldNumber(tag) match
        case 1 => f1 = Some(b)
        case 2 => f2 = Some(b)
        case _ => ()
      tag = in.readTag()
    (f1, f2)

  private def json(manifest: String, text: String): Payload =
    Payload("application/json", manifest, ByteString.copyFromUtf8(text))

  private def item(i: Int): String =
    s"""{"productId":"p$i","name":"Pen $i","quantity":${i % 5 + 1}}"""

  private def addItem(id: Long, i: Int): EventSourcedIn.Command =
    EventSourcedIn.Command(id = id, name = "add-item", payload = Some(json("Item", item(i))))

  private def cartOf(n: Int): Payload =
    json("Cart", s"""{"items":[${(0 until n).map(item).mkString(",")}]}""")

  private def percentiles(samples: Array[Long]): (Long, Long) =
    val sorted = samples.sorted
    (sorted(sorted.length / 2), sorted((sorted.length * 0.99).toInt))

  private def measure(n: Int)(f: => Any): (Long, Long) =
    percentiles(Array.fill(n) {
      val start = System.nanoTime()
      f
      System.nanoTime() - start
    })

  // ── correctness, always ────────────────────────────────────────────────────

  for language <- languages do
    test(s"${language.name}: discovery and a command, stateless, in the encoding") {
      val guest = Guest(language, Some(language.compiled))

      val spec = guest.discover()
      assertEquals(spec.protocolVersion, "1.0")
      assertEquals(
        spec.components.map(c => (c.kind, c.id)),
        Seq((Kind.EVENT_SOURCED_ENTITY, "cart"))
      )
      assertEquals(
        spec.components.head.handlers.map(h => (h.name, h.readOnly)).toSet,
        Set(("add-item", false), ("get-cart", true))
      )
      assertEquals(spec.components.head.getEventSourced.snapshotEvery, 100)

      val (reply1, state1) = guest.handle(None, addItem(1, 0))
      assertEquals(reply1.commandId, 1L)
      assertEquals(
        reply1.events.map(_.data.toStringUtf8),
        Seq("""{"type":"ItemAdded","item":{"productId":"p0","name":"Pen 0","quantity":1}}""")
      )
      assert(reply1.outcome.exists(_.outcome.isReply))
      assertEquals(reply1.outcome.get.getReply.payload.get.manifest, "done")
      assertEquals(state1.manifest, "Cart")
      assertEquals(state1.data.toStringUtf8, s"""{"items":[${item(0)}]}""")

      // The host hands the state back: the guest holds nothing between calls.
      val (reply2, state2) = guest.handle(Some(state1), addItem(2, 1))
      assertEquals(reply2.events.size, 1)
      assertEquals(state2.data.toStringUtf8, s"""{"items":[${item(0)},${item(1)}]}""")

      // Replay is the same fold the command used.
      val replayed = guest.fold(Some(state1), reply2.events.head)
      assertEquals(replayed.data.toStringUtf8, state2.data.toStringUtf8)

      // A read-only handler answers from the state it was given.
      val (got, _) = guest.handle(
        Some(state2),
        EventSourcedIn.Command(id = 3, name = "get-cart", payload = Some(json("unit", "")))
      )
      assertEquals(got.events, Seq.empty)
      assertEquals(
        got.outcome.get.getReply.payload.get.data.toStringUtf8,
        state2.data.toStringUtf8
      )

      // An unknown handler is a refusal, not a fault.
      val (refused, _) = guest.handle(
        Some(state2),
        EventSourcedIn.Command(id = 4, name = "nope", payload = Some(json("unit", "")))
      )
      assert(refused.outcome.exists(_.outcome.isError))

      // The host's answer to a call out comes back through the guest.
      val echo = Guest(language, Some(language.compiled), invoke = _.reverse)
      assertEquals(echo.callOut(Array[Byte](1, 2, 3)).toSeq, Seq[Byte](3, 2, 1))
    }

  // ── measurements, gated ────────────────────────────────────────────────────

  private def benchmarks = sys.props.contains("ankka.benchmarks")

  test("1. the call cost: handle and fold, against the loopback hop") {
    assume(benchmarks, "-Dankka.benchmarks")
    for
      language        <- languages
      (mode, factory) <- Seq("compiled" -> Some(language.compiled), "interpreted" -> None)
    do
      val guest = Guest(language, factory)
      // The interpreter is measured only far enough to rule it out: Go's 10 KB case is 100 ms a
      // call there, and 2,500 of them is the suite's timeout for a number nobody will read.
      for items <- if mode == "compiled" then Seq(0, 20, 200) else Seq(0, 20) do
        val state = if items == 0 then None else Some(cartOf(items))
        val cmd   = addItem(1, items)
        val evt   = json("Event", s"""{"type":"ItemAdded","item":${item(items)}}""")
        val warm  = if mode == "compiled" then 5000 else 500
        val runs  = if mode == "compiled" then 20000 else 2000
        measure(warm)(guest.handle(state, cmd))
        val (h50, h99) = measure(runs)(guest.handle(state, cmd))
        measure(warm)(guest.fold(state, evt))
        val (f50, f99) = measure(runs)(guest.fold(state, evt))
        val bytes      = state.map(_.serializedSize).getOrElse(0)
        println(
          f"wasm ${language.name}%-4s $mode%-11s state $items%3d items ($bytes%5d bytes): handle p50 ${h50 / 1000}%5dµs p99 ${h99 / 1000}%5dµs   fold p50 ${f50 / 1000}%5dµs p99 ${f99 / 1000}%5dµs"
        )
    for language <- languages do
      val (d50, d99) = measure(2000)(Guest(language, Some(language.compiled)).discover())
      println(
        f"wasm ${language.name}%-4s compiled discover: p50 ${d50 / 1000}%dµs p99 ${d99 / 1000}%dµs"
      )
  }

  test("2. and 4. instantiation cost and memory per instance") {
    assume(benchmarks, "-Dankka.benchmarks")
    for language <- languages do
      val (c50, c99) = measure(200)(Guest(language, Some(language.compiled)))
      println(
        f"wasm ${language.name}%-4s instance with the compiled factory: p50 ${c50 / 1000}%dµs p99 ${c99 / 1000}%dµs"
      )
      val (m50, _) = measure(20)(MachineFactoryCompiler.compile(language.module))
      println(f"wasm ${language.name}%-4s compiling the module: p50 ${m50 / 1000000}%dms")

      def used(): Long =
        System.gc(); Thread.sleep(200); System.gc(); rt.totalMemory() - rt.freeMemory()
      val before = used()
      val kept   = Vector.fill(100)(Guest(language, Some(language.compiled)))
      val after  = used()
      println(
        f"wasm ${language.name}%-4s memory per idle instance: ${(after - before) / kept.size / 1024}%d KiB (${kept.head.instance.memory().initialPages()} initial pages, ${kept.head.instance.memory().pages()} after initialisation)"
      )
      kept.head.discover()
  }

  test("3. a blocking host function on a virtual thread") {
    assume(benchmarks, "-Dankka.benchmarks")
    val n         = 64
    val hold      = 200.millis
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val guests = Vector.tabulate(n) { i =>
      val language = languages(i % languages.size)
      Guest(
        language,
        Some(language.compiled),
        invoke = request =>
          val p = Promise[Array[Byte]]()
          scheduler.schedule(
            (() => p.success(request.reverse)): Runnable,
            hold.toMillis,
            TimeUnit.MILLISECONDS
          )
          Await.result(p.future, 10.seconds)
      )
    }
    val start = System.nanoTime()
    val calls = guests.zipWithIndex.map { (g, i) =>
      Future(g.callOut(Array[Byte](i.toByte, 1, 2, 3)).toSeq)(using AnkkaExecutors.virtual)
    }
    val results =
      Await.result(Future.sequence(calls)(using implicitly, AnkkaExecutors.virtual), 30.seconds)
    val wall = (System.nanoTime() - start) / 1000000
    scheduler.shutdownNow()
    println(
      f"wasm $n guests (both languages) each blocking ${hold.toMillis}ms in a host function on virtual threads (${rt.availableProcessors()} carriers): wall ${wall}ms"
    )
    results.zipWithIndex.foreach((r, i) => assertEquals(r, Seq[Byte](3, 2, 1, i.toByte)))
    // Pinned carriers would serialise the blocks: ceil(64 / carriers) × hold.
    assert(wall < hold.toMillis * 3, s"wall ${wall}ms suggests the carrier was pinned")
  }

  private def rt = Runtime.getRuntime
