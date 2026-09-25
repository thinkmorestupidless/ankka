package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  Observability,
  RecordedSpan,
  SpanOutcome,
  SqlFragment
}
import org.apache.pekko.actor.typed.ActorSystem

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.Await
import scala.jdk.CollectionConverters.*

/**
 * One munit case per behaviour in `contracts/conformance.md`, named after it, driving the service
 * through its declared HTTP routes and reading the journal and the recorder — never a Scala type of
 * the reference — so the same cases hold the Scala reference in-process and any process in another
 * language to one definition of "compatible".
 */
class ConformanceSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 10.minutes

  private val model                     = TestModelProvider()
  private var target: ConformanceTarget = scala.compiletime.uninitialized
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

  override def beforeAll(): Unit =
    target = ConformanceTarget.fromProperty(model)
    println(s"conformance target: ${target.name}")

  override def afterAll(): Unit = if target != null then target.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  // ── HTTP and journal helpers ───────────────────────────────────────────────

  final case class Reply(status: Int, body: String, headers: Map[String, String]):
    def json: Json = Json.parse(body).fold(p => fail(s"not JSON ($p): $body"), identity)

  private def send(
      method: String,
      path: String,
      body: Option[String] = None,
      headers: Seq[(String, String)] = Nil,
      contentType: String = "text/plain"
  ): Reply =
    val b = HttpRequest
      .newBuilder(URI.create(target.baseUrl + path))
      .timeout(java.time.Duration.ofSeconds(30))
    body match
      case Some(text) =>
        b.method(method, HttpRequest.BodyPublishers.ofString(text))
          .header("Content-Type", contentType)
      case None => b.method(method, HttpRequest.BodyPublishers.noBody())
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(
      r.statusCode(),
      r.body(),
      r.headers().map().asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
    )

  private def get(path: String, headers: (String, String)*): Reply =
    send("GET", path, headers = headers)
  private def post(path: String, body: String = "", contentType: String = "text/plain"): Reply =
    send("POST", path, Some(body), contentType = contentType)
  private def postJson(path: String, body: String): Reply = post(path, body, "application/json")
  private def delete(path: String): Reply                 = send("DELETE", path)

  private def record(id: String, input: String): Reply = post(s"/conformance/$id/record", input)
  private def count(id: String): Int = get(s"/conformance/$id/count").body.trim.toInt

  private def eventually[A](timeout: FiniteDuration = 20.seconds)(check: => Option[A]): A =
    val deadline        = System.nanoTime() + timeout.toNanos
    var last: Option[A] = check
    while last.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(100)
      last = check
    last.getOrElse(fail(s"not observed within $timeout"))

  private def journal(persistenceId: String): Vector[(Long, String, String)] =
    given ActorSystem[?] = target.system
    Await.result(
      Database().query(
        SqlFragment.raw(
          s"SELECT seq_nr, event_ser_manifest, event_payload FROM event_journal WHERE persistence_id = '$persistenceId' ORDER BY seq_nr"
        )
      )(r =>
        (
          r.get("seq_nr", classOf[java.lang.Long]).longValue,
          r.get("event_ser_manifest", classOf[String]),
          String(r.get("event_payload", classOf[Array[Byte]]), "UTF-8")
        )
      ),
      10.seconds
    )

  private def snapshotSeq(persistenceId: String): Option[Long] =
    given ActorSystem[?] = target.system
    Await
      .result(
        Database().query(
          SqlFragment.raw(s"SELECT seq_nr FROM snapshot WHERE persistence_id = '$persistenceId'")
        )(r => r.get("seq_nr", classOf[java.lang.Long]).longValue),
        10.seconds
      )
      .headOption

  private def spans(component: String, handler: String): Vector[RecordedSpan] =
    val o = Observability(target.system)
    o.recorder
      .snapshot()
      .filter(s =>
        o.names.nameOf(s.componentRef).contains(component) && o.names
          .nameOf(s.handlerRef)
          .contains(handler)
      )

  private def onlyForProcesses(): Unit = assume(target.isProcess, "process targets only")

  private def cartJson(id: String, name: String, quantity: Int) =
    s"""{"productId":"$id","name":"$name","quantity":$quantity}"""

  // ── Discovery ──────────────────────────────────────────────────────────────

  test("discovery.lists-every-component") {
    assertEquals(target.componentIds, ConformanceReference.ComponentIds)
    val routes = target.endpointRoutes
    Seq(
      "POST /carts/{cartId}/items",
      "GET /carts/awkward",
      "GET /conformance/echo",
      "GET /private/"
    ).foreach { r =>
      assert(
        routes.exists(_.replaceAll("\\{[^}]+\\}", "{}") == r.replaceAll("\\{[^}]+\\}", "{}")),
        s"$r not in $routes"
      )
    }
  }

  test("discovery.read-only-flag") {
    val expected = Set(
      ("shopping-cart", "get-cart"),
      ("conformance", "count"),
      ("profile", "get"),
      ("checkout", "status")
    )
    assert(expected.subsetOf(target.readOnlyHandlers), target.readOnlyHandlers.toString)
    assert(!target.readOnlyHandlers.contains(("conformance", "record")))
  }

  test("discovery.refuses-wrong-major") {
    onlyForProcesses()
    val refused = target.discoverWith("99.0").get
    assert(refused.isLeft, refused)
    assert(refused.left.exists(_.exists(_.contains("99.0"))), refused)
    eventually()(Some(target.problems).filter(_.exists(_.contains("99.0"))))
  }

  // ── Event sourced ──────────────────────────────────────────────────────────

  test("es.persist-and-reply") {
    assertEquals(postJson("/carts/c1/items", cartJson("p1", "Pen", 2)).status, 204)
    // The row is ankka's JournalRecord holding the domain manifest and the JSON bytes.
    val rows = journal("shopping-cart|c1")
    assertEquals(rows.map(_._1), Vector(1L))
    assert(rows.head._3.contains("shopping-cart-event"), rows.head._3)
    assert(
      rows.head._3
        .contains("""{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}"""),
      rows.head._3
    )
  }

  test("es.refusal-persists-nothing") {
    record("r1", "one")
    val before  = journal("conformance|r1")
    val refused = post("/conformance/r1/refuse", "")
    assertEquals(refused.status, 409, refused.body)
    assert(refused.body.contains("refused on purpose"), refused.body)
    assertEquals(journal("conformance|r1"), before)
  }

  test("es.no-reply") {
    assertEquals(post("/conformance/n1/no-reply").status, 204)
    eventually()(Some(count("n1")).filter(_ == 1))
  }

  test("es.recover-after-restart") {
    postJson("/carts/c2/items", cartJson("p1", "Pen", 1))
    postJson("/carts/c2/items", cartJson("p2", "Ink", 1))
    postJson("/carts/c2/items", cartJson("p3", "Pad", 1))
    target.restart()
    val cart = get("/carts/c2").json
    assertEquals(cart("items").flatMap(_.asArray).map(_.size), Some(3))
  }

  test("es.snapshot-on-request") {
    (1 to 4).foreach(i => assertEquals(record("snap1", s"v$i").body, "done"))
    // In-process the snapshot lands on event 3; a process's snapshot describes the state after
    // event 3 and is stored by the sidecar at 3 or at the next event, so 3 or 4.
    val seq = eventually()(snapshotSeq("conformance|snap1").filter(s => s == 3L || s == 4L))
    assert(seq == 3L || seq == 4L)
    target.restart()
    assertEquals(count("snap1"), 4)
  }

  test("es.delete-then-fresh") {
    record("d1", "one")
    assertEquals(post("/conformance/d1/delete").body, "done")
    assertEquals(count("d1"), 0)
    assertEquals(record("d1", "again").body, "done")
    assertEquals(count("d1"), 1)
  }

  test("es.expire") {
    record("x1", "one")
    assertEquals(post("/conformance/x1/expire", "1000").body, "done")
    assertEquals(count("x1"), 2)
    Thread.sleep(1500)
    assertEquals(count("x1"), 0)
  }

  test("es.serial-per-instance") {
    val pool = Executors.newFixedThreadPool(10)
    try
      val futures = (1 to 10).map(i => pool.submit(() => record("serial1", s"m$i").status))
      assertEquals(futures.map(_.get(30, TimeUnit.SECONDS)).toSet, Set(200))
    finally pool.shutdownNow(): Unit
    assertEquals(count("serial1"), 10)
    assertEquals(journal("conformance|serial1").map(_._1), (1L to 10L).toVector)
  }

  test("es.parallel-instances") {
    val pool = Executors.newFixedThreadPool(10)
    try
      val started = System.nanoTime()
      val futures = (1 to 10).map(i => pool.submit(() => record(s"par-$i", "x").status))
      assertEquals(futures.map(_.get(30, TimeUnit.SECONDS)).toSet, Set(200))
      assert((System.nanoTime() - started) < 20.seconds.toNanos)
    finally pool.shutdownNow(): Unit
  }

  test("es.fault-is-failed-not-refused") {
    val before = spans("conformance", "misbehave").size
    val fault  = post("/conformance/f1/misbehave")
    assertEquals(fault.status, 500, fault.body)
    assertEquals(record("f1", "after").body, "done")
    val recorded = eventually()(Some(spans("conformance", "misbehave")).filter(_.sizeIs > before))
    assertEquals(recorded.last.outcome, SpanOutcome.Failed)
  }

  test("es.late-reply-dropped") {
    assume(false, "needs a scriptable double; proven by RemoteEntitySuite")
  }

  test("es.journal-portable") {
    // The bytes in the journal are the shared encoding, so either SDK recovers them — the
    // in-process and process runs of this suite write the same rows for the same requests.
    postJson("/carts/port1/items", cartJson("p1", "Pen", 2))
    assertEquals(post("/carts/port1/checkout").status, 200)
    val rows = journal("shopping-cart|port1")
    // Two domain events under the shared manifest, then the deletion marker.
    assertEquals(rows.size, 3, rows.toString)
    assert(
      rows(0)._3
        .contains("""{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}"""),
      rows(0)._3
    )
    assert(rows(1)._3.contains("""{"type":"CheckedOut"}"""), rows(1)._3)
    assert(rows.take(2).forall(_._3.contains("shopping-cart-event")))
  }

  test("kv.set-get") {
    assertEquals(post("/conformance/profile/p1", "Ada").body, "done")
    assertEquals(get("/conformance/profile/p1").body, "Ada")
    assertEquals(post("/conformance/profile/p1", "").status, 400)
  }

  test("kv.recover-after-restart") {
    post("/conformance/profile/p2", "Bob")
    target.restart()
    assertEquals(get("/conformance/profile/p2").body, "Bob")
  }

  test("kv.delete-then-fresh") {
    post("/conformance/profile/p3", "Cy")
    assertEquals(delete("/conformance/profile/p3").body, "done")
    assertEquals(get("/conformance/profile/p3").body, "none")
  }

  // ── Workflow ───────────────────────────────────────────────────────────────

  private def checkoutStatus(id: String): String = get(s"/conformance/checkout/$id").body

  test("wf.runs-steps-in-order") {
    assertEquals(post("/conformance/checkout/w1", "ok").body, "started")
    eventually()(Some(checkoutStatus("w1")).filter(_ == "charged"))
    val rows = journal("checkout|w1")
    assert(rows.sizeIs >= 3, rows.toString)
    assertEquals(rows.map(_._1), (1L to rows.size.toLong).toVector)
    assertEquals(post("/conformance/checkout/w1", "ok").status, 409)
  }

  test("wf.step-failure-compensates") {
    assertEquals(post("/conformance/checkout/w2", "fail").body, "started")
    eventually()(Some(checkoutStatus("w2")).filter(_ == "compensated"))
  }

  test("wf.step-calls-client") {
    val before = spans("shopping-cart", "total-quantity").size
    post("/conformance/checkout/w3", "ok")
    eventually()(Some(checkoutStatus("w3")).filter(_ == "charged"))
    assert(
      spans("shopping-cart", "total-quantity").sizeIs > before,
      "the reserve step did not call the cart"
    )
  }

  test("wf.survives-restart-mid-step") {
    post("/conformance/checkout/w4", "pause")
    eventually()(Some(checkoutStatus("w4")).filter(_ == "waiting"))
    target.restart()
    eventually(30.seconds)(Some(checkoutStatus("w4")).filter(_ == "charged"))
  }

  // ── View and consumer ──────────────────────────────────────────────────────

  test("view.row-updated") {
    postJson("/carts/v1/items", cartJson("p1", "Pen", 2))
    postJson("/carts/v1/items", cartJson("p1", "Pen", 3))
    val row = eventually()(
      Some(get("/carts/v1/rows"))
        .filter(_.status == 200)
        .map(_.json)
        .filter(_("quantities").flatMap(_("p1")).flatMap(_.asDouble).contains(5.0))
    )
    assertEquals(row("cartId").flatMap(_.asString), Some("v1"))
  }

  test("view.query-by-id") {
    assertEquals(get("/carts/never/rows").status, 404)
    postJson("/carts/v2/items", cartJson("p9", "Nib", 1))
    eventually()(Some(get("/carts/v2/rows")).filter(_.status == 200))
  }

  test("view.row-tombstoned-on-checkout") {
    postJson("/carts/v3/items", cartJson("p1", "Pen", 1))
    eventually()(Some(get("/carts/v3/rows")).filter(_.status == 200))
    assertEquals(post("/carts/v3/checkout").status, 200)
    eventually()(
      Some(get("/carts/v3/rows").json).filter(_("checkedOut").flatMap(_.asBoolean).contains(true))
    )
    assertEquals(get("/carts/v3").json("items").flatMap(_.asArray).map(_.size), Some(0))
  }

  test("consumer.at-least-once-in-order") {
    postJson("/carts/k1/items", cartJson("p1", "Pen", 1))
    assertEquals(post("/carts/k1/checkout").status, 200)
    eventually()(Some(count("k1")).filter(_ >= 1))
    assert(journal("conformance|k1").exists(_._3.contains("checkout")))
  }

  // ── Timed action ───────────────────────────────────────────────────────────

  test("timer.fires") {
    assertEquals(post("/conformance/remind/t1").status, 204)
    eventually(10.seconds)(Some(count("t1")).filter(_ == 1))
    assert(journal("conformance|t1").exists(_._3.contains("reminded")))
  }

  test("timer.fires-after-process-restart") {
    assume(false, "needs a process the suite controls; proven by RemoteProjectionSuite")
  }

  // ── HTTP endpoints ─────────────────────────────────────────────────────────

  test("http.path-params-bind") {
    postJson("/carts/h1/items", cartJson("p1", "Pen", 1))
    assertEquals(get("/carts/h1").json("cartId").flatMap(_.asString), Some("h1"))
  }

  test("http.literal-beats-parameter") {
    assertEquals(get("/carts/awkward").body, "literal")
  }

  test("http.query-and-headers-cross") {
    val echoed = get("/conformance/echo?a=1&a=2&b=x", "X-One" -> "first", "X-Two" -> "second").json
    assertEquals(echoed("a").flatMap(_.asArray).map(_.flatMap(_.asString)), Some(Vector("1", "2")))
    assertEquals(echoed("b").flatMap(_.asString), Some("x"))
    assertEquals(echoed("headers").flatMap(_("x-one")).flatMap(_.asString), Some("first"))
    assertEquals(echoed("headers").flatMap(_("x-two")).flatMap(_.asString), Some("second"))
  }

  test("http.body-decoded-reply-encoded") {
    val r = postJson("/carts/h2/items", cartJson("p1", "Pen", 1))
    assertEquals(r.status, 204)
    val cart = get("/carts/h2")
    assert(
      cart.headers.getOrElse("content-type", "").startsWith("application/json"),
      cart.headers.toString
    )
    assertEquals(
      cart.body,
      """{"cartId":"h2","items":[{"productId":"p1","name":"Pen","quantity":1}],"checkedOut":false}"""
    )
    assertEquals(postJson("/carts/h2/items", """{"productId":"p1"}""").status, 400)
  }

  test("http.status-passthrough") {
    assertEquals(get("/conformance/status/418").status, 418)
  }

  test("http.handler-fault-is-500") {
    // The message stays in the service's log: a fault's details are not the caller's.
    assertEquals(get("/conformance/boom").status, 500)
  }

  test("http.acl-deny-never-reaches-process") {
    val r = get("/private/")
    assert(r.status == 401 || r.status == 503, r.toString)
  }

  test("http.route-acl-overrides-the-endpoint") {
    // `/conformance` admits everyone; this one route does not, and says so without reaching
    // the process. Its siblings under the same prefix are unaffected.
    assertEquals(get("/conformance/closed").status, 403)
    assertEquals(get("/conformance/status/418").status, 418)
  }

  test("http.sse-frames-json-encoded") {
    val r = get("/conformance/stream/s1")
    assertEquals(r.status, 200)
    val frames =
      r.body.linesIterator.filter(_.startsWith("data:")).map(_.drop("data:".length).trim).toVector
    assertEquals(
      frames.map(f => Json.parse(f).toOption.flatMap(_.asString)),
      Vector(Some(" leading space"), Some("two\nlines"), Some("plain"))
    )
  }

  test("http.request-span-parents-entity-span") {
    val before = spans("shopping-cart", "add-item").size
    postJson("/carts/h3/items", cartJson("p1", "Pen", 1))
    val entity =
      eventually()(Some(spans("shopping-cart", "add-item")).filter(_.sizeIs > before)).last
    assert(entity.parentSpanId != 0L, "the entity span has no parent")
    val parent = Observability(target.system).recorder
      .spansOf(entity.traceId)
      .find(_.spanId == entity.parentSpanId)
    assert(parent.isDefined, "the entity span's parent is not in its trace")
  }

  test("http.unready-process-is-503") {
    assume(false, "needs a process the suite controls; proven by SidecarClusterSuite")
  }

  // ── Agent ──────────────────────────────────────────────────────────────────

  test("agent.plans-and-replies") {
    model.expectText("Hello there")
    val r = post("/conformance/ask/a1", "hi")
    assertEquals(r.status, 200, r.body)
    assertEquals(r.body, "Hello there")
    assertEquals(model.lastRequest.systemMessage, Some("You are helpful."))
  }

  test("agent.tool-invoked-in-process") {
    model
      .expectToolCall("lookup", Json.obj("id" -> Json.str("tool1")))
      .expectText("The count is in.")
    assertEquals(post("/conformance/ask/a2", "count?").body, "The count is in.")
    val results = model.lastRequest.messages.collect { case ChatMessage.ToolResults(rs) =>
      rs
    }.flatten
    assertEquals(results.map(r => (r.name, r.isError)), Vector(("lookup", false)))
    assert(results.head.content.contains("count for tool1 is 1"), results.head.content)
    assertEquals(count("tool1"), 1)
  }

  test("agent.tool-error-continues") {
    model.expectToolCall("lookup", Json.obj("id" -> Json.str(""))).expectText("recovered")
    assertEquals(post("/conformance/ask/a3", "bad").body, "recovered")
    val results = model.lastRequest.messages.collect { case ChatMessage.ToolResults(rs) =>
      rs
    }.flatten
    assertEquals(results.map(_.isError), Vector(true))
  }

  test("agent.stream") {
    model.expectText("one two three")
    val r = get("/conformance/stream-ask/a4?q=go")
    assertEquals(r.status, 200, r.body)
    val frames =
      r.body.linesIterator.filter(_.startsWith("data:")).map(_.drop("data:".length).trim).toVector
    assertEquals(
      frames.map(f => Json.parse(f).toOption.flatMap(_.asString)),
      Vector(Some("one"), Some(" two"), Some(" three"))
    )
  }

  test("agent.guardrail-blocks") {
    model.expectText("the key is sk-123")
    val r = post("/conformance/ask/a5", "key?")
    assertEquals(r.status, 403, r.body)
    assert(r.body.contains("no-secrets"), r.body)
  }

  test("agent.session-survives-process-restart") {
    assume(false, "needs a process the suite controls; proven by RemoteAgentSuite")
  }

  // ── Client ─────────────────────────────────────────────────────────────────

  test("client.trace-propagates") {
    // A call made from an endpoint handler is a child of the request's span — across the
    // process boundary for a process target, since the SDK copies the request's metadata.
    // `record-many`: nothing but an endpoint calls it, so the newest span is this request's.
    val before = spans("conformance", "record-many").size
    assertEquals(post("/conformance/trace1/record-many", "1").body, "done")
    val span =
      eventually()(Some(spans("conformance", "record-many")).filter(_.sizeIs > before)).last
    assert(span.parentSpanId != 0L, "the entity span has no parent")
  }

  test("client.error-code-crosses") {
    val r = post("/conformance/e1/refuse")
    assertEquals(r.status, 409)
  }

  // ── SC-003: one request through the endpoint, entity and journal, both hosting modes ──

  test("bench.request-latency") {
    assume(sys.props.contains("ankka.benchmarks"), "-Dankka.benchmarks to measure")
    def measure(label: String)(request: () => Unit): Unit =
      (1 to 100).foreach(_ => request())
      val samples = Array
        .fill(300) {
          val started = System.nanoTime()
          request()
          System.nanoTime() - started
        }
        .sorted
      println(
        f"$label%-40s p50 ${samples(150) / 1000}%6dµs  p99 ${samples(297) / 1000}%6dµs  (${target.name})"
      )
    postJson("/carts/bench/items", cartJson("p1", "Pen", 1))
    measure("GET /carts/{id} (query through the endpoint)")(() =>
      assertEquals(get("/carts/bench").status, 200)
    )
    var n = 0
    measure("POST record (persist through the endpoint)") { () =>
      n += 1
      assertEquals(record("bench", s"m$n").status, 200)
    }
  }

  // ── Observability ──────────────────────────────────────────────────────────

  test("obs.one-span-per-invocation") {
    val before = spans("shopping-cart", "add-item").size
    postJson("/carts/obs1/items", cartJson("p1", "Pen", 1))
    val after = eventually()(Some(spans("shopping-cart", "add-item")).filter(_.sizeIs > before))
    assertEquals(after.size - before, 1)
    val trace = Observability(target.system).recorder.spansOf(after.last.traceId)
    assertEquals(trace.count(_.parentSpanId == 0L), 1, s"request spans in the trace: $trace")
  }
