package com.thinkmorestupidless.ankka.sidecar.wasm

import ankka.protocol.v1.client.{ServiceFailure, ServiceReply, ServiceRequest}
import ankka.protocol.v1.endpoint.{HttpRequest as PbHttpRequest, HttpResponse as PbHttpResponse}
import ankka.protocol.v1.payload as pb
import com.dylibso.chicory.runtime.{HostFunction, ImportValues, Instance, WasmFunctionHandle}
import com.dylibso.chicory.wabt.Wat2Wasm
import com.dylibso.chicory.wasm.types.{FunctionType, ValType}
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.{
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
import com.thinkmorestupidless.ankka.sidecar.{ServiceCalls, Settings}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.file.Files
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/**
 * The three imports a module may ask the runtime for since protocol 1.10 — `request`, `now` and
 * `random` — and the rule about where `request` may be called, held with guests written here by
 * hand. They link no guest library, which is the point: what the runtime refuses, it refuses
 * whatever the module was built with. The client is a stand-in that records what it is asked, so
 * nothing here starts a service, and a claim that nothing was sent is read from its record.
 *
 * A case named for a scenario of `features/wasm/` holds that scenario at the host.
 */
class WasmImportsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 1.minute

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  // ── Guests ─────────────────────────────────────────────────────────────────

  /** Every function the host calls with a request and reads a reply from. */
  private val callable: Vector[String] =
    (ModuleLoader.Exports - "ankka1_alloc" - "ankka1_free").toVector.sorted

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"\\${b & 0xff}%02x").mkString

  /**
   * A module of the ABI's shape: memory, an allocator that only ever moves forward, and every
   * callable function with the body `instructions`, which has the request's pointer and length as
   * its two parameters and leaves the packed reply.
   */
  private def guest(imports: String, instructions: String, extra: String = ""): LoadedModule =
    val functions = callable
      .map(name => s"""(func (export "$name") (param i32 i32) (result i64) $instructions)""")
      .mkString("\n  ")
    val text =
      s"""(module
         |  $imports
         |  (memory (export "memory") 2)
         |  (global $$next (mut i32) (i32.const 8192))
         |  (func (export "ankka1_alloc") (param $$len i32) (result i32)
         |    (local $$ptr i32)
         |    (local.set $$ptr (global.get $$next))
         |    (global.set $$next (i32.add (global.get $$next) (local.get $$len)))
         |    (local.get $$ptr))
         |  (func (export "ankka1_free") (param i32 i32))
         |  $extra
         |  $functions)""".stripMargin
    val file = Files.createTempFile("wasm-imports-suite", ".wasm")
    Files.write(file, Wat2Wasm.parse(text))
    file.toFile.deleteOnExit()
    ModuleLoader.load(file).fold(problems => fail(problems.mkString("; ")), identity)

  private val requestImport =
    """(import "ankka1" "request" (func $request (param i32 i32) (result i64)))"""

  /** Every function calls `request` with `asked`, and answers what the import answered. */
  private def forwarding(asked: ServiceRequest, times: Int = 1): LoadedModule =
    val bytes = asked.toByteArray
    val once  = s"(call $$request (i32.const 16) (i32.const ${bytes.length}))"
    guest(
      requestImport,
      Vector.fill(times - 1)(s"(drop $once)").mkString(" ") + " " + once,
      s"""(data (i32.const 16) "${hex(bytes)}")"""
    )

  private val toWallet = ServiceRequest(
    service = "wallet",
    method = "POST",
    path = "/internal/credits",
    contentType = Some("application/json"),
    body = Some(ByteString.copyFromUtf8("""{"amount":5}"""))
  )

  // ── The stand-in for the client ────────────────────────────────────────────

  /** What a `request` reached: the request, and the call site it was made from. */
  private final case class Asked(request: ServiceRequest, site: Option[CallSite])

  private final class RecordingCalls(answer: ServiceRequest => Future[ServiceReply])
      extends ServiceCalls:
    private val received     = ConcurrentLinkedQueue[Asked]()
    def asked: Vector[Asked] = received.asScala.toVector
    def request(request: ServiceRequest): Future[ServiceReply] =
      // Called on the thread that called the import, so the call site is this thread's.
      received.add(Asked(request, CallSite.current)): Unit
      answer(request)

  private def response(
      status: Int,
      body: String,
      contentType: String = "text/plain",
      headers: Seq[(String, String)] = Nil
  ): ServiceReply =
    ServiceReply(
      ServiceReply.Result.Response(
        PbHttpResponse(
          status,
          contentType,
          ByteString.copyFromUtf8(body),
          headers.map((name, value) => PbHttpRequest.Pair(name, value))
        )
      )
    )

  private def answering(reply: ServiceReply): RecordingCalls =
    RecordingCalls(_ => Future.successful(reply))

  private def imports(
      calls: Option[ServiceCalls],
      serviceClientTimeout: FiniteDuration = 5.seconds,
      clock: () => Long = () => System.currentTimeMillis()
  ): HostImports =
    val host = HostImports(5.seconds, 5.seconds, _ => None, serviceClientTimeout, clock)
    calls.foreach(host.bindCalls)
    host

  private def instance(module: LoadedModule, host: HostImports): GuestInstance =
    GuestInstance.build(module, host.values, settings.wasmMaxMemoryPages)

  private val teller = Purpose("teller", Some("credit"), Metadata.empty)

  private def replyOf(answered: Either[GuestFault, Array[Byte]]): ServiceReply =
    ServiceReply.parseFrom(answered.fold(fault => fail(fault.toString), identity))

  // ── The call site ──────────────────────────────────────────────────────────

  /** Imports whose `log` records what the calling thread, and another thread, see. */
  private final class Probe:
    val seen                            = ConcurrentLinkedQueue[Option[CallSite]]()
    val elsewhere                       = ConcurrentLinkedQueue[Option[CallSite]]()
    def last: Option[CallSite]          = seen.asScala.lastOption.flatten
    def lastElsewhere: Option[CallSite] = elsewhere.asScala.lastOption.flatten
    val values: ImportValues = ImportValues
      .builder()
      .addFunction(
        new HostFunction(
          "ankka1",
          "log",
          FunctionType.of(List(ValType.I32, ValType.I32, ValType.I32).asJava, List.empty.asJava),
          new WasmFunctionHandle:
            def apply(instance: Instance, args: Long*): Array[Long] =
              seen.add(CallSite.current): Unit
              val other = Future(CallSite.current)(using AnkkaExecutors.virtual)
              elsewhere.add(Await.result(other, 5.seconds)): Unit
              Array.emptyLongArray
        )
      )
      .build()

  private val logImport = """(import "ankka1" "log" (func $log (param i32 i32 i32)))"""

  private val probing =
    guest(logImport, "(call $log (i32.const 2) (i32.const 0) (i32.const 0)) (i64.const 0)")

  test("an import reads the call it was made from, on the calling thread and on no other") {
    val probe = Probe()
    val made  = GuestInstance.build(probing, probe.values, settings.wasmMaxMemoryPages)
    assertEquals(CallSite.current, None)
    assert(made.call("ankka1_consumer", Array.emptyByteArray, teller).isRight)
    val site = probe.last.getOrElse(fail("the import saw no call site"))
    assertEquals((site.function, site.purpose), ("ankka1_consumer", teller))
    assert(site.instance eq made)
    assertEquals(probe.lastElsewhere, None, "another thread saw this thread's call site")
    assertEquals(CallSite.current, None, "the call site outlived its call")
  }

  test("a call that traps leaves no call site behind") {
    val trapping = guest("", "unreachable")
    val made     = GuestInstance.build(trapping, ImportValues.builder().build(), 16)
    assert(made.call("ankka1_handle", Array.emptyByteArray, teller).isLeft)
    assertEquals(CallSite.current, None)
  }

  test("every function a call to another service is permitted from is one the host calls") {
    assert(CallSite.Permitted.subsetOf(ModuleLoader.Exports), CallSite.Permitted.toString)
  }

  // ── Where `request` may be called ──────────────────────────────────────────

  private val refused: Vector[String] = callable.filterNot(CallSite.Permitted.contains)

  test(
    "every function of the ABI is one a call to another service is permitted from or refused in"
  ) {
    assertEquals((CallSite.Permitted ++ refused).toSet, callable.toSet)
    assert(refused.contains("ankka1_handle") && refused.contains("ankka1_fold"), refused.toString)
    assert(refused.contains("ankka1_view"), refused.toString)
  }

  test("every function that is not permitted traps before anything is sent") {
    refused.foreach { function =>
      val calls    = answering(response(200, "ok"))
      val made     = instance(forwarding(toWallet), imports(Some(calls)))
      val answered = made.call(function, Array.emptyByteArray, teller)
      val fault    = answered.left.getOrElse(fail(s"$function was allowed to call another service"))
      assert(fault.message.startsWith("request may not be called"), s"$function: ${fault.message}")
      assert(fault.message.contains("a module calls another service from"), fault.message)
      assert(made.broken, s"$function: the instance was kept")
      assertEquals(calls.asked, Vector.empty, s"$function: a request was made")
    }
  }

  test("a module calls another service from every handler that may wait") {
    CallSite.Permitted.toVector.sorted.foreach { function =>
      val calls = answering(response(200, "credited"))
      val made  = instance(forwarding(toWallet), imports(Some(calls)))
      val reply = replyOf(made.call(function, Array.emptyByteArray, teller))
      assertEquals(reply, response(200, "credited"), function)
      assertEquals(calls.asked.map(_.request.path), Vector("/internal/credits"), function)
      assert(!made.broken, s"$function: the instance was discarded")
    }
  }

  test("the platform stops a command's call whatever the module was built with") {
    // The guest is this file's text: it links nothing that could have refused first.
    val calls = answering(response(200, "ok"))
    val made  = instance(forwarding(toWallet), imports(Some(calls)))
    val fault = made.call("ankka1_handle", Array.emptyByteArray, teller).left.toOption.get
    assert(
      fault.message.startsWith("request may not be called from the command teller/credit"),
      fault.message
    )
    assertEquals(calls.asked, Vector.empty)
  }

  test("each refusal says what was running: an event being read, a view, or the function") {
    def refusal(function: String): String =
      instance(forwarding(toWallet), imports(Some(answering(response(200, "ok")))))
        .call(function, Array.emptyByteArray, teller)
        .left
        .toOption
        .get
        .message
    assert(refusal("ankka1_fold").contains("while teller reads an event"), refusal("ankka1_fold"))
    assert(refusal("ankka1_view").contains("from the view teller"), refusal("ankka1_view"))
    assert(refusal("ankka1_close").contains("from ankka1_close"), refusal("ankka1_close"))
  }

  test("a call to another service made outside any call into the module is refused") {
    val calls = answering(response(200, "ok"))
    val made  = instance(forwarding(toWallet), imports(Some(calls)))
    // Chicory's own call, with no `GuestInstance.call` around it to say what it is for.
    val thrown = intercept[Throwable](made.instance.`export`("ankka1_consumer").apply(0L, 0L))
    val causes = Iterator.iterate(thrown)(_.getCause).takeWhile(_ != null).toVector
    assert(causes.exists(_.isInstanceOf[ImportRefused]), causes.toString)
    assertEquals(calls.asked, Vector.empty)
  }

  test("a call the runtime has abandoned makes no further request") {
    // The first answer arrives after the runtime gave up on the call, which is what marks an
    // instance broken; the guest then asks again.
    lazy val calls: RecordingCalls = RecordingCalls { _ =>
      CallSite.current.foreach(_.instance.markBroken(GuestFault("ankka1_consumer", "abandoned")))
      Future.successful(response(200, "late"))
    }
    val made  = instance(forwarding(toWallet, times = 2), imports(Some(calls)))
    val fault = made.call("ankka1_consumer", Array.emptyByteArray, teller).left.toOption.get
    assert(fault.message.contains("a call the runtime has abandoned"), fault.message)
    assertEquals(calls.asked.size, 1, "the abandoned call asked again")
  }

  // ── Through the conversation: what each call is for ────────────────────────

  private val tellerId = ComponentId("teller")
  private val unit     = Payload(Payload.Json, "unit", Array.emptyByteArray)

  private def conversation(module: LoadedModule, host: HostImports): WasmConversation =
    WasmConversation(module, settings, host, _ => Shape.Stateless)

  private def command(name: String, metadata: Metadata = Metadata.empty) =
    Command(1, MethodName(name), unit, metadata, snapshotRequested = false)

  /** Every way the conversation calls a module with metadata, by the function it calls. */
  private def entries(
      talk: WasmConversation,
      metadata: Metadata
  ): Vector[(String, Option[String], () => Future[Any])] =
    def session(kind: ComponentKind) = talk.open(Init(kind, tellerId, EntityId("e1"), None))
    Vector(
      (
        "ankka1_handle",
        Some("credit"),
        () => session(ComponentKind.EventSourcedEntity).command(command("credit", metadata))
      ),
      (
        "ankka1_run_step",
        Some("pay-out"),
        () => session(ComponentKind.Workflow).runStep(1, "pay-out", None, metadata)
      ),
      (
        "ankka1_view",
        None,
        () => talk.handleView(ViewRequest(tellerId, Some(unit), metadata, None))
      ),
      (
        "ankka1_consumer",
        None,
        () => talk.handleConsumer(ConsumerRequest(tellerId, Some(unit), metadata))
      ),
      (
        "ankka1_timed_action",
        Some("sweep"),
        () =>
          talk.invokeTimedAction(
            TimedActionRequest(tellerId, MethodName("sweep"), Array.emptyByteArray, metadata)
          )
      ),
      (
        "ankka1_plan",
        Some("ask"),
        () => talk.plan(PlanRequest(tellerId, "s1", MethodName("ask"), unit, metadata))
      ),
      (
        "ankka1_invoke_tool",
        Some("balance"),
        () => talk.invokeTool(tellerId, "s1", "balance", "{}", metadata)
      ),
      (
        "ankka1_check_guardrail",
        Some("polite"),
        () => talk.checkGuardrail(tellerId, "s1", "polite", GuardrailStage.Input, "hi", metadata)
      ),
      (
        "ankka1_check_task_result",
        Some("answer"),
        () => talk.checkTaskResult(tellerId, "t1", "answer", "{}", metadata)
      ),
      (
        "ankka1_http",
        Some("POST /pay"),
        () =>
          talk.handleHttp(
            HttpForward(
              "teller",
              "POST /pay",
              Vector.empty,
              Vector.empty,
              Vector.empty,
              "",
              Array.emptyByteArray,
              None,
              metadata
            )
          )
      )
    )

  /**
   * Whatever a call through the conversation came to, as text: an answer, a failure, an exception.
   */
  private def outcome(call: () => Future[Any]): String =
    Try(Await.result(call(), 10.seconds)) match
      case Success(answered) => answered.toString
      case Failure(e)        => Option(e.getMessage).getOrElse(e.toString)

  test("every call the conversation makes into a module says what it is for") {
    val traced = Metadata.empty.set("ankka-trace-id", "t-1").set("ankka-caller", "teller#credit")
    entries(conversation(forwarding(toWallet), imports(None)), traced)
      .filter((function, _, _) => CallSite.Permitted.contains(function))
      .foreach { (function, handler, call) =>
        // A reply with no case set is no bytes, which every reply type reads as its default.
        val calls = answering(ServiceReply())
        val talk  = conversation(forwarding(toWallet), imports(Some(calls)))
        outcome(entries(talk, traced).find(_._1 == function).get._3): Unit
        val site = calls.asked.headOption
          .flatMap(_.site)
          .getOrElse(fail(s"$function: no request reached the client, after ${outcome(call)}"))
        assertEquals((site.function, site.purpose.handler), (function, handler))
        assertEquals(site.purpose.componentId, "teller", function)
        assertEquals(site.purpose.metadata.get("ankka-trace-id"), Some("t-1"), function)
      }
  }

  private def refusedThroughConversation(kind: ComponentKind): Unit =
    val calls = answering(response(200, "ok"))
    val talk  = conversation(forwarding(toWallet), imports(Some(calls)))
    val failed = Await.result(
      talk.open(Init(kind, tellerId, EntityId("e1"), None)).command(command("credit")),
      10.seconds
    )
    val error = failed.left.getOrElse(fail(s"the command was answered: $failed")).error
    assert(
      error.message.contains("request may not be called from the command teller/credit"),
      error.message
    )
    assertEquals(error.code, ErrorCode.Internal)
    assertEquals(calls.asked, Vector.empty)

  test(
    "a command in a module that calls another service fails before anything is sent: an event sourced entity"
  )(refusedThroughConversation(ComponentKind.EventSourcedEntity))

  test(
    "a command in a module that calls another service fails before anything is sent: a key value entity"
  )(refusedThroughConversation(ComponentKind.KeyValueEntity))

  test(
    "a command in a module that calls another service fails before anything is sent: a workflow"
  )(refusedThroughConversation(ComponentKind.Workflow))

  test(
    "an event sourced entity in a module that calls another service while reading its events fails"
  ) {
    val calls   = answering(response(200, "ok"))
    val talk    = conversation(forwarding(toWallet), imports(Some(calls)))
    val session = talk.open(Init(ComponentKind.EventSourcedEntity, tellerId, EntityId("e1"), None))
    session.event(1, unit)
    // Twice: the event that could not be read is still to be read by the next command.
    (1 to 2).foreach { _ =>
      val text = Await.result(session.command(command("credit")), 10.seconds).toString
      assert(text.contains("request may not be called while teller reads an event"), text)
    }
    assertEquals(calls.asked, Vector.empty)
  }

  test("a view in a module that calls another service fails the event it was reading") {
    val calls = answering(response(200, "ok"))
    val talk  = conversation(forwarding(toWallet), imports(Some(calls)))
    val text =
      outcome(() => talk.handleView(ViewRequest(tellerId, Some(unit), Metadata.empty, None)))
    assert(text.contains("request may not be called from the view teller"), text)
    assertEquals(calls.asked, Vector.empty)
  }

  // ── What a permitted call carries ──────────────────────────────────────────

  test("the answer of the service called reaches a module's handler as the service made it") {
    val made  = response(201, """{"ok":true}""", "application/json", Seq("X-Answer" -> "yes"))
    val calls = answering(made)
    val reply = replyOf(
      instance(forwarding(toWallet), imports(Some(calls)))
        .call("ankka1_consumer", Array.emptyByteArray, teller)
    )
    assertEquals(reply, made)
    val asked = calls.asked.head.request
    assertEquals((asked.service, asked.method, asked.path), ("wallet", "POST", "/internal/credits"))
    assertEquals(asked.body.map(_.toStringUtf8), Some("""{"amount":5}"""))
    assertEquals(asked.contentType, Some("application/json"))
  }

  test("a refusal by the service called reaches a module's handler as that refusal") {
    val made = instance(forwarding(toWallet), imports(Some(answering(response(403, "no")))))
    assertEquals(
      replyOf(made.call("ankka1_consumer", Array.emptyByteArray, teller)),
      response(403, "no")
    )
    assert(!made.broken, "a refusal by the service called discarded the instance")
  }

  test(
    "a module's call to a service that cannot be found fails, naming the service, and is not sent"
  ) {
    // Whether anything was sent is the client's to say, and it says it in the reply: this holds
    // that the reply reaches the guest as it was made, and costs the guest nothing.
    val unresolvable = ServiceReply(
      ServiceReply.Result.Failure(
        ServiceFailure(ServiceFailure.Reason.UNRESOLVABLE, "no service 'ledger' was found")
      )
    )
    val made = instance(
      forwarding(toWallet.copy(service = "ledger")),
      imports(Some(answering(unresolvable)))
    )
    assertEquals(replyOf(made.call("ankka1_consumer", Array.emptyByteArray, teller)), unresolvable)
    assert(!made.broken)
  }

  test("the call's metadata is what the host sent the handler, not what the guest wrote") {
    val forged = toWallet.copy(metadata =
      Some(pb.Metadata(Seq(pb.Metadata.Entry("ankka-caller", "wallet#someone-else"))))
    )
    val sent = Metadata.empty
      .set("ankka-caller", "teller#pay-out")
      .set("ankka-trace-id", "t-1")
      .set("ankka-span-id", "s-1")
    val calls = answering(response(200, "ok"))
    instance(forwarding(forged), imports(Some(calls)))
      .call("ankka1_run_step", Array.emptyByteArray, Purpose("teller", Some("pay-out"), sent)): Unit
    val carried = calls.asked.head.request.getMetadata.entries.map(e => e.key -> e.value).toMap
    assertEquals(
      carried,
      Map("ankka-caller" -> "teller#pay-out", "ankka-trace-id" -> "t-1", "ankka-span-id" -> "s-1")
    )
  }

  test("before the service has started, a call to another service is answered unavailable") {
    val reply = replyOf(
      instance(forwarding(toWallet), imports(None))
        .call("ankka1_consumer", Array.emptyByteArray, teller)
    )
    assertEquals(reply.result.error.map(_.code), Some(pb.ErrorCode.UNAVAILABLE))
  }

  test("a client that never answers ends the call, naming the import and the service") {
    val never = RecordingCalls(_ => Promise[ServiceReply]().future)
    // The import waits six seconds longer than the client is given, so this case takes that.
    val made =
      instance(forwarding(toWallet), imports(Some(never), serviceClientTimeout = Duration.Zero))
    val fault = made.call("ankka1_consumer", Array.emptyByteArray, teller).left.toOption.get
    assert(fault.message.contains("request to wallet was given no answer"), fault.message)
  }

  test(
    "a module's handler that waits for another service longer than the platform waits for the handler fails"
  ) {
    val slow = RecordingCalls { _ =>
      Future {
        Thread.sleep(1500)
        response(200, "late")
      }(using AnkkaExecutors.virtual)
    }
    val module   = forwarding(toWallet, times = 2)
    val host     = imports(Some(slow))
    val blocking = BlockingPool(() => instance(module, host))
    val answered = blocking.withFresh("ankka1_consumer", 300.millis)(
      _.call("ankka1_consumer", Array.emptyByteArray, teller)
    )
    val fault = answered.left.getOrElse(fail("the handler was waited for"))
    assert(fault.message.contains("no reply from the module within"), fault.message)
    // The abandoned call is still waiting on its first request; when that is answered it asks
    // again, and is refused. Two seconds is past the answer.
    Thread.sleep(2000)
    assertEquals(slow.asked.size, 1, "the abandoned handler made its second request")
  }

  // ── The time and random bytes ──────────────────────────────────────────────

  private val nowImport = """(import "ankka1" "now" (func $now (result i64)))"""

  /** Every function answers the eight bytes of `now`. */
  private val telling = guest(
    nowImport,
    s"(i64.store (i32.const 8) (call $$now)) (i64.const ${Abi.pack(8, 8)})"
  )

  private def timeOf(answered: Either[GuestFault, Array[Byte]]): Long =
    val bytes = answered.fold(fault => fail(fault.toString), identity)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong

  test("a module reads the time from every handler") {
    // A value no machine's clock has, so what the guest read is what the runtime was given.
    val fixed = 12345678901L
    val host  = imports(None, clock = () => fixed)
    callable.foreach { function =>
      assertEquals(
        timeOf(instance(telling, host).call(function, Array.emptyByteArray, teller)),
        fixed,
        function
      )
    }
  }

  test("the time a module's step is told is the platform's, read while the step runs") {
    val host   = imports(None)
    val before = System.currentTimeMillis()
    val told = timeOf(instance(telling, host).call("ankka1_run_step", Array.emptyByteArray, teller))
    val after = System.currentTimeMillis()
    assert(before <= told && told <= after, s"$told is not between $before and $after")
  }

  private val randomImport = """(import "ankka1" "random" (func $random (param i32 i32)))"""

  private def filling(ptr: Int, len: Int): LoadedModule =
    guest(randomImport, s"(call $$random (i32.const $ptr) (i32.const $len)) (i64.const 0)")

  test("a module is given random bytes that differ each time it asks") {
    // Two buffers that start as different constants, so one left alone would show.
    val constants = Array.fill[Byte](16)(0xaa.toByte) ++ Array.fill[Byte](16)(0xbb.toByte)
    val twice = guest(
      randomImport,
      "(call $random (i32.const 100) (i32.const 16)) (call $random (i32.const 116) (i32.const 16)) " +
        s"(i64.const ${Abi.pack(100, 32)})",
      s"""(data (i32.const 100) "${hex(constants)}")"""
    )
    val bytes = instance(twice, imports(None))
      .call("ankka1_handle", Array.emptyByteArray, teller)
      .fold(fault => fail(fault.toString), identity)
    val (first, second) = bytes.splitAt(16)
    assertEquals(bytes.length, 32)
    assert(!first.sameElements(constants.take(16)), "the first buffer was not filled")
    assert(!second.sameElements(constants.drop(16)), "the second buffer was not filled")
    assert(!first.sameElements(second), "the two fills are the same bytes")
  }

  test("random refuses a length it does not fill and a buffer that is not the module's") {
    def refusal(ptr: Int, len: Int): String =
      instance(filling(ptr, len), imports(None))
        .call("ankka1_handle", Array.emptyByteArray, teller)
        .left
        .getOrElse(fail(s"random($ptr, $len) was answered"))
        .message
    assert(refusal(100, HostImports.MaxRandomBytes + 1).contains("random fills at most"))
    assert(refusal(100, -1).contains("random fills at most"))
    // Two pages of memory: sixteen bytes that start eight before its end.
    assert(refusal(2 * 65536 - 8, 16).contains("not in the module's memory"))
    val whole = instance(filling(100, HostImports.MaxRandomBytes), imports(None))
    assert(whole.call("ankka1_handle", Array.emptyByteArray, teller).isRight)
    val none = instance(filling(100, 0), imports(None))
    assert(none.call("ankka1_handle", Array.emptyByteArray, teller).isRight)
  }

  test("a command may read the time and ask for random bytes: neither waits") {
    val made = instance(filling(100, 16), imports(None))
    assert(made.call("ankka1_handle", Array.emptyByteArray, teller).isRight)
    assert(!made.broken)
  }

  test(
    "a module the platform cannot load is refused, saying why: it asks for something not offered"
  ) {
    val file = Files.createTempFile("wasm-imports-suite", ".wasm")
    Files.write(
      file,
      Wat2Wasm.parse(
        """(module
          |  (import "ankka1" "teleport" (func (param i32 i32) (result i64)))
          |  (memory (export "memory") 1)
          |  (func (export "ankka1_alloc") (param i32) (result i32) i32.const 0)
          |  (func (export "ankka1_free") (param i32 i32))
          |  (func (export "ankka1_discover") (param i32 i32) (result i64) i64.const 0))""".stripMargin
      )
    )
    file.toFile.deleteOnExit()
    val problems = ModuleLoader.load(file).left.getOrElse(fail("the module was loaded"))
    assert(problems.exists(_.contains("teleport")), problems.toString)
  }

  // ── A module built before a module could ask for the time ──────────────────

  /**
   * Every function is `unreachable` unless its request holds the bytes of `needle`: a module that
   * reads an entry of the metadata it is sent, and fails without it, as one built before the `now`
   * import does for `ankka.now`.
   */
  private def needing(needle: String): LoadedModule =
    val bytes = needle.getBytes("UTF-8")
    guest(
      "",
      "(if (i32.eqz (call $has (local.get 0) (local.get 1))) (then unreachable)) (i64.const 0)",
      s"""(data (i32.const 16) "${hex(bytes)}")
         |  (func $$has (param $$ptr i32) (param $$len i32) (result i32)
         |    (local $$i i32) (local $$j i32)
         |    (block $$absent
         |      (loop $$next
         |        (br_if $$absent
         |          (i32.gt_u (i32.add (local.get $$i) (i32.const ${bytes.length})) (local.get $$len)))
         |        (local.set $$j (i32.const 0))
         |        (block $$differs
         |          (loop $$same
         |            (br_if $$differs
         |              (i32.ne
         |                (i32.load8_u
         |                  (i32.add (i32.add (local.get $$ptr) (local.get $$i)) (local.get $$j)))
         |                (i32.load8_u (i32.add (i32.const 16) (local.get $$j)))))
         |            (local.set $$j (i32.add (local.get $$j) (i32.const 1)))
         |            (br_if $$same (i32.lt_u (local.get $$j) (i32.const ${bytes.length})))
         |            (return (i32.const 1))))
         |        (local.set $$i (i32.add (local.get $$i) (i32.const 1)))
         |        (br $$next)))
         |    (i32.const 0))""".stripMargin
    )

  private val moduleFailed = "the module failed in"

  test("a module built before a module could ask for the time still reads the time") {
    val talk = conversation(needing("ankka.now"), imports(None))
    entries(talk, Metadata.empty).foreach { (function, _, call) =>
      val came = outcome(call)
      assert(!came.contains(moduleFailed), s"$function was sent no ankka.now: $came")
    }
  }

  test("the guest that needs an entry fails every call that does not carry it") {
    // What makes the case before this one mean something: the same guest, needing an entry no
    // call carries, fails every one of the same calls.
    val talk = conversation(needing("ankka.never"), imports(None))
    entries(talk, Metadata.empty).foreach { (function, _, call) =>
      val came = outcome(call)
      assert(came.contains(moduleFailed), s"$function did not run the guest's check: $came")
    }
  }
