package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import ankka.protocol.v1.discovery.{
  Component,
  EventSourcedDetail,
  Handler,
  Kind,
  Source as PbSource,
  Spec,
  ViewDetail
}
import ankka.protocol.v1.wasm.WasmSpec
import com.dylibso.chicory.runtime.{HostFunction, ImportValues, Instance, WasmFunctionHandle}
import com.dylibso.chicory.wabt.Wat2Wasm
import com.dylibso.chicory.wasm.types.{FunctionType, ValType}
import com.thinkmorestupidless.ankka.core.{
  BuildInfo,
  CommandError,
  ComponentId,
  ComponentKind,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import com.thinkmorestupidless.ankka.runtime.remote.*
import com.thinkmorestupidless.ankka.sidecar.Discovery.Shape
import com.thinkmorestupidless.ankka.agent.TestModelProvider
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.sidecar.conformance.ConformanceTarget
import com.thinkmorestupidless.ankka.sidecar.wasm.*
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters.*

/**
 * The host itself, against real modules: what it refuses to load, what it discovers, how a command
 * and a replay reach a guest, what a trap costs, what the `config` import withholds, and that calls
 * blocked inside an import cost one wait between them, not one each.
 *
 * The spike guest (`src/test/rust/spike-guest`) is a stateless cart speaking the ABI; the refusals
 * use modules assembled from WebAssembly text here, each wrong in exactly one way.
 */
class WasmHostSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val spikeGuest: Path = Path.of(getClass.getResource("/wasm/spike-guest.wasm").toURI)

  private val settings = Settings(
    "127.0.0.1:0",
    0,
    "127.0.0.1",
    5.seconds,
    1.second,
    5.seconds,
    5.seconds,
    wasmModule = Some(spikeGuest),
    wasmInstances = 2
  )

  private def loaded(path: Path): LoadedModule =
    ModuleLoader.load(path).fold(p => fail(p.mkString("; ")), identity)

  /** A module from WebAssembly text, written where `ModuleLoader` reads a file. */
  private def wat(text: String): Path =
    val file = Files.createTempFile("wasm-host-suite", ".wasm")
    Files.write(file, Wat2Wasm.parse(text))
    file.toFile.deleteOnExit()
    file

  private val required =
    """(memory (export "memory") 1)
      |(func (export "ankka1_alloc") (param i32) (result i32) i32.const 0)
      |(func (export "ankka1_free") (param i32 i32))""".stripMargin

  private def refusals(path: Path): Vector[String] =
    ModuleLoader.load(path).fold(identity, _ => fail(s"$path loaded, and should have been refused"))

  // ── Loading ────────────────────────────────────────────────────────────────

  test("a module missing an export every module needs is refused, naming it") {
    val problems = refusals(wat(s"(module $required)"))
    assert(problems.exists(_.contains("'ankka1_discover'")), problems.toString)
  }

  test("a module speaking another ABI version is refused, naming both versions") {
    val problems = refusals(
      wat(
        s"""(module $required
           |  (func (export "ankka1_discover") (param i32 i32) (result i64) i64.const 0)
           |  (func (export "ankka2_discover") (param i32 i32) (result i64) i64.const 0))""".stripMargin
      )
    )
    assert(
      problems.exists(p =>
        p.contains("'ankka2_discover'") && p.contains("version 2") && p.contains("version 1")
      ),
      problems.toString
    )
  }

  test("a module importing anything but the ankka1 functions is refused, naming the import") {
    val problems = refusals(
      wat(
        s"""(module
           |  (import "wasi_snapshot_preview1" "fd_write" (func (param i32 i32 i32 i32) (result i32)))
           |  $required
           |  (func (export "ankka1_discover") (param i32 i32) (result i64) i64.const 0))""".stripMargin
      )
    )
    assert(problems.exists(_.contains("'wasi_snapshot_preview1.fd_write'")), problems.toString)
  }

  test("a file that is not a module is refused, naming the path") {
    val file = Files.createTempFile("not-a-module", ".wasm")
    Files.writeString(file, "this is not WebAssembly")
    val problems = refusals(file)
    assert(problems.exists(_.contains(file.toString)), problems.toString)
    assert(refusals(file.resolveSibling("missing.wasm")).exists(_.contains("no module at")))
  }

  // ── Discovery ──────────────────────────────────────────────────────────────

  private def imports(env: Map[String, String] = Map.empty) =
    HostImports(settings.commandTimeout, settings.requestTimeout, env.get)

  test("the spike guest's declaration is discovered: a stateless event sourced cart") {
    val module    = loaded(spikeGuest)
    val bootstrap = GuestInstance.build(module, imports().values, settings.wasmMaxMemoryPages)
    val discovered = WasmDiscovery
      .discover(bootstrap, module, BuildInfo.version)
      .fold(p => fail(p.mkString), identity)
    assertEquals(
      discovered.descriptors.map(d => (d.kind, d.componentId.toString)),
      Vector((ComponentKind.EventSourcedEntity, "cart"))
    )
    assertEquals(discovered.shapeOf(ComponentId("cart")), Shape.Stateless)
  }

  private def cartSpec(handler: Handler = Handler("add-item")): Spec =
    Spec(
      protocolVersion = "1.0",
      components = Seq(
        Component(
          kind = Kind.EVENT_SOURCED_ENTITY,
          id = "cart",
          handlers = Seq(handler),
          detail = Component.Detail.EventSourced(EventSourcedDetail(100))
        )
      )
    )

  private val everyExport = ModuleLoader.Exports

  private def problems(wasm: WasmSpec, exports: Set[String] = everyExport): Vector[String] =
    WasmDiscovery.validate(wasm, exports).fold(identity, _ => fail("the declaration was accepted"))

  test("a streaming handler is refused: a module answers every call whole") {
    val found =
      problems(WasmSpec(Some(cartSpec(Handler("watch", streaming = true))), Seq.empty, "1"))
    assert(found.exists(p => p.contains("'watch'") && p.contains("streams")), found.toString)
  }

  test("an autonomous agent needs the export that checks its task results") {
    val agent = Component(kind = Kind.AUTONOMOUS_AGENT, id = "answerer")
    val found = problems(
      WasmSpec(Some(cartSpec().addComponents(agent)), Seq.empty, "1"),
      everyExport - "ankka1_check_task_result"
    )
    assert(
      found.exists(p => p.contains("'answerer'") && p.contains("ankka1_check_task_result")),
      found.toString
    )
  }

  test("a declaration is refused for another ABI version, a bad stateful id, or a missing export") {
    val view = Component(
      kind = Kind.VIEW,
      id = "rows",
      detail = Component.Detail.View(
        ViewDetail(
          source = Some(
            PbSource(
              PbSource.Source.Component(PbSource.ComponentRef(Kind.EVENT_SOURCED_ENTITY, "cart"))
            )
          ),
          rowManifest = "Row"
        )
      )
    )
    val spec = cartSpec().addComponents(view)
    val found =
      problems(WasmSpec(Some(spec), Seq("ghost", "rows"), "2"), everyExport - "ankka1_view")
    for expected <- Seq(
        "ABI version '2'",
        "'ghost' is declared stateful",
        "'rows' is declared stateful",
        "'ankka1_view'"
      )
    do assert(found.exists(_.contains(expected)), s"$expected not in $found")
  }

  // ── The conversation ───────────────────────────────────────────────────────

  private val cartId = ComponentId("cart")

  private def conversation(env: Map[String, String] = Map.empty): WasmConversation =
    WasmConversation(loaded(spikeGuest), settings, imports(env), _ => Shape.Stateless)

  private def item(i: Int) = s"""{"productId":"p$i","name":"Pen $i","quantity":1}"""

  private def json(manifest: String, text: String) =
    Payload(Payload.Json, manifest, text.getBytes("UTF-8"))

  private def command(id: Long, name: String, payload: Payload) =
    Command(id, MethodName(name), payload, Metadata.empty, snapshotRequested = false)

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  private def cartOf(session: InstanceSession, id: Long): String =
    await(session.command(command(id, "get-cart", json("unit", "")))) match
      case Right(reply) =>
        reply.outcome match
          case RemoteOutcome.Reply(payload, _) => String(payload.data, "UTF-8")
          case other                           => fail(s"get-cart answered $other")
      case Left(failure) => fail(s"get-cart failed: $failure")

  test("a command from the empty state, the state handed back, and a replay by fold") {
    val talk    = conversation()
    val session = talk.open(Init(ComponentKind.EventSourcedEntity, cartId, EntityId("c1"), None))
    val first = await(session.command(command(1, "add-item", json("Item", item(0)))))
      .fold(f => fail(f.toString), identity)
    assertEquals(first.commandId, 1L)
    assertEquals(
      first.events.map(e => String(e.data, "UTF-8")),
      Vector(s"""{"type":"ItemAdded","item":${item(0)}}""")
    )
    await(session.command(command(2, "add-item", json("Item", item(1)))))
      .fold(f => fail(f.toString), identity)
    // The guest keeps nothing: the second item is on top of the first because the host held it.
    assertEquals(cartOf(session, 3), s"""{"items":[${item(0)},${item(1)}]}""")

    // A fresh session replays the journal's events, folded before its first command.
    val replayed = talk.open(Init(ComponentKind.EventSourcedEntity, cartId, EntityId("c2"), None))
    replayed.event(1, first.events.head)
    replayed.event(2, json("Event", s"""{"type":"ItemAdded","item":${item(7)}}"""))
    assertEquals(cartOf(replayed, 1), s"""{"items":[${item(0)},${item(7)}]}""")
  }

  test(
    "a trap is a fault for its command only: the held state is kept and the next command served"
  ) {
    val session =
      conversation().open(Init(ComponentKind.EventSourcedEntity, cartId, EntityId("c3"), None))
    await(session.command(command(1, "add-item", json("Item", item(0)))))
      .fold(f => fail(f.toString), identity)
    val failure = await(session.command(command(2, "panic", json("unit", ""))))
    assert(failure.isLeft, failure.toString)
    assertEquals(failure.left.toOption.get.commandId, 2L)
    assertEquals(failure.left.toOption.get.error.code, ErrorCode.Internal)
    await(session.command(command(3, "add-item", json("Item", item(1)))))
      .fold(f => fail(f.toString), identity)
    assertEquals(cartOf(session, 4), s"""{"items":[${item(0)},${item(1)}]}""")
  }

  test(
    "the config import answers a descriptor's variable and withholds every name the platform reserves"
  ) {
    val reserved = Vector(
      "ANTHROPIC_API_KEY",
      "ANKKA_MODEL_NAME",
      "ANKKA_DB_PASSWORD",
      "ANKKA_DB_USER",
      "ANKKA_CLUSTER_MODE",
      "ANKKA_CLUSTER_SERVICE",
      "ANKKA_WASM_MODULE",
      "ANKKA_SIDECAR_PORT",
      "ANKKA_PROCESS_ADDRESS",
      "ANKKA_AUTH_ISSUER",
      "POD_IP",
      "ANKKA_HTTP_PORT",
      "ANKKA_BASE_DOMAIN",
      "ANKKA_HTTPS_PORT"
    )
    val env         = reserved.map(_ -> "secret").toMap + ("GREETING" -> "hello")
    val hostImports = imports(env)
    assertEquals(hostImports.lookup("GREETING"), Some("hello"))
    assertEquals(hostImports.lookup("UNSET"), None)
    reserved.foreach(name => assertEquals(hostImports.lookup(name), None, name))

    // And through a guest: the module asks, and is answered or told there is nothing.
    val session =
      conversation(env).open(Init(ComponentKind.EventSourcedEntity, cartId, EntityId("c4"), None))
    def ask(name: String) =
      await(
        session.command(
          command(1, "config", Payload(Payload.Text, "string", name.getBytes("UTF-8")))
        )
      )
        .fold(f => fail(f.toString), _.outcome)
    ask("GREETING") match
      case RemoteOutcome.Reply(payload, _) => assertEquals(String(payload.data, "UTF-8"), "hello")
      case other                           => fail(s"GREETING answered $other")
    ask("ANKKA_DB_PASSWORD") match
      case RemoteOutcome.Error(CommandError(_, ErrorCode.NotFound)) => ()
      case other => fail(s"ANKKA_DB_PASSWORD answered $other")
  }

  // ── Blocking in an import ──────────────────────────────────────────────────

  test(
    "sixty-four calls each blocked 200ms in an import complete together, not one after another"
  ) {
    val hold = 200.millis
    val blockingInvoke = new HostFunction(
      "ankka1",
      "invoke",
      FunctionType.of(List(ValType.I32, ValType.I32).asJava, List(ValType.I64).asJava),
      new WasmFunctionHandle:
        def apply(instance: Instance, args: Long*): Array[Long] =
          val request = instance.memory().readBytes(args(0).toInt, args(1).toInt)
          Thread.sleep(hold.toMillis)
          Array(HostImports.give(instance, request.reverse))
    )
    val config = new HostFunction(
      "ankka1",
      "config",
      FunctionType.of(List(ValType.I32, ValType.I32).asJava, List(ValType.I64).asJava),
      new WasmFunctionHandle:
        def apply(instance: Instance, args: Long*): Array[Long] = Array(0L)
    )
    val values = ImportValues.builder().addFunction(blockingInvoke).addFunction(config).build()
    val module = loaded(spikeGuest)
    val pool = BlockingPool(() => GuestInstance.build(module, values, settings.wasmMaxMemoryPages))

    def round(): (Long, Vector[Seq[Byte]]) =
      val start = System.nanoTime()
      val calls = (0 until 64).map { i =>
        Future(
          pool.withFresh("ankka1_call_out", 10.seconds)(
            _.call("ankka1_call_out", Array[Byte](i.toByte, 1, 2))
          )
        )(using AnkkaExecutors.virtual)
      }
      val results =
        Await.result(Future.sequence(calls)(using implicitly, AnkkaExecutors.virtual), 30.seconds)
      (
        (System.nanoTime() - start) / 1000000,
        results.map(_.fold(f => fail(f.toString), _.toSeq)).toVector
      )

    round(): Unit // the first round pays for the JIT
    val (wall, results) = round()
    results.zipWithIndex.foreach((r, i) => assertEquals(r, Seq[Byte](2, 1, i.toByte)))
    assert(
      wall < hold.toMillis * 3 / 2,
      s"64 blocked calls took ${wall}ms; together they should cost one hold"
    )
  }

  // ── The Rust cart, end to end ──────────────────────────────────────────────

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * The Rust example built to a module by cargo, or `None` — and a warning naming cargo — where
   * there is no Rust toolchain, as `RemoteOverlaySuite` does for kubectl.
   */
  private lazy val rustCart: Option[Path] =
    val workspace = Path.of(sys.props.getOrElse("user.dir", ".")).resolve("../sdks/rust").normalize
    val found     = Path.of("sdks/rust").toAbsolutePath
    val dir       = if Files.isDirectory(workspace) then workspace else found
    val built =
      try
        val process = ProcessBuilder(
          "cargo",
          "build",
          "-p",
          "shopping-cart",
          "--release",
          "--target",
          "wasm32-unknown-unknown"
        )
          .directory(dir.toFile)
          .inheritIO()
          .start()
        process.waitFor() == 0
      catch case _: java.io.IOException => false
    val module = dir.resolve("target/wasm32-unknown-unknown/release/shopping_cart.wasm")
    if built && Files.isRegularFile(module) then Some(module)
    else
      log.warn(
        "skipping the Rust cart's end-to-end case: `cargo` is not on PATH or the build failed"
      )
      None

  private def http(method: String, url: String, json: Option[String] = None): (Int, String) =
    val request = HttpRequest.newBuilder(URI.create(url))
    json match
      case Some(body) =>
        request
          .method(method, HttpRequest.BodyPublishers.ofString(body))
          .header("Content-Type", "application/json")
      case None => request.method(method, HttpRequest.BodyPublishers.noBody())
    val response =
      HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())

  test(
    "the Rust cart is commanded over HTTP, survives a restart, and writes the Scala cart's journal"
  ) {
    assume(rustCart.isDefined, "cargo is not on PATH")
    val target = ConformanceTarget.ModuleTarget(rustCart.get, "stateless", TestModelProvider())
    try
      val pen = """{"productId":"p1","name":"Pen","quantity":2}"""
      val ink = """{"productId":"p2","name":"Ink","quantity":1}"""
      assertEquals(http("POST", s"${target.baseUrl}/carts/rust-1/items", Some(pen))._1, 204)
      assertEquals(http("POST", s"${target.baseUrl}/carts/rust-1/items", Some(ink))._1, 204)

      target.restart()

      val (status, body) = http("GET", s"${target.baseUrl}/carts/rust-1")
      assertEquals(status, 200, body)
      assert(body.contains(""""productId":"p1"""") && body.contains(""""productId":"p2""""), body)

      given ActorSystem[?] = target.system
      val rows = Await.result(
        Database().query(
          SqlFragment.raw(
            "SELECT event_payload FROM event_journal WHERE persistence_id = 'shopping-cart|rust-1' ORDER BY seq_nr"
          )
        )(r => String(r.get("event_payload", classOf[Array[Byte]]), "UTF-8")),
        10.seconds
      )
      assertEquals(rows.size, 2)
      // ankka's JournalRecord, holding the Scala cart's manifest and the Scala cart's JSON.
      assert(rows.forall(_.contains("shopping-cart-event")), rows.toString)
      assert(rows.head.contains(s"""{"type":"ItemAdded","item":$pen}"""), rows.head)
    finally target.stop()
  }

  // ── The stateful shape ─────────────────────────────────────────────────────

  private def stateOf(reply: Either[ProcessFailure, Reply]): (Option[String], Reply) =
    val r = reply.fold(f => fail(s"the command failed: $f"), identity)
    r.outcome match
      case RemoteOutcome.Reply(_, metadata) => (metadata.get("state-present"), r)
      case other                            => fail(s"answered $other")

  test(
    "a stateful cart is handed its state once, keeps it, and is handed it again after a trap or a close"
  ) {
    val module   = loaded(spikeGuest)
    val stateful = imports(Map("ANKKA_CONFORMANCE_SHAPE" -> "stateful"))
    val discovered = WasmDiscovery
      .discover(
        GuestInstance.build(module, stateful.values, settings.wasmMaxMemoryPages),
        module,
        BuildInfo.version
      )
      .fold(p => fail(p.mkString), identity)
    assertEquals(discovered.shapeOf(cartId), Shape.Stateful)
    val talk     = WasmConversation(module, settings, stateful, discovered.shapeOf)
    val snapshot = Snapshot(1, json("Cart", s"""{"items":[${item(0)}]}"""))
    val init     = Init(ComponentKind.EventSourcedEntity, cartId, EntityId("s1"), Some(snapshot))
    val session  = talk.open(init)
    def add(id: Long, i: Int) =
      await(session.command(command(id, "add-item", json("Item", item(i)))))

    // Handed the snapshot on the first command, and then nothing: the guest keeps it.
    assertEquals(
      (1 to 3).map(i => stateOf(add(i, i))._1),
      Vector(Some("true"), Some("false"), Some("false"))
    )
    assertEquals(cartOf(session, 4), s"""{"items":[${(0 to 3).map(item).mkString(",")}]}""")

    // A trap replaces the pinned instance; the next command hands the held state to the new one.
    assert(await(session.command(command(5, "panic", json("unit", "")))).isLeft)
    assertEquals(stateOf(add(6, 4))._1, Some("true"))
    assertEquals(cartOf(session, 7), s"""{"items":[${(0 to 4).map(item).mkString(",")}]}""")

    // A close tells the guest to drop it; opened again, the guest is handed the state again.
    session.close()
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while talk.commands.isResident(s"$cartId/s1") && System.nanoTime() < deadline do
      Thread.sleep(20)
    assert(!talk.commands.isResident(s"$cartId/s1"), "the closed cart is still resident")
    val reopened = talk.open(init)
    assertEquals(
      stateOf(await(reopened.command(command(8, "add-item", json("Item", item(9))))))._1,
      Some("true")
    )
  }

  test("a stateless cart is handed its state on every command") {
    val snapshot = Snapshot(1, json("Cart", s"""{"items":[${item(0)}]}"""))
    val session = conversation().open(
      Init(ComponentKind.EventSourcedEntity, cartId, EntityId("s2"), Some(snapshot))
    )
    val presence = (1 to 3).map(i =>
      stateOf(await(session.command(command(i, "add-item", json("Item", item(i))))))._1
    )
    assertEquals(presence, Vector.fill(3)(Some("true")))
  }

  // ── The pools ──────────────────────────────────────────────────────────────

  /** Imports whose `invoke` holds the calling instance for `hold`, as a slow component would. */
  private def slowInvoke(hold: FiniteDuration): ImportValues =
    def function(name: String)(answer: Array[Byte] => Array[Byte]) = new HostFunction(
      "ankka1",
      name,
      FunctionType.of(List(ValType.I32, ValType.I32).asJava, List(ValType.I64).asJava),
      new WasmFunctionHandle:
        def apply(instance: Instance, args: Long*): Array[Long] =
          val request = instance.memory().readBytes(args(0).toInt, args(1).toInt)
          Array(HostImports.give(instance, answer(request)))
    )
    ImportValues
      .builder()
      .addFunction(function("invoke") { r =>
        Thread.sleep(hold.toMillis); r
      })
      .addFunction(function("config")(_ => Array.emptyByteArray))
      .build()

  private val getCart =
    ankka.protocol.v1.wasm
      .HandleRequest(
        kind = Kind.EVENT_SOURCED_ENTITY,
        componentId = "cart",
        entityId = "p1",
        command = ankka.protocol.v1.wasm.HandleRequest.Command.EventSourced(
          ankka.protocol.v1.event_sourced.EventSourcedIn.Command(1, "get-cart")
        )
      )
      .toByteArray

  test("work that waits holds a fresh instance, never one a command needs") {
    val module   = loaded(spikeGuest)
    val values   = slowInvoke(500.millis)
    val build    = () => GuestInstance.build(module, values, settings.wasmMaxMemoryPages)
    val commands = CommandPool(1, build, 5.seconds)
    val blocking = BlockingPool(build)
    (1 to 50).foreach(_ =>
      commands.withAny("ankka1_handle")(_.call("ankka1_handle", getCart))
    ) // warm
    val step = Future(
      blocking.withFresh("ankka1_call_out", 5.seconds)(_.call("ankka1_call_out", Array[Byte](1)))
    )(using
      AnkkaExecutors.virtual
    )
    Thread.sleep(100) // the step is now blocked inside the import
    val timings = (1 to 5).map { _ =>
      val start = System.nanoTime()
      assert(commands.withAny("ankka1_handle")(_.call("ankka1_handle", getCart)).isRight)
      (System.nanoTime() - start) / 1000000
    }
    assert(!step.isCompleted, "the step finished before the commands were measured")
    assert(timings.sorted.apply(2) < 50, s"commands took $timings ms while a step was blocked")
    assert(Await.result(step, 5.seconds).isRight)
  }

  test("one command instance serialises stateless commands, and answers them all") {
    val module = loaded(spikeGuest)
    val values = slowInvoke(200.millis)
    val commands = CommandPool(
      1,
      () => GuestInstance.build(module, values, settings.wasmMaxMemoryPages),
      5.seconds
    )
    val start = System.nanoTime()
    val both =
      (1 to 2).map { i =>
        Future(
          commands.withAny("ankka1_call_out")(_.call("ankka1_call_out", Array[Byte](i.toByte)))
        )(using
          AnkkaExecutors.virtual
        )
      }
    val results = both.map(Await.result(_, 5.seconds))
    val wall    = (System.nanoTime() - start) / 1000000
    assert(results.forall(_.isRight), results.toString)
    assert(wall >= 400, s"two calls through one instance took ${wall}ms; they ran at once")
  }

  // ── Every export, in two languages ─────────────────────────────────────────

  private val spikeGuestGo: Path = Path.of(getClass.getResource("/wasm/spike-guest-go.wasm").toURI)

  for (language, path) <- Seq("rust" -> spikeGuest, "go" -> spikeGuestGo) do
    test(s"$language: every call the host makes of a module is answered") {
      val talk = WasmConversation(loaded(path), settings, imports(), _ => Shape.Stateless)
      val at   = Metadata.empty
      assertEquals(await(talk.handleView(ViewRequest(cartId, None, at, None))), ViewOutcome.Ignore)
      assertEquals(
        await(talk.handleConsumer(ConsumerRequest(cartId, None, at))),
        ConsumerOutcome.Done
      )
      assertEquals(
        await(
          talk.invokeTimedAction(
            TimedActionRequest(cartId, MethodName("tick"), Array.emptyByteArray, at)
          )
        ),
        Right(())
      )
      val plan =
        await(talk.plan(PlanRequest(cartId, "session", MethodName("ask"), json("unit", ""), at)))
      assertEquals(plan.map(_.user), Right(Some("hello")))
      assertEquals(
        await(talk.invokeTool(cartId, "session", "lookup", "{}", Metadata.empty)),
        Right("tool ran")
      )
      assertEquals(
        await(
          talk
            .checkGuardrail(cartId, "session", "polite", GuardrailStage.Input, "hi", Metadata.empty)
        ),
        Right(())
      )
      assertEquals(
        await(talk.checkTaskResult(cartId, "t-1", "answer", "{}", Metadata.empty)),
        TaskResultVerdict.Accept
      )
      val http = await(
        talk.handleHttp(
          HttpForward(
            "api",
            "GET /",
            Vector.empty,
            Vector.empty,
            Vector.empty,
            "",
            Array.emptyByteArray,
            None,
            at
          )
        )
      ).fold(f => fail(f.toString), identity)
      assertEquals((http.status, String(http.body, "UTF-8")), (200, "hello"))
      val workflow = talk.open(Init(ComponentKind.Workflow, cartId, EntityId("w1"), None))
      val step = await(workflow.runStep(7, "first", None, Metadata.empty))
        .fold(f => fail(f.toString), identity)
      assertEquals(
        (step.commandId, step.next),
        (7L, com.thinkmorestupidless.ankka.core.effect.StepOutcome.End)
      )
    }
