package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.testkit.TestSocket
import com.thinkmorestupidless.ankka.core.secrets.DerivedIds
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.http.{Caller, LocalCallers}
import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, PropertyValue}
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
class ConformanceSuite extends munit.FunSuite with LogCapturing:

  override def munitTimeout: scala.concurrent.duration.Duration = 10.minutes

  private val model                     = TestModelProvider()
  private var target: ConformanceTarget = scala.compiletime.uninitialized
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

  override def beforeAll(): Unit =
    target = ConformanceTarget.fromProperty(model)
    println(
      s"conformance target: ${target.name}, secrets on " +
        ConformanceTarget.secretManager.fold("postgres")(_ => "secret-manager") +
        ", storage on " + ConformanceTarget.garage.fold("memory")(_ => "garage")
    )

  override def afterAll(): Unit =
    if target != null then target.stop()
    ConformanceTarget.secretManager.foreach(_.stop())
    ConformanceTarget.garage.foreach(_.stop())

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

  /** A module answers every call whole: its handlers and routes cannot stream (WASM-ABI.md). */
  private def onlyWhereStreaming(): Unit =
    assume(!target.isModule, "a module cannot stream; refused at its discovery")

  private def cartJson(id: String, name: String, quantity: Int) =
    s"""{"productId":"$id","name":"$name","quantity":$quantity}"""

  // ── Discovery ──────────────────────────────────────────────────────────────

  test("discovery.lists-every-component") {
    // A module declares no agent that waits for a person: the crate cannot declare one yet.
    val expected =
      if target.isModule then ConformanceReference.ComponentIds - "approver"
      else ConformanceReference.ComponentIds
    assertEquals(target.componentIds, expected)
    val routes = target.endpointRoutes
    Seq(
      "POST /carts/{cartId}/items",
      "GET /carts/awkward",
      "GET /conformance/echo",
      "GET /private/",
      "GET /callers/whoami",
      "GET /callers/granted",
      "POST /autonomous/tasks/{type}",
      "GET /autonomous/tasks/{id}",
      "MCP /mcp get_cart",
      "MCP /mcp add_item",
      "MCP /mcp checkout"
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
      ("checkout", "status"),
      ("replicated-vehicle", "get")
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
    val kept = journal("shopping-cart|port1")
    // A checked-out cart is kept: two domain events under the shared manifest, and no deletion.
    assertEquals(kept.size, 2, kept.toString)
    assert(
      kept(0)._3
        .contains("""{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}"""),
      kept(0)._3
    )
    assert(kept(1)._3.contains("""{"type":"CheckedOut"}"""), kept(1)._3)
    assert(kept.forall(_._3.contains("shopping-cart-event")))

    postJson("/carts/port2/items", cartJson("p1", "Pen", 2))
    assertEquals(send("DELETE", "/carts/port2").status, 204)
    val discarded = journal("shopping-cart|port2")
    // Two domain events under the shared manifest, then the deletion marker.
    assertEquals(discarded.size, 3, discarded.toString)
    assert(discarded(1)._3.contains("""{"type":"Discarded"}"""), discarded(1)._3)
    assert(discarded.take(2).forall(_._3.contains("shopping-cart-event")))
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

  test("wf.start-and-await") {
    val statusBefore = spans("checkout", "status").size
    val answer       = post("/conformance/checkout-await/w5", "ok")
    assertEquals(answer.status, 200, answer.body)
    assertEquals(answer.body, "charged")
    // One request, and nobody asked the workflow how it was getting on.
    assertEquals(spans("checkout", "status").size, statusBefore)
    assertEquals(checkoutStatus("w5"), "charged")
  }

  test("wf.await-failed") {
    val answer = post("/conformance/checkout-await/w6", "doomed")
    assertEquals(answer.status, 424, answer.body)
    // The step, and a reason: a process says its own words, a module's trap says it trapped. The
    // message is the body as a process answers it, and the problem's `error` as the JVM does.
    val reason = "compensate: ([^\"]+)".r.findFirstMatchIn(answer.body).map(_.group(1).trim)
    assert(reason.exists(_.nonEmpty), answer.body)
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

  test("view.row-marked-on-checkout") {
    postJson("/carts/v3/items", cartJson("p1", "Pen", 1))
    eventually()(Some(get("/carts/v3/rows")).filter(_.status == 200))
    assertEquals(post("/carts/v3/checkout").status, 200)
    eventually()(
      Some(get("/carts/v3/rows").json).filter(_("checkedOut").flatMap(_.asBoolean).contains(true))
    )
    // The checked-out cart is kept, holding what it held.
    assertEquals(get("/carts/v3").json("items").flatMap(_.asArray).map(_.size), Some(1))
  }

  test("view.row-removed-with-source") {
    postJson("/carts/v4/items", cartJson("p1", "Pen", 1))
    eventually()(Some(get("/carts/v4/rows")).filter(_.status == 200))
    assertEquals(send("DELETE", "/carts/v4").status, 204)
    // The source's deletion removes the row; retried on the row going, as the view follows.
    eventually()(Some(get("/carts/v4/rows")).filter(_.status == 404))
    assertEquals(get("/carts/v4").json("items").flatMap(_.asArray).map(_.size), Some(0))
  }

  // ── Declared and recursive queries (features/views/languages.feature) ──────

  test("a recursive query answers with the same rows in every language") {
    // Under-a, three deep, beside an unrelated root: only a's tree comes back, in key order.
    assertEquals(post("/tree/q-a").status, 200)
    assertEquals(post("/tree/q-b/under/q-a").status, 200)
    assertEquals(post("/tree/q-c/under/q-b").status, 200)
    assertEquals(post("/tree/q-z").status, 200)
    assertEquals(post("/tree/q-y/under/q-z").status, 200)
    val below = eventually()(
      Some(get("/tree/q-a/below"))
        .filter(_.status == 200)
        .flatMap(_.json.asArray)
        .map(_.flatMap(_.asString))
        .filter(_.size == 2)
    )
    assertEquals(below, Vector("q-b", "q-c"))
  }

  // ── View streams (protocol 1.17, features/view-streams/languages.feature) ──

  /**
   * The lines of a stream of server-sent events, read as they come, until `enough` or a deadline.
   */
  private def readEvents(path: String)(enough: Vector[String] => Boolean): Vector[String] =
    val response = http.send(
      HttpRequest.newBuilder(URI.create(target.baseUrl + path)).GET().build(),
      HttpResponse.BodyHandlers.ofLines()
    )
    assertEquals(response.statusCode(), 200)
    val lines    = response.body().iterator().asScala
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var read     = Vector.empty[String]
    while !enough(read) && System.nanoTime() < deadline && lines.hasNext do read :+= lines.next()
    response.body().close()
    read

  private def eventsOf(lines: Vector[String]): Vector[(String, String)] =
    lines
      .foldLeft(Vector(Vector.empty[String]))((acc, l) =>
        if l.isEmpty then acc :+ Vector.empty else acc.init :+ (acc.last :+ l)
      )
      .flatMap { fields =>
        val data = fields.find(_.startsWith("data:")).map(_.stripPrefix("data:").trim)
        val name =
          fields.find(_.startsWith("event:")).map(_.stripPrefix("event:").trim).getOrElse("")
        data.filter(_.nonEmpty).map(d => name -> d)
      }

  test("view.stream-named") {
    onlyWhereStreaming()
    Seq("vs-a", "vs-b", "vs-c").foreach(k => assertEquals(post(s"/tree/$k").status, 200))
    eventually()(Some(get("/tree/vs-c/below")).filter(_.status == 200))
    val keys = eventually() {
      val streamed = eventsOf(readEvents("/tree/streamed")(_ => false))
        .map(_._2)
        .flatMap(d => Json.parse(d).toOption.flatMap(_.asString))
      Option.when(Seq("vs-a", "vs-b", "vs-c").forall(streamed.contains))(streamed)
    }
    assertEquals(keys, keys.sorted, "not in the statement's order")
  }

  test("view.watch-named") {
    onlyWhereStreaming()
    assertEquals(post("/tree/vw-a").status, 200)
    // The view holds the row once a stream of every row gives it.
    eventually()(
      Some(eventsOf(readEvents("/tree/streamed")(_ => false)).map(_._2))
        .filter(_.exists(_.contains("\"vw-a\"")))
    )
    // A writer, started once the watcher is caught up, places a row the watch must then give.
    val placed = new java.util.concurrent.atomic.AtomicBoolean(false)
    val lines = readEvents("/tree/watched") { read =>
      val events = eventsOf(read)
      if events.exists(_._1 == "caught-up") && !placed.getAndSet(true) then
        Thread.ofVirtual().start(() => post("/tree/vw-b"): Unit): Unit
      events.exists((n, d) => n == "row" && d.contains("\"vw-b\""))
    }
    val events = eventsOf(lines)
    val caught = events.indexWhere(_._1 == "caught-up")
    assert(caught >= 0, s"no caught-up event: $events")
    assert(
      events.take(caught).exists(_._2.contains("\"vw-a\"")),
      s"vw-a not among the rows now: $events"
    )
    assert(
      events.drop(caught + 1).exists(_._2.contains("\"vw-b\"")),
      s"vw-b not given after: $events"
    )
  }

  test(
    "a service whose view declares a statement that reads another table does not start in every language"
  ) {
    // The language's own declaration reached the check: the statement it sent is the one asked.
    val sent = target.declaredStatement("tree-rows", "under")
    assert(sent.exists(_.contains("WITH RECURSIVE")), s"tree-rows declares under as $sent")
    assertEquals(target.problemsWithStatement("tree-rows", "under", sent.get), Vector.empty)
    // The same declaration reading another view's table is refused, naming all three.
    val problems = target.problemsWithStatement(
      "tree-rows",
      "under",
      "SELECT payload FROM ankka_view_cart_rows WHERE row_key = :row"
    )
    assert(
      problems.exists(p =>
        p.contains("'tree-rows'") && p.contains("'under'") && p.contains("ankka_view_cart_rows")
      ),
      problems.mkString("; ")
    )
  }

  private def joinedRow(key: String): Option[Json] =
    Some(get(s"/joined/rows/$key")).filter(_.status == 200).map(_.json)

  private def notesOf(row: Json): Vector[String] =
    row("notes").flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Vector.empty)

  test("a view of several sources reads every one of them in every language") {
    assertEquals(post("/joined/left/j1-s1/j1-r1").status, 200)
    eventually()(joinedRow("j1-s1"))
    assertEquals(post("/joined/right/j1-r1").status, 200)
    val row = eventually()(joinedRow("j1-s1").filter(notesOf(_) == Vector("left", "right")))
    assertEquals(row("holding").flatMap(_.asString), Some("j1-r1"))
  }

  test("a view finds the rows an event is about by asking a query of its own in every language") {
    assertEquals(post("/joined/left/j2-s1/j2-r1").status, 200)
    assertEquals(post("/joined/left/j2-s2/j2-r1").status, 200)
    assertEquals(post("/joined/left/j2-s3/j2-r2").status, 200)
    Seq("j2-s1", "j2-s2", "j2-s3").foreach(key => eventually()(joinedRow(key)))
    assertEquals(post("/joined/right/j2-r1").status, 200)
    eventually()(joinedRow("j2-s1").filter(notesOf(_) == Vector("left", "right")))
    eventually()(joinedRow("j2-s2").filter(notesOf(_) == Vector("left", "right")))
    // The row holding another right entity is as it was.
    assertEquals(joinedRow("j2-s3").map(notesOf), Some(Vector("left")))
  }

  test("the topology shows a view connected to each of its sources in every language") {
    val declared =
      Json.parse(target.topology).toOption.flatMap(_("declared")).flatMap(_.asArray).get
    val into = declared
      .filter(e => e("to").flatMap(_.asString).contains("joined-rows"))
      .flatMap(e => e("from").flatMap(_.asString).map(_ -> e("kind").flatMap(_.asString)))
      .toSet
    assertEquals(
      into,
      Set[(String, Option[String])](
        "joined-left"  -> Some("events"),
        "joined-right" -> Some("events")
      )
    )
  }

  // ── Topic sources: where each starts ──

  private def topicRows(): Long =
    import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
    scala.concurrent.Await
      .result(
        Database()(using target.system).queryOne(
          SqlFragment.raw("SELECT count(*) AS n FROM ankka_view_topic_rows")
        )(_.get("n", classOf[java.lang.Long]).longValue),
        10.seconds
      )
      .getOrElse(0L)

  private def relayed(): Vector[String] =
    target.broker.publishedTo(ConformanceReference.Relayed).flatMap(_.message.subject).toVector

  test("topic.view-starts-earliest") {
    // A view that declares no start reads what the topic held before the service started.
    val _ = eventually()(Option.when(topicRows() == 3L)(()))
  }

  test("topic.consumer-starts-latest") {
    // Declared `latest`: none of what the topic held when it started is relayed, and what is
    // published after is.
    val _ = target.broker.publish(
      ConformanceReference.Topic,
      """{"n":4}""".getBytes("UTF-8"),
      com.thinkmorestupidless.ankka.core.Metadata.empty
        .withSubject("t-4")
        .set(com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys.Manifest, "fanned")
        .set(
          com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys.ContentType,
          com.thinkmorestupidless.ankka.runtime.remote.Payload.Json
        )
    )
    val _ = eventually()(Option.when(relayed().contains("t-4"))(()))
    assertEquals(relayed().filterNot(_ == "t-4"), Vector.empty)
  }

  // ── Another project's topic (feature 040) ──────────────────────────────────

  /** The topic `partner-relay` reads and publishes to, as the runtime carries them. */
  private val partnerShared = s"${ConformanceReference.Partner}/${ConformanceReference.Shared}"
  private val partnerRelayed =
    s"${ConformanceReference.Partner}/${ConformanceReference.SharedRelayed}"

  private def publishFanned(topic: String, subject: String, n: Int) =
    target.broker.publish(
      topic,
      s"""{"n":$n}""".getBytes("UTF-8"),
      com.thinkmorestupidless.ankka.core.Metadata.empty
        .withSubject(subject)
        .set(com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys.Manifest, "fanned")
        .set(
          com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys.ContentType,
          com.thinkmorestupidless.ankka.runtime.remote.Payload.Json
        )
    )

  test("topics.cross-project-source: a consumer reads another project's topic by its project") {
    val _ = publishFanned(partnerShared, "x-1", 1)
    val _ = eventually()(
      Option.when(
        target.broker.publishedTo(partnerRelayed).flatMap(_.message.subject).contains("x-1")
      )(())
    )
    // Under a group of the service's own, named for the component as any topic source's is: the
    // target states no service name, so `ankka-consumer-<id>`, and nothing of the topic's project.
    val groups = target.broker.positions(partnerShared).keySet
    assertEquals(groups, Set("ankka-consumer-partner-relay"))
  }

  test("topics.cross-project-publication: a consumer publishes to another project's topic") {
    val _ = publishFanned(partnerShared, "x-2", 2)
    val _ = eventually()(
      Option.when(
        target.broker.publishedTo(partnerRelayed).flatMap(_.message.subject).contains("x-2")
      )(())
    )
    // Never to a topic of this project's that has the same name.
    assertEquals(target.broker.publishedTo(ConformanceReference.SharedRelayed), Seq.empty)
  }

  test("topic.version-names-the-group") {
    // Declared at version 2, so it reads under a group whose kind says so; a target that ignored
    // the version would read under the plain one. Waits for the view to have read, not for a
    // count: the case before this one publishes to the topic too.
    val _      = eventually()(Option.when(topicRows() >= 3L)(()))
    val groups = target.broker.positions(ConformanceReference.Topic).keySet
    // The target states no service name, so its groups are named for their component alone.
    assert(groups.contains("ankka-view.v2-topic-rows"), groups)
    assert(!groups.contains("ankka-view-topic-rows"), groups)
  }

  test("consumer.at-least-once-in-order") {
    postJson("/carts/k1/items", cartJson("p1", "Pen", 1))
    assertEquals(post("/carts/k1/checkout").status, 200)
    eventually()(Some(count("k1")).filter(_ >= 1))
    assert(journal("conformance|k1").exists(_._3.contains("checkout")))
  }

  // ── A consumer's several messages ──────────────────────────────────────────

  /** What `checkout-fanout` published about one cart: (record key, n, the message's metadata). */
  private def fanned(
      cart: String
  ): Vector[(Option[String], Int, com.thinkmorestupidless.ankka.core.Metadata)] =
    target.broker
      .publishedTo("conformance-fanout")
      .toVector
      .filter(_.message.metadata.subject.contains(cart))
      .map { d =>
        val n = Json.parse(d.text).toOption.flatMap(_("n")).flatMap(_.asDouble).map(_.toInt)
        (d.message.key, n.getOrElse(fail(s"not a fanned message: ${d.text}")), d.message.metadata)
      }

  private def checkedOut(cart: String): Unit =
    postJson(s"/carts/$cart/items", cartJson("p1", "Pen", 1))
    assertEquals(post(s"/carts/$cart/checkout").status, 200)

  test("consumer.produce-all-in-order") {
    checkedOut("fan1")
    val records = eventually()(Some(fanned("fan1")).filter(_.sizeIs >= 3))
    assertEquals(records.map(_._2), Vector(1, 2, 3))
  }

  test("consumer.produce-all-keys") {
    checkedOut("fan2")
    val records = eventually()(Some(fanned("fan2")).filter(_.sizeIs >= 3))
    // The key named, else the subject; the subject is the cart's id whatever the key.
    assertEquals(records.map(_._1), Vector(Some("fan2"), Some("second:fan2"), Some("fan2")))
    assertEquals(records.map(_._3.subject).distinct, Vector(Some("fan2")))
    assertEquals(records.map(_._3.get("x-n")), Vector(None, None, Some("3")))
  }

  test("consumer.produce-all-empty") {
    postJson("/carts/fan3/items", cartJson("p1", "Pen", 1))
    postJson("/carts/fan3/items", cartJson("p2", "Ink", 1))
    assertEquals(post("/carts/fan3/checkout").status, 200)
    // The two items added published nothing and were handled: the checkout after them is here,
    // and it is all that is here.
    val records = eventually()(Some(fanned("fan3")).filter(_.sizeIs >= 3))
    assertEquals(records.map(_._2), Vector(1, 2, 3))
  }

  test("consumer.produce-all-redelivers") {
    // The consumer is running and has nothing in flight before a publication is made to fail.
    checkedOut("fan4")
    eventually()(Some(fanned("fan4")).filter(_.sizeIs >= 3))

    postJson("/carts/fan5/items", cartJson("p1", "Pen", 1))
    target.broker.failNext("conformance-fanout", after = 1)
    assertEquals(post("/carts/fan5/checkout").status, 200)
    val records = eventually(90.seconds)(Some(fanned("fan5")).filter(_.exists(_._2 == 2)))
    assert(
      records.count(_._2 == 1) >= 2,
      s"the first message was published again: ${records.map(_._2)}"
    )
    assertEquals(records.takeRight(3).map(_._2), Vector(1, 2, 3))
  }

  test("consumer.single-produce-unchanged") {
    postJson("/carts/fan6/items", cartJson("p1", "Pen", 1))
    assertEquals(send("DELETE", "/carts/fan6/items/p1").status, 204)
    val (key, n, metadata) = eventually()(fanned("fan6").headOption)
    assertEquals(n, 0)
    assertEquals(key, Some("fan6"))
    assertEquals(metadata.subject, Some("fan6"))
  }

  // ── A graph consumer's deltas ──────────────────────────────────────────────

  /**
   * The deltas published to `topic` about one subject, each read under the key it was published
   * under by the reader a consumer of the topic would use: what a merge sink would be given.
   */
  private def deltas(topic: String, subject: String): Vector[GraphDelta] =
    target.broker
      .publishedTo(topic)
      .toVector
      .filter(_.message.metadata.subject.contains(subject))
      .map { d =>
        assertEquals(d.message.metadata.eventType, Some(GraphDelta.SchemaName), d.text)
        GraphDelta
          .read(d.message.key, d.message.payload)
          .fold(p => fail(s"$p: ${d.text}"), identity)
      }

  private def cartNode(cart: String, checkedOut: Boolean)(delta: GraphDelta): Unit =
    assertEquals(delta.key, s"node:cart:$cart")
    assertEquals(delta.labels, Vector("Cart"))
    assertEquals(
      delta.properties,
      Map[String, PropertyValue]("cartId" -> cart, "checkedOut" -> checkedOut)
    )

  test("consumer.graph-deltas") {
    postJson("/carts/g1/items", cartJson("p1", "Pen", 1))
    postJson("/carts/g1/items", cartJson("p2", "Ink", 1))
    assertEquals(send("DELETE", "/carts/g1/items/p2").status, 204)
    assertEquals(post("/carts/g1/checkout").status, 200)

    val published = eventually()(Some(deltas("conformance-graph", "g1")).filter(_.sizeIs >= 6))
    // The whole history, in the order it was published: one record for each change to the cart,
    // then the checkout's three, all at the sequence number of the event they came from.
    assertEquals(
      published.map(d => d.key -> d.version),
      Vector(
        "node:cart:g1"        -> 1L,
        "node:cart:g1"        -> 2L,
        "node:cart:g1"        -> 3L,
        "node:cart:g1"        -> 4L,
        "node:checkout:g1"    -> 4L,
        "edge:checked-out:g1" -> 4L
      )
    )
    published.take(3).foreach(cartNode("g1", checkedOut = false))
    cartNode("g1", checkedOut = true)(published(3))
    val checkout = published(4)
    assertEquals(checkout.labels, Vector("Checkout"))
    assertEquals(checkout.properties, Map[String, PropertyValue]("cartId" -> "g1"))
    val edge = published(5)
    assertEquals(
      (edge.edgeType, edge.from, edge.to, edge.properties),
      (Some("CHECKED_OUT"), Some("cart:g1"), Some("checkout:g1"), Map.empty[String, PropertyValue])
    )
    assert(published.forall(!_.isTombstone))
  }

  test("consumer.graph-delete-and-recreate") {
    postJson("/carts/g2/items", cartJson("p1", "Pen", 1))
    assertEquals(send("DELETE", "/carts/g2").status, 204)
    val tombstone = eventually()(deltas("conformance-graph", "g2").find(_.isTombstone))
    assertEquals(tombstone.key, "node:cart:g2")
    // The item, the discard, then the deletion: above every version before it.
    assertEquals(tombstone.version, 3L)

    postJson("/carts/g2/items", cartJson("p9", "Cap", 1))
    val again = eventually() {
      deltas("conformance-graph", "g2").find(d => !d.isTombstone && d.version > tombstone.version)
    }
    assertEquals(again.version, 4L)
    cartNode("g2", checkedOut = false)(again)
    val versions = deltas("conformance-graph", "g2").map(_.version)
    assertEquals(versions, versions.sorted)
  }

  test("consumer.graph-replay-is-equal") {
    // The consumer is running and has nothing in flight before a publication is made to fail.
    postJson("/carts/g3/items", cartJson("p1", "Pen", 1))
    eventually()(deltas("conformance-graph", "g3").headOption)

    postJson("/carts/g4/items", cartJson("p1", "Pen", 1))
    eventually()(deltas("conformance-graph", "g4").headOption)
    // Of the checkout's three records, the second is refused: the first is in the topic, the
    // change is not handled, and it is handled again.
    target.broker.failNext("conformance-graph", after = 1)
    assertEquals(post("/carts/g4/checkout").status, 200)
    val published = eventually(90.seconds) {
      Some(deltas("conformance-graph", "g4")).filter(_.exists(_.key == "node:checkout:g4"))
    }
    val checkedOut = published.filter(d => d.key == "node:cart:g4" && d.version == 2)
    assert(checkedOut.sizeIs >= 2, s"the cart's node was published again: ${published.map(_.key)}")
    assertEquals(checkedOut.distinct.size, 1, "and equal each time, in key, version and value")
    cartNode("g4", checkedOut = true)(checkedOut.head)
  }

  test("consumer.kv-sequence") {
    post("/conformance/profile/kvg1", "Ada")
    post("/conformance/profile/kvg1", "Bea")
    // A key value source delivers the latest state and may skip one before it.
    val last = eventually() {
      deltas("conformance-profile-graph", "kvg1").find(_.properties.get("name").contains("Bea"))
    }
    assertEquals(last.key, "node:profile:kvg1")
    assertEquals(last.labels, Vector("Profile"))
    assertEquals(last.version, 2L, "the state's revision")
    val versions = deltas("conformance-profile-graph", "kvg1").map(_.version)
    assert(versions.forall(_ >= 1), s"never zero: $versions")
    assertEquals(versions, versions.sorted)
  }

  test("kv.delete-is-a-change") {
    post("/conformance/profile/kvg2", "Ada")
    eventually()(deltas("conformance-profile-graph", "kvg2").headOption)
    assertEquals(delete("/conformance/profile/kvg2").body, "done")
    val tombstone = eventually()(deltas("conformance-profile-graph", "kvg2").find(_.isTombstone))
    assertEquals(tombstone.key, "node:profile:kvg2")
    assertEquals(tombstone.version, 2L, "the revision after the state's")
  }

  test("kv.delete-then-write") {
    post("/conformance/profile/kvg3", "Ada")
    assertEquals(delete("/conformance/profile/kvg3").body, "done")
    assertEquals(get("/conformance/profile/kvg3").body, "none")
    post("/conformance/profile/kvg3", "Cy")
    assertEquals(get("/conformance/profile/kvg3").body, "Cy")
    val again = eventually() {
      deltas("conformance-profile-graph", "kvg3").find(_.properties.get("name").contains("Cy"))
    }
    assertEquals(again.version, 3L, "the revisions go on from the deletion")
    // And they survive every instance leaving memory.
    target.restart()
    assertEquals(get("/conformance/profile/kvg3").body, "Cy")
    post("/conformance/profile/kvg3", "Di")
    val after = eventually() {
      deltas("conformance-profile-graph", "kvg3").find(_.properties.get("name").contains("Di"))
    }
    assertEquals(after.version, 4L)
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

  // ── Recurring timers ───────────────────────────────────────────────────────
  //
  // `features/timers/languages.feature`, one scenario per case. The target's `reminder.tick`
  // records `due:<ankka.due>` on the conformance entity it is given; the timer is `recur-<id>`, due
  // at once and every second.

  /** The due times a target's `tick` recorded for `id`, oldest first. */
  private def ticks(id: String): Vector[Long] =
    journal(s"conformance|$id")
      .map(_._3)
      .flatMap(e => """due:(\d+)""".r.findFirstMatchIn(e))
      .map(_.group(1).toLong)

  /** The due time the next run of `recur-<id>` is for, as the timer table holds it. */
  private def nextDue(id: String): Option[Long] =
    given ActorSystem[?] = target.system
    Await.result(
      Database().queryOne(
        SqlFragment.raw(s"SELECT due_for FROM ankka_timers WHERE timer_name = 'recur-$id'")
      )(_.get("due_for", classOf[java.time.Instant]).toEpochMilli),
      10.seconds
    )

  private def spacedBySecond(dues: Vector[Long]): Unit =
    dues.zip(dues.tail).foreach((a, b) => assertEquals(b - a, 1000L, dues.toString))

  // a recurring timer fires once for each period in every language
  test("timer.recurring.cadence") {
    assertEquals(post("/conformance/recur/rc1").status, 204)
    val dues = eventually(15.seconds)(Some(ticks("rc1")).filter(_.size >= 3))
    spacedBySecond(dues)
    assertEquals(post("/conformance/recur/rc1/cancel").status, 204)
  }

  // a handler is told the due time of the timer that ran it in every language
  test("timer.recurring.due-time") {
    assertEquals(post("/conformance/recur/rd1").status, 204)
    val told = eventually(15.seconds)(Some(ticks("rd1")).filter(_.size >= 2))
    val next = nextDue("rd1").getOrElse(fail("no timer recur-rd1"))
    // What the handler was told is the runtime's own cadence: the table's next due is on the same
    // grid, and after everything the handler has been told.
    assertEquals((next - told.head) % 1000L, 0L, s"next due $next is off the grid of $told")
    assert(next > told.last, s"next due $next is not after $told")
    spacedBySecond(told)
    assertEquals(post("/conformance/recur/rd1/cancel").status, 204)
  }

  // a recurring timer set again keeps its next due time in every language
  test("timer.recurring.set-again") {
    assertEquals(post("/conformance/recur/rs1").status, 204)
    val _ = eventually(15.seconds)(Some(ticks("rs1")).filter(_.nonEmpty))
    // Set again with a delay of a minute: a replacement would not run again for a minute.
    assertEquals(post("/conformance/recur/rs1/again").status, 204)
    val seen = ticks("rs1").size
    val dues = eventually(10.seconds)(Some(ticks("rs1")).filter(_.size >= seen + 2))
    spacedBySecond(dues)
    assertEquals(post("/conformance/recur/rs1/cancel").status, 204)
  }

  // a cancelled recurring timer does not fire again in every language
  test("timer.recurring.cancel") {
    assertEquals(post("/conformance/recur/rx1").status, 204)
    val _ = eventually(15.seconds)(Some(ticks("rx1")).filter(_.nonEmpty))
    assertEquals(post("/conformance/recur/rx1/cancel").status, 204)
    assertEquals(nextDue("rx1"), None)
    val after = ticks("rx1").size
    Thread.sleep(3000)
    // One run may already have been under way when it was cancelled; none starts after.
    assert(ticks("rx1").size <= after + 1, s"recur-rx1 went on firing: ${ticks("rx1")}")
  }

  test("timer.recurring.refused") {
    val reply = post("/conformance/recur-refused/rr1")
    assertEquals(reply.status, 400, reply.body)
    assert(reply.body.contains("recur-rr1"), reply.body)
    assertEquals(nextDue("rr1"), None)
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
    // Before feature 022 a process's authenticated route answered 503 whatever was sent; now the
    // runtime verifies, and a request with no token is challenged without the process being asked.
    assertEquals(get("/private/").status, 401)
  }

  // ── Authenticated routes (feature 022) ───────────────────────────────────
  // The runtime verifies a token from the suite's test issuer for every target, and the process is
  // handed the principal: subject, roles, every other claim, and the issuer's configured name.

  private def bearer(token: String) = "Authorization" -> s"Bearer $token"

  test("http.auth-admits-verified-token") {
    val token = ConformanceTarget.issuer.token("ada", roles = Set("buyer"))
    val r     = get("/private/me", bearer(token))
    assertEquals(r.status, 200, r.toString)
    val me = r.json
    assertEquals(me("subject").flatMap(_.asString), Some("ada"))
    assertEquals(me("roles").flatMap(_.asArray).map(_.flatMap(_.asString)), Some(Vector("buyer")))
    assertEquals(me("issuer").flatMap(_.asString), Some("test"))
  }

  test("http.auth-claims") {
    val token = ConformanceTarget.issuer.token("ada", claims = Map("tier" -> "gold"))
    assertEquals(get("/private/me", bearer(token)).json("tier").flatMap(_.asString), Some("gold"))
  }

  test("http.auth-challenges-missing") {
    val r = get("/private/me")
    assertEquals(r.status, 401)
    assert(r.headers.getOrElse("www-authenticate", "").startsWith("Bearer realm="), r.toString)
  }

  test("http.auth-challenges-expired") {
    val token = ConformanceTarget.issuer.token("ada", expiresIn = (-5).minutes)
    val r     = get("/private/me", bearer(token))
    assertEquals(r.status, 401)
    assert(r.headers.getOrElse("www-authenticate", "").contains("invalid_token"), r.toString)
  }

  test("http.auth-challenges-unlisted-issuer") {
    val stranger = com.thinkmorestupidless.ankka.auth.oidc
      .TestIssuer("https://auth.conformance.test/realms/strangers", "strangers", "conformance")
    try
      assertEquals(get("/private/me", bearer(stranger.token("eve"))).status, 401)
      assertEquals(stranger.fetches.get(), 0, "an unlisted issuer's keys were asked for")
    finally stranger.stop()
  }

  test("http.route-acl-overrides-the-endpoint") {
    // `/conformance` admits everyone; this one route does not, and says so without reaching
    // the process. Its siblings under the same prefix are unaffected.
    assertEquals(get("/conformance/closed").status, 403)
    assertEquals(get("/conformance/status/418").status, 418)
  }

  // ── Callers (feature 014) ─────────────────────────────────────────────────
  // Every target runs outside a cluster, so callers are named through the local caller header and
  // the service's own identity is local/local. What is being pinned is that all three SDKs declare
  // the same ACLs and hand the handler the same caller.

  private def as(caller: Caller) = LocalCallers.header(caller)

  test("http.caller-local") {
    assertEquals(get("/callers/whoami").body, "local")
    assertEquals(get("/callers/self").status, 200)
  }

  test("http.caller-gateway") {
    val r = get("/callers/whoami", as(Caller.Gateway))
    assertEquals((r.status, r.body), (200, "gateway"))
    assertEquals(get("/callers/self", as(Caller.Gateway)).status, 403)
  }

  test("http.caller-service") {
    val admitted = get("/callers/whoami", as(Caller.Service("local", "orders")))
    assertEquals((admitted.status, admitted.body), (200, "service:local/orders"))
    assertEquals(get("/callers/whoami", as(Caller.Service("local", "payments"))).status, 403)
    assertEquals(get("/callers/whoami", as(Caller.Service("billing", "orders"))).status, 403)
    assertEquals(get("/callers/self", as(Caller.Service("local", "local"))).body, "self")
  }

  // Feature 040: a route whose ACL names granted callers admits exactly the callers its
  // project granted it to, and a machine is a caller as a service is.
  test("http.granted-admits-a-grant") {
    val service = get("/callers/granted", as(Caller.Service("billing", "invoices")))
    assertEquals((service.status, service.body), (200, "granted"))
    val machine = get("/callers/granted", as(Caller.Machine("affiliates", "network")))
    assertEquals((machine.status, machine.body), (200, "granted"))
    assertEquals(
      get("/callers/granted").status,
      200,
      "a local caller is admitted, as by every matcher"
    )
  }

  test("http.granted-refuses-without") {
    for caller <- Vector(
        Caller.Service("billing", "payroll"),
        Caller.Service("local", "orders"),
        Caller.Machine("affiliates", "other"),
        Caller.Gateway
      )
    do
      val r = get("/callers/granted", as(caller))
      assertEquals(r.status, 403, caller.toString)
      assert(!r.body.contains("invoices") && !r.body.contains("grant"), r.body)
    // A grant on one route opens no other: the caller the grant names is refused elsewhere.
    assertEquals(get("/callers/self", as(Caller.Service("billing", "invoices"))).status, 403)
  }

  test("http.caller-machine") {
    val r = get("/callers/whoami", as(Caller.Machine("affiliates", "network")))
    // The endpoint admits the internet, and a machine is the internet that proved who it is.
    assertEquals((r.status, r.body), (200, "machine:affiliates/network"))
  }

  test("http.caller-in-stream") {
    onlyWhereStreaming()
    assertEquals(get("/callers/events", as(Caller.Gateway)).status, 403)
    assertEquals(get("/callers/events", as(Caller.Service("local", "local"))).status, 200)
  }

  test("http.sse-frames-json-encoded") {
    onlyWhereStreaming()
    val r = get("/conformance/stream/s1")
    assertEquals(r.status, 200)
    val frames =
      r.body.linesIterator.filter(_.startsWith("data:")).map(_.drop("data:".length).trim).toVector
    assertEquals(
      frames.map(f => Json.parse(f).toOption.flatMap(_.asString)),
      Vector(Some(" leading space"), Some("two\nlines"), Some("plain"))
    )
  }

  // ── Sockets (protocol 1.9) ────────────────────────────────────────────────

  private def socket(
      path: String,
      headers: Map[String, String] = Map.empty,
      protocols: Seq[String] = Nil
  ): Either[TestSocket.Refused, TestSocket] =
    TestSocket.open("ws" + target.baseUrl.stripPrefix("http") + path, headers, protocols)

  private def opened(path: String, headers: Map[String, String] = Map.empty): TestSocket =
    socket(path, headers).fold(r => fail(s"not opened: $r"), identity)

  private def socketLog: Vector[String] =
    get("/conformance/socket-log").json.asArray.toVector.flatten.flatMap(_.asString)

  test("socket.frames-in-order") {
    onlyWhereStreaming()
    val s = opened("/conformance/socket/r1")
    (1 to 10).foreach(i => s.send(s"frame $i"))
    assertEquals(
      (1 to 10).map(_ => s.receive()).toVector,
      (1 to 10).map(i => Some(s"frame $i")).toVector
    )
    s.close()
  }

  test("socket.request-context") {
    onlyWhereStreaming()
    val s = opened("/conformance/socket/lobby?tag=a")
    s.send("x")
    s.send("y")
    s.send("context")
    assertEquals(
      Vector(s.receive(), s.receive(), s.receive()),
      Vector(Some("x"), Some("y"), Some("lobby a"))
    )
    s.close()
  }

  private def principalAndCaller(s: TestSocket): Json =
    Json.parse(s.receive().getOrElse(fail("nothing sent"))).fold(p => fail(p), identity)

  test("socket.principal-and-caller") {
    onlyWhereStreaming()
    val token         = ConformanceTarget.issuer.token("ada", roles = Set("buyer"))
    val (name, value) = as(Caller.Gateway)
    val s    = opened("/private/socket", Map("Authorization" -> s"Bearer $token", name -> value))
    val told = principalAndCaller(s)
    assertEquals(told("subject").flatMap(_.asString), Some("ada"))
    assertEquals(told("roles").flatMap(_.asArray).map(_.flatMap(_.asString)), Some(Vector("buyer")))
    assertEquals(told("caller").flatMap(_.asString), Some("gateway"))
    s.close()
  }

  test("socket.token-as-subprotocol") {
    onlyWhereStreaming()
    val token = ConformanceTarget.issuer.token("grace")
    val s = socket("/private/socket", protocols = Seq("ankka.socket", s"ankka.bearer.$token"))
      .fold(r => fail(s"not opened: $r"), identity)
    assertEquals(s.subprotocol, Some("ankka.socket"))
    assertEquals(principalAndCaller(s)("subject").flatMap(_.asString), Some("grace"))
    s.close()
  }

  test("socket.challenged-without-token") {
    onlyWhereStreaming()
    val before  = socketLog.count(_.startsWith("private:"))
    val refused = socket("/private/socket").left.getOrElse(fail("opened without a token"))
    assertEquals(refused.status, 401)
    val challenge = refused.headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("www-authenticate") => v
    }
    assert(challenge.exists(_.startsWith("Bearer")), refused.toString)
    assertEquals(socketLog.count(_.startsWith("private:")), before, "a handler ran")
  }

  test("socket.closed-when-handler-returns") {
    onlyWhereStreaming()
    val s = opened("/conformance/socket-once")
    s.send("go")
    assertEquals(s.closed().code, 1000)
  }

  test("socket.failed-when-handler-throws") {
    onlyWhereStreaming()
    val s = opened("/conformance/socket-fail")
    s.send("go")
    assertEquals(s.closed().code, 1011)
  }

  test("socket.handler-told-of-client-close") {
    onlyWhereStreaming()
    val room = s"closing-${System.nanoTime()}"
    val s    = opened(s"/conformance/socket/$room")
    s.send("hello")
    assertEquals(s.receive(), Some("hello"))
    s.close()
    assertEquals(s.closed().code, 1000)
    eventually()(Option.when(socketLog.contains(s"closed:$room"))(()))
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

  test("agent.names-a-model") {
    ConformanceReference.SecondModel.reset()
    ConformanceReference.SecondModel.expectText("from the second")
    val r = post("/conformance/ask-second/n1", "hi")
    assertEquals(r.status, 200, r.body)
    assertEquals(r.body, "from the second")
    assertEquals(model.callCount, 0, "the default model was not asked")
    assertEquals(ConformanceReference.SecondModel.callCount, 1)
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
    onlyWhereStreaming()
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

  // ── Autonomous agents ──────────────────────────────────────────────────────

  /** Runs a task of type `answer` and answers its id and instance. */
  private def runTask(instructions: String): (String, String) =
    val r = post("/autonomous/tasks/answer", instructions)
    assertEquals(r.status, 200, r.body)
    val j = r.json
    (j("taskId").flatMap(_.asString).get, j("instanceId").flatMap(_.asString).getOrElse(""))

  private def task(id: String): Json = get(s"/autonomous/tasks/$id").json

  private def status(t: Json): String = t("status").flatMap(_.asString).getOrElse("")

  /** Waits for the task to end, and answers its record. */
  private def ended(id: String): Json =
    eventually(20.seconds)(
      Some(task(id)).filter(t => Set("completed", "failed", "cancelled")(status(t)))
    )

  test("auto.completes-with-typed-result") {
    model
      .expectToolCall("lookup", Json.obj("id" -> Json.str("auto1")))
      .expectCompleteTaskJson("""{"answer":"one","sources":["lookup"]}""")
    val (id, _) = runTask("How many under auto1?")
    val t       = ended(id)
    assertEquals(status(t), "completed", t.render)
    assertEquals(
      t("result").flatMap(_.asString).flatMap(Json.parse(_).toOption),
      Json.parse("""{"answer":"one","sources":["lookup"]}""").toOption
    )
    assertEquals(t("iterations").flatMap(_.asDouble), Some(2.0))
    assertEquals(count("auto1"), 1)
  }

  test("auto.fails-on-request") {
    model.expectFailTask("cannot be known")
    val t = ended(runTask("What happens next year?")._1)
    assertEquals(status(t), "failed")
    assertEquals(t("reason").flatMap(_.asString), Some("cannot be known"))
    assertEquals(model.callCount, 1)
  }

  test("auto.malformed-result-is-tool-error") {
    model
      .expectCompleteTaskJson("""{"answer":1}""")
      .expectCompleteTaskJson("""{"answer":"fixed","sources":["memory"]}""")
    val t = ended(runTask("Answer")._1)
    assertEquals(status(t), "completed", t.render)
    val errors =
      model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assert(errors.exists(r => r.isError && r.content.contains("does not match")), errors.toString)
  }

  test("auto.tool-error-continues") {
    model
      .expectToolCall("lookup", Json.obj("id" -> Json.str("")))
      .expectCompleteTaskJson("""{"answer":"none","sources":["lookup"]}""")
    val t = ended(runTask("Look nothing up")._1)
    assertEquals(status(t), "completed", t.render)
    val results =
      model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assertEquals(results.map(_.isError), Vector(true))
  }

  test("auto.guardrail-fails-task") {
    val t = ended(runTask("the key is sk-123")._1)
    assertEquals(status(t), "failed")
    assert(t("reason").flatMap(_.asString).exists(_.contains("no-secrets")), t.render)
    assertEquals(model.callCount, 0)
  }

  test("auto.budget-fails-task") {
    (1 to 6).foreach(i => model.expectToolCall("lookup", Json.obj("id" -> Json.str(s"budget$i"))))
    val t = ended(runTask("Never finish")._1)
    assertEquals(status(t), "failed")
    assertEquals(t("reason").flatMap(_.asString), Some("iteration budget of 4 exhausted"))
    assertEquals(model.callCount, 4)
    val last = model.requests(3).systemMessage.getOrElse("")
    assert(last.contains("iteration 4 of 4") && last.contains("last iteration"), last)
  }

  test("auto.rule-check-fault-is-iteration-failure") {
    // The rule throws the first time: that is no verdict, so the check is made again rather than
    // the result being rejected, and the model is not asked again.
    model.expectCompleteTaskJson("""{"answer":"flaky-once","sources":["memory"]}""")
    val t = ended(runTask("Answer, eventually")._1)
    assertEquals(status(t), "completed", t.render)
    assertEquals(model.callCount, 1)
  }

  test("auto.rule-rejects-then-accepts") {
    model
      .expectCompleteTaskJson("""{"answer":"three","sources":[]}""")
      .expectCompleteTaskJson("""{"answer":"three","sources":["memory"]}""")
    val t = ended(runTask("How many?")._1)
    assertEquals(status(t), "completed", t.render)
    assertEquals(t("iterations").flatMap(_.asDouble), Some(2.0))
    val second =
      model.requests(1).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten
    assert(
      second.exists(r => r.isError && r.content.contains("sources must not be empty")),
      second.toString
    )
  }

  private def createTask(instructions: String, dependsOn: String*): String =
    val deps = dependsOn.map(d => s"\"$d\"").mkString(",")
    val r = postJson(
      "/autonomous/tasks/answer/create",
      s"""{"instructions":"$instructions","dependsOn":[$deps]}"""
    )
    assertEquals(r.status, 200, r.body)
    r.json("taskId").flatMap(_.asString).get

  private def assign(instance: String, ids: String*): Reply =
    postJson(
      s"/autonomous/instances/$instance/assign",
      ids.map(i => s"\"$i\"").mkString("[", ",", "]")
    )

  private def op(instance: String, name: String): Unit =
    val r = post(s"/autonomous/instances/$instance/$name")
    assert(r.status == 204 || r.status == 200, r.toString)

  private def state(instance: String): Json = get(s"/autonomous/instances/$instance/state").json

  test("auto.queue-runs-in-order") {
    val ids = Vector("first", "second", "third").map(createTask(_))
    ids.foreach(_ => model.expectCompleteTaskJson("""{"answer":"x","sources":["memory"]}"""))
    assertEquals(assign("r1", ids*).status, 200)
    val ends = ids.map(id => ended(id)("endedAt").flatMap(_.asDouble).get)
    assertEquals(ends, ends.sorted)
  }

  test("auto.suspend-resume") {
    val warm = createTask("warm up")
    model.expectCompleteTaskJson("""{"answer":"warm","sources":["memory"]}""")
    assign("r-pause", warm): Unit
    ended(warm): Unit
    op("r-pause", "suspend")
    val id = createTask("wait")
    model.expectCompleteTaskJson("""{"answer":"waited","sources":["memory"]}""")
    assign("r-pause", id): Unit
    Thread.sleep(2000)
    assertEquals(status(task(id)), "assigned")
    assertEquals(state("r-pause")("phase").flatMap(_.asString), Some("suspended"))
    op("r-pause", "resume")
    assertEquals(status(ended(id)), "completed")
  }

  test("auto.terminate-unassigns") {
    val warm = createTask("warm up")
    model.expectCompleteTaskJson("""{"answer":"warm","sources":["memory"]}""")
    assign("r2", warm): Unit
    ended(warm): Unit
    op("r2", "suspend")
    val ids = Vector(createTask("one"), createTask("two"))
    assign("r2", ids*): Unit
    op("r2", "terminate")
    ids.foreach { id =>
      val t = task(id)
      assertEquals(status(t), "pending", t.render)
      assert(t("assignee").forall(_.isNull), t.render)
    }
    assertEquals(assign("r2", createTask("too late")).status, 409)
  }

  test("auto.cancel-queued") {
    val warm = createTask("warm up")
    model.expectCompleteTaskJson("""{"answer":"warm","sources":["memory"]}""")
    assign("r3", warm): Unit
    ended(warm): Unit
    op("r3", "suspend")
    val ids = Vector(createTask("keep"), createTask("cancel"))
    assign("r3", ids*): Unit
    assertEquals(post(s"/autonomous/tasks/${ids(1)}/cancel").status, 204)
    assertEquals(status(task(ids(1))), "cancelled")
    assertEquals(
      state("r3")("queued").flatMap(_.asArray).map(_.flatMap(_.asString)),
      Some(Vector(ids(0)))
    )
  }

  test("auto.dependency-result-in-context") {
    val a = createTask("the dependency")
    val b = createTask("the dependent", a)
    model
      .expectCompleteTaskJson("""{"answer":"forty-two","sources":["memory"]}""")
      .expectCompleteTaskJson("""{"answer":"used it","sources":["dependency"]}""")
    assign("r-deps", b, a): Unit
    assertEquals(status(ended(b)), "completed")
    val first = model.requests(1).messages.head.toString
    assert(first.contains(s"Result of task '$a'") && first.contains("forty-two"), first)
  }

  test("auto.dependency-failure-cascades") {
    val a = createTask("will fail")
    val b = createTask("depends on it", a)
    model.expectFailTask("could not")
    assign("r-cascade", a): Unit
    val t = ended(b)
    assertEquals(status(t), "cancelled")
    assertEquals(t("reason").flatMap(_.asString), Some(s"dependency '$a' failed"))
  }

  /**
   * Reads an instance's notifications as server-sent events, on a thread of its own, into a queue
   * of notification JSON: each event's data is a JSON string holding the notification's JSON.
   */
  private def subscribe(
      instance: String
  ): (java.util.concurrent.LinkedBlockingQueue[Json], () => Unit) =
    val queue = java.util.concurrent.LinkedBlockingQueue[Json]()
    val request = HttpRequest
      .newBuilder(URI.create(s"${target.baseUrl}/autonomous/instances/$instance/notifications"))
      .GET()
      .build()
    val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
    val reader = Thread.ofVirtual().start { () =>
      try
        response.get().body().forEach { line =>
          if line.startsWith("data:") then
            val field = Json.parse(line.drop("data:".length).trim).toOption.flatMap(_.asString)
            field.flatMap(Json.parse(_).toOption).foreach(queue.add(_): Unit)
        }
      catch case _: Throwable => ()
    }
    (queue, () => { response.cancel(true): Unit; reader.interrupt() })

  private def typesUntil(
      queue: java.util.concurrent.LinkedBlockingQueue[Json],
      last: String
  ): Vector[String] =
    val out  = Vector.newBuilder[String]
    var name = ""
    while name != last do
      val next = Option(queue.poll(20, java.util.concurrent.TimeUnit.SECONDS))
        .getOrElse(fail(s"no $last after ${out.result()}"))
      name = next("type").flatMap(_.asString).getOrElse("")
      out += name
    out.result()

  test("auto.notifications-in-order") {
    onlyWhereStreaming()
    val instance        = s"watch-${java.util.UUID.randomUUID()}"
    val (queue, cancel) = subscribe(instance)
    try
      assertEquals(typesUntil(queue, "Activated"), Vector("Activated"))
      model
        .expectCompleteTaskJson("""{"answer":"x","sources":[]}""")
        .expectCompleteTaskJson("""{"answer":"x","sources":["memory"]}""")
      val id = createTask("Watch me")
      assign(instance, id): Unit
      assertEquals(
        typesUntil(queue, "TaskCompleted"),
        Vector(
          "TaskAssigned",
          "TaskStarted",
          "IterationStarted",
          "IterationCompleted",
          "TaskResultRejected",
          "IterationStarted",
          "IterationCompleted",
          "TaskCompleted"
        )
      )
    finally cancel()
  }

  test("auto.notifications-no-replay") {
    onlyWhereStreaming()
    val instance = s"late-${java.util.UUID.randomUUID()}"
    model.expectCompleteTaskJson("""{"answer":"x","sources":["memory"]}""")
    val id = createTask("Before anyone watched")
    assign(instance, id): Unit
    ended(id): Unit
    val (queue, cancel) = subscribe(instance)
    try
      Thread.sleep(1500)
      val seen = Iterator
        .continually(queue.poll())
        .takeWhile(_ != null)
        .flatMap(_("type").flatMap(_.asString))
        .toVector
      assert(!seen.exists(_.startsWith("Task")), seen.toString)
    finally cancel()
  }

  test("auto.state-of-idle-instance") {
    val r = get("/autonomous/instances/never-used/state")
    assertEquals(r.status, 200, r.body)
    assertEquals(r.json("phase").flatMap(_.asString), Some("idle"))
    assertEquals(r.json("queued").flatMap(_.asArray), Some(Vector.empty))
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

  // ── Secrets ────────────────────────────────────────────────────────────────

  private def secretPath(name: String) =
    "/conformance/secrets?name=" + java.net.URLEncoder.encode(name, "UTF-8")

  /** Every row of every table, as text, with the table it is in. */
  private def everyRow(): Vector[(String, String)] =
    given ActorSystem[?] = target.system
    val database         = Database()
    val tables = Await.result(
      database.query(
        SqlFragment.raw(
          "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        )
      )(_.get(0, classOf[String])),
      10.seconds
    )
    tables.flatMap { table =>
      Await
        .result(
          database.query(SqlFragment.raw(s"""SELECT row_to_json(t)::text FROM "$table" t"""))(
            _.get(0, classOf[String])
          ),
          10.seconds
        )
        .map(table -> _)
    }

  private def secretRows(name: String): Vector[String] =
    everyRow().collect { case ("ankka_secrets", row) if row.contains(s""""name":"$name"""") => row }

  /**
   * How many copies of `name` the backend holds where it keeps secrets: rows of `ankka_secrets`,
   * or, on the Secret Manager backend, 1 when the fake holds the secret — and then never a row.
   */
  private def heldCopies(name: String): Int =
    ConformanceTarget.secretManager match
      case None => secretRows(name).size
      case Some(fake) =>
        assertEquals(secretRows(name), Vector.empty, s"the database holds '$name'")
        val id =
          DerivedIds.service(ConformanceTarget.SecretProject, ConformanceTarget.SecretService, name)
        if fake.snapshot.get(id).exists(_ > 0) then 1 else 0

  test("secret.put-then-get") {
    assertEquals(post(secretPath("acme"), "sk-acme-1").status, 204)
    val r = get(secretPath("acme"))
    assertEquals(r.status, 200)
    assertEquals(r.body, "sk-acme-1")
    // Every language's read is recorded, with the hosting it came through; a component is named
    // only where the runtime ran the handler itself, never through a process or a module.
    val read = target.recordedReads.filter(r => r.name == "acme" && r.operation == "get").last
    assertEquals((read.outcome, read.hosting), ("read", target.hosting))
    if target.hosting != "embedded" then assertEquals(read.component, None, read.toString)
    // The switch that ran Postgres would be green above; this is what says it did not.
    ConformanceTarget.secretManager.foreach { fake =>
      val id =
        DerivedIds.service(ConformanceTarget.SecretProject, ConformanceTarget.SecretService, "acme")
      assertEquals(fake.latestValue(id), Some("sk-acme-1"))
    }
  }

  test("secret.absent") {
    // First that the route answers at all: a target without it would answer 404 too.
    post(secretPath("kept-beside"), "sk-1"): Unit
    assertEquals(get(secretPath("kept-beside")).status, 200)
    val r = get(secretPath("never-kept"))
    assertEquals(r.status, 404)
    assert(
      r.body.contains("never-kept"),
      s"the 404 must be the store's, not the router's: ${r.body}"
    )
  }

  test("secret.overwrite") {
    post(secretPath("overwritten"), "sk-old"): Unit
    assertEquals(post(secretPath("overwritten"), "sk-new").status, 204)
    assertEquals(get(secretPath("overwritten")).body, "sk-new")
    assertEquals(heldCopies("overwritten"), 1)
  }

  test("secret.delete") {
    post(secretPath("removed"), "sk-gone"): Unit
    assertEquals(delete(secretPath("removed")).status, 204)
    assertEquals(get(secretPath("removed")).status, 404)
    assertEquals(heldCopies("removed"), 0)
  }

  test("secret.stored-encrypted") {
    val value = "sk-conformance-91c2e"
    post(secretPath("encrypted"), value): Unit
    val hex   = value.getBytes("UTF-8").map(b => f"${b & 0xff}%02x").mkString
    val leaks = everyRow().filter((_, row) => row.contains(value) || row.contains(hex))
    assertEquals(leaks.map(_._1).distinct, Vector.empty)
    assertEquals(heldCopies("encrypted"), 1)
  }

  test("secret.refuses-bad-name") {
    val r = post(secretPath("provider acme"), "sk-1")
    assertEquals(r.status, 400)
    assert(r.body.contains("'.', '_', '-' or '/'"), r.body)
  }

  test("secret.refuses-empty-value") {
    assertEquals(post(secretPath("empty"), "").status, 400)
  }

  test("secret.name-with-slash") {
    assertEquals(post(secretPath("provider/initech"), "sk-initech").status, 204)
    assertEquals(get(secretPath("provider/initech")).body, "sk-initech")
  }

  // ── Storage (feature 055) ──────────────────────────────────────────────────
  //
  // Every answer of `/conformance/storage` is a record, refusals included, so a case reads the
  // client's outcome rather than how a language's server turned an error into a status.

  private def encoded(value: String) =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

  private def storagePath(params: (String, String)*) =
    params.map((k, v) => s"$k=${encoded(v)}").mkString("/conformance/storage?", "&", "")

  private def storage(reply: Reply): Json =
    assertEquals(reply.status, 200, reply.body)
    reply.json

  private def word(json: Json, field: String): String =
    json(field).flatMap(_.asString).getOrElse("")

  private def keep(name: String, body: String, contentType: String, bucket: Option[String] = None) =
    storage(
      post(storagePath((bucket.map("bucket" -> _).toSeq :+ ("name" -> name))*), body, contentType)
    )

  private def ask(op: String, params: (String, String)*): Json =
    storage(get(storagePath((("op" -> op) +: params)*)))

  private def names(json: Json): Vector[String] =
    json("names").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)

  test("storage.round-trip: an object kept is read back with its content type and length") {
    assertEquals(word(keep("notes/a.jpg", "hello", "image/jpeg"), "outcome"), "ok")
    val got = ask("get", "name" -> "notes/a.jpg")
    assertEquals(
      (word(got, "outcome"), word(got, "contentType"), word(got, "text")),
      ("ok", "image/jpeg", "hello")
    )
    assertEquals(got("length").flatMap(_.asDouble), Some(5.0))
  }

  test("storage.list: a listing answers the names under a prefix, in order") {
    for n <- Vector("listed/b", "listed/a", "elsewhere/c") do keep(n, "x", "image/jpeg"): Unit
    assertEquals(names(ask("list", "prefix" -> "listed/")), Vector("listed/a", "listed/b"))
  }

  test("storage.stat: a stat answers the length and content type, and nothing for no object") {
    keep("stat/one", "four", "image/png"): Unit
    val info = ask("stat", "name" -> "stat/one")
    assertEquals(word(info, "contentType"), "image/png")
    assertEquals(info("length").flatMap(_.asDouble), Some(4.0))
    assertEquals(word(ask("stat", "name" -> "stat/never"), "outcome"), "absent")
  }

  test("storage.delete-twice: a second deletion removes nothing and is not an error") {
    keep("gone/one", "x", "image/jpeg"): Unit
    assertEquals(word(storage(delete(storagePath("name" -> "gone/one"))), "outcome"), "ok")
    assertEquals(word(storage(delete(storagePath("name" -> "gone/one"))), "outcome"), "ok")
    assertEquals(word(ask("get", "name" -> "gone/one"), "outcome"), "absent")
  }

  test("storage.rules: every SDK refuses the same object names and prefixes for the same reasons") {
    val fixture = Json
      .parse(
        String(
          java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("../protocol/fixtures/storage/rules.json")
          ),
          java.nio.charset.StandardCharsets.UTF_8
        )
      )
      .fold(p => fail(p), identity)
    def rows(field: String) = fixture(field).flatMap(_.asArray).getOrElse(fail(s"no $field"))
    def text(row: Json) =
      word(row, "unit") * row("repeat").flatMap(_.asDouble).fold(1)(_.toInt)
    val checked = for
      (field, rule) <- Vector("objectNames" -> "name", "prefixes" -> "prefix")
      row           <- rows(field)
    yield
      val answer   = storage(post(storagePath("rules" -> rule), text(row)))
      val why      = word(row, "why")
      val accepted = row("accepted").flatMap(_.asBoolean).contains(true)
      if accepted then
        assertEquals(word(answer, "outcome"), "ok", s"$field: $why: ${answer.render}")
      else
        assertEquals(
          (word(answer, "outcome"), word(answer, "code")),
          ("refused", "BAD_REQUEST"),
          s"$field: $why: ${answer.render}"
        )
        assert(
          word(answer, "message").contains(word(row, "says")),
          s"$field: $why: ${answer.render}"
        )
      why
    assertEquals(checked.size, rows("objectNames").size + rows("prefixes").size)
  }

  test(
    "storage.unknown-bucket: a bucket the service does not have is unavailable, naming its own"
  ) {
    val r = ask("get", "bucket" -> "videos", "name" -> "x")
    assertEquals((word(r, "outcome"), word(r, "code")), ("refused", "UNAVAILABLE"), r.render)
    assert(word(r, "message").contains("'videos'"), r.render)
    assert(word(r, "message").contains("'photos'"), r.render)
  }

  test("storage.public-url: a public bucket answers an address; the unnamed bucket refuses one") {
    val url = ask("url", "bucket" -> "photos", "name" -> "listings/42/front.jpg")
    assertEquals(word(url, "outcome"), "ok", url.render)
    assert(word(url, "url").endsWith("/listings/42/front.jpg"), url.render)
    val refused = ask("url", "name" -> "front.jpg")
    assertEquals((word(refused, "outcome"), word(refused, "code")), ("refused", "BAD_REQUEST"))
  }

  test("storage.named-bucket: a named bucket keeps its own objects") {
    keep("front.jpg", "named", "image/jpeg", bucket = Some("photos")): Unit
    assertEquals(word(ask("get", "bucket" -> "photos", "name" -> "front.jpg"), "text"), "named")
    assertEquals(word(ask("get", "name" -> "front.jpg"), "outcome"), "absent")
  }

  // `features/storage-client/languages.feature`, "no object passes through the platform's own
  // program": an object above the storage import's bound is kept by a process, which speaks to the
  // store itself, and refused to a module, whose objects do pass through the runtime. The Scala
  // reference keeps it too. No rpc on `Client` carries storage (`client.proto`), so a process has
  // no other way to hand an object to the sidecar.
  test(
    "storage.large: an object above the import's bound is kept by a process, refused to a module"
  ) {
    val size = 5_000_000
    val kept = keep("large/five-megabytes", "x" * size, "application/octet-stream")
    if target.isModule then
      assertEquals((word(kept, "outcome"), word(kept, "code")), ("refused", "BAD_REQUEST"))
      assert(word(kept, "message").contains("4000000"), kept.render)
    else
      assertEquals(word(kept, "outcome"), "ok", kept.render)
      val stat = ask("stat", "name" -> "large/five-megabytes")
      assertEquals(stat("length").flatMap(_.asDouble), Some(size.toDouble), stat.render)
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

  // ── Approvals and MCP servers (protocol 1.11) ───────────────────────────────
  //
  // `features/agents/approvals.feature`, `autonomous-approvals.feature` and `mcp-servers.feature`,
  // through the reference's `approver` agent and its routes: `ask` answers `{"answered": text}` or
  // `{"awaiting": [{"id", "tool", "arguments"}]}`, and `decide` the same once the turn goes on. The
  // MCP servers are `target.tickets` and `target.guarded`, played on loopback.

  private def onlyWhereApprovals(): Unit =
    assume(!target.isModule, "a module declares no agent that waits for a person")

  private def askApprover(session: String, question: String): Reply =
    post(s"/conformance/approver/$session", question)

  /** The awaiting requests of an outcome, as `(id, tool)`. */
  private def awaitingIn(r: Reply): Vector[(String, String)] =
    assertEquals(r.status, 200, r.body)
    r.json("awaiting")
      .flatMap(_.asArray)
      .getOrElse(fail(s"not awaiting: ${r.body}"))
      .map(j => (j("id").flatMap(_.asString).get, j("tool").flatMap(_.asString).get))

  private def answeredIn(r: Reply): String =
    assertEquals(r.status, 200, r.body)
    r.json("answered").flatMap(_.asString).getOrElse(fail(s"not answered: ${r.body}"))

  private def decideApproval(
      session: String,
      id: String,
      approved: Boolean,
      by: String,
      note: String = ""
  ): Reply =
    postJson(
      s"/conformance/approver/$session/decide/$id",
      Json
        .obj("approved" -> Json.Bool(approved), "by" -> Json.str(by), "note" -> Json.str(note))
        .render
    )

  private def toolResultsOf(request: Int) =
    model.requests(request).messages.collect { case ChatMessage.ToolResults(rs) => rs }.flatten

  test("approval.awaits") {
    onlyWhereApprovals()
    model.expectToolCall("refund", Json.obj("id" -> Json.str("ap1"))).expectText("never sent")
    val waiting = awaitingIn(askApprover("ap-s1", "refund ap1"))
    assertEquals(waiting.map(_._2), Vector("refund"))
    assertEquals(count("ap1"), 0)
    assertEquals(model.callCount, 1)
  }

  test("approval.approved-runs-once") {
    onlyWhereApprovals()
    model.expectToolCall("refund", Json.obj("id" -> Json.str("ap2"))).expectText("refund made")
    val Vector((id, _)) = awaitingIn(askApprover("ap-s2", "refund ap2")): @unchecked
    target.restart()
    assertEquals(
      answeredIn(decideApproval("ap-s2", id, approved = true, by = "dana")),
      "refund made"
    )
    assertEquals(count("ap2"), 1)
    val results = toolResultsOf(1)
    assertEquals(results.map(r => (r.name, r.isError)), Vector(("refund", false)))
    assert(results.head.content.contains("refunded ap2"), results.head.content)
  }

  test("approval.refused-tells-model") {
    onlyWhereApprovals()
    model.expectToolCall("refund", Json.obj("id" -> Json.str("ap3"))).expectText("understood")
    val Vector((id, _)) = awaitingIn(askApprover("ap-s3", "refund ap3")): @unchecked
    val answer = decideApproval("ap-s3", id, approved = false, by = "sam", note = "not this one")
    assertEquals(answeredIn(answer), "understood")
    assertEquals(count("ap3"), 0)
    val results = toolResultsOf(1)
    assertEquals(results.map(_.isError), Vector(true))
    assert(results.head.content.contains("not this one"), results.head.content)
  }

  test("approval.decided-once") {
    onlyWhereApprovals()
    model.expectToolCall("refund", Json.obj("id" -> Json.str("ap4"))).expectText("refund made")
    val Vector((id, _)) = awaitingIn(askApprover("ap-s4", "refund ap4")): @unchecked
    answeredIn(decideApproval("ap-s4", id, approved = true, by = "dana")): Unit
    assertEquals(decideApproval("ap-s4", id, approved = false, by = "sam").status, 409)
    assertEquals(count("ap4"), 1)
  }

  test("approval.session-waits") {
    onlyWhereApprovals()
    model.expectToolCall("refund", Json.obj("id" -> Json.str("ap5")))
    awaitingIn(askApprover("ap-s5", "refund ap5")): Unit
    val again = askApprover("ap-s5", "hello?")
    assertEquals(again.status, 409, again.body)
    assertEquals(model.callCount, 1)
  }

  test("approval.needs-a-name") {
    onlyWhereApprovals()
    model.expectToolCall("refund", Json.obj("id" -> Json.str("ap6"))).expectText("refund made")
    val Vector((id, _)) = awaitingIn(askApprover("ap-s6", "refund ap6")): @unchecked
    assertEquals(decideApproval("ap-s6", id, approved = true, by = " ").status, 400)
    assertEquals(count("ap6"), 0)
    // Nothing was decided: the request is still there to decide.
    assertEquals(
      answeredIn(decideApproval("ap-s6", id, approved = true, by = "dana")),
      "refund made"
    )
  }

  private def awaitingOf(instance: String): Vector[String] =
    state(instance)("awaiting")
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(_("id").flatMap(_.asString))

  test("auto.approval.waits-without-budget") {
    onlyWhereApprovals()
    model.expectToolCall("sensitive_lookup", Json.obj("id" -> Json.str("auto-ap1")))
    val (id, instance) = runTask("Look auto-ap1 up carefully")
    eventually()(Some(awaitingOf(instance)).filter(_.nonEmpty)): Unit
    val iterations = task(id)("iterations").flatMap(_.asDouble)
    Thread.sleep(1500)
    assertEquals(model.callCount, 1)
    val t = task(id)
    assertEquals(status(t), "in-progress", t.render)
    // Waiting spends nothing: the count the budget is held to does not move.
    assertEquals(t("iterations").flatMap(_.asDouble), iterations, t.render)
    assertEquals(count("auto-ap1"), 0)
  }

  test("auto.approval.approved-continues") {
    onlyWhereApprovals()
    model
      .expectToolCall("sensitive_lookup", Json.obj("id" -> Json.str("auto-ap2")))
      .expectCompleteTaskJson("""{"answer":"one","sources":["sensitive_lookup"]}""")
    val (id, instance) = runTask("Look auto-ap2 up carefully")
    val approval       = eventually()(awaitingOf(instance).headOption)
    val decided = postJson(
      s"/autonomous/instances/$instance/decide/$approval",
      """{"approved":true,"by":"dana"}"""
    )
    assert(decided.status == 200 || decided.status == 204, decided.toString)
    val t = ended(id)
    assertEquals(status(t), "completed", t.render)
    assertEquals(count("auto-ap2"), 1)
  }

  test("mcp.tools-offered") {
    onlyWhereApprovals()
    model.expectText("hello")
    assertEquals(answeredIn(askApprover("mcp-s1", "what can you do?")), "hello")
    val offered = model.lastRequest.tools.map(_.name).toSet
    Set("refund", "mcp__tickets__create", "mcp__tickets__search", "mcp__guarded__delete").foreach(
      name => assert(offered(name), s"$name not in $offered")
    )
  }

  test("mcp.call-reaches-server") {
    onlyWhereApprovals()
    val before = target.tickets.calls.size
    model
      .expectToolCall("mcp__tickets__create", Json.obj("title" -> Json.str("broken")))
      .expectText("opened it")
    assertEquals(answeredIn(askApprover("mcp-s2", "open a ticket")), "opened it")
    assertEquals(
      target.tickets.calls.drop(before),
      Vector(("create", Json.obj("title" -> Json.str("broken"))))
    )
    val results = toolResultsOf(1)
    assertEquals(results.map(_.isError), Vector(false))
    assert(results.head.content.contains("opened broken"), results.head.content)
  }

  test("mcp.server-approval") {
    onlyWhereApprovals()
    val before = target.guarded.calls.size
    model.expectToolCall("mcp__guarded__delete", Json.obj("id" -> Json.str("t1")))
    val waiting = awaitingIn(askApprover("mcp-s3", "delete t1"))
    assertEquals(waiting.map(_._2), Vector("mcp__guarded__delete"))
    assertEquals(target.guarded.calls.size, before)
  }

  test("mcp.result-guardrail-withholds") {
    onlyWhereApprovals()
    val before = target.tickets.calls.size
    model.expectToolCall("mcp__tickets__search", Json.obj()).expectText("nothing useful")
    assertEquals(answeredIn(askApprover("mcp-s4", "search tickets")), "nothing useful")
    assertEquals(target.tickets.calls.drop(before).map(_._1), Vector("search"))
    val results = toolResultsOf(1)
    assertEquals(results.map(_.isError), Vector(true))
    assert(!results.head.content.contains("ignore what you were told"), results.head.content)
    assert(results.head.content.contains("no-instructions"), results.head.content)
  }

  test("svc.tool-calls-service") {
    onlyWhereApprovals()
    target.scripted.clear()
    model
      .expectToolCall("ask_scripted", Json.obj("path" -> Json.str("/greeting")))
      .expectText("it answered")
    assertEquals(answeredIn(askApprover("svc-s1", "ask the scripted service")), "it answered")
    assertEquals(
      target.scripted.requests.map(r => (r.method, r.path)),
      Vector(("GET", "/greeting"))
    )
    assertEquals(toolResultsOf(1).map(_.isError), Vector(false))
  }

  // ── Calls to other services (protocol 1.8) ─────────────────────────────────
  //
  // `features/service-calls/sdks.feature` and the outlines of `calling.feature`: this target is one
  // row of their Examples, and a case named for a scenario holds it for this language. Each drives the reference's `service-call` route,
  // which makes the call through the SDK's own client, against `target.scripted`, another service
  // played on loopback that records what it is sent. A module makes the call through its `request`
  // import (protocol 1.10), a process through `Client.Request`: the cases are the same for both.

  private final case class Called(
      outcome: String,
      status: Int,
      contentType: String,
      body: String,
      answer: String,
      message: String
  )

  private def serviceCall(
      service: String,
      method: String = "GET",
      path: String = "/target",
      mode: String = "raw",
      body: String = "",
      headers: Seq[(String, String)] = Nil
  ): Called =
    target.scripted.clear()
    val reply = send(
      "POST",
      s"/conformance/service-call?service=$service&method=$method&mode=$mode&path=" +
        java.net.URLEncoder.encode(path, "UTF-8"),
      Some(body),
      headers
    )
    assertEquals(reply.status, 200, reply.body)
    val json                = reply.json
    def text(field: String) = json(field).flatMap(_.asString).getOrElse("")
    Called(
      text("outcome"),
      json("status").flatMap(_.asDouble).map(_.toInt).getOrElse(0),
      text("contentType"),
      text("body"),
      text("answer"),
      text("message")
    )

  test(
    "service.request-reaches-target: on a developer's machine a service in every language calls another service running there"
  ) {
    val called = serviceCall(
      "scripted",
      method = "POST",
      path = "/payouts?amount=5",
      body = "hello",
      headers = Seq("X-Conformance-Id" -> "c1")
    )
    assertEquals(called.outcome, "response", called.toString)
    val received = target.scripted.requests
    assertEquals(received.size, 1, received.toString)
    val request = received.head
    assertEquals((request.method, request.path), ("POST", "/payouts?amount=5"))
    assertEquals(request.contentType, Some("text/plain"))
    assertEquals(request.text, "hello")
    assertEquals(request.header("x-conformance-id"), Some("c1"))
  }

  test(
    "service.answer-reaches-handler: the conformance suite's call to another service passes for every SDK"
  ) {
    target.scripted.answer(_ =>
      ServiceResponse(
        201,
        "application/json",
        """{"ok":true}""".getBytes,
        Vector("X-Answer" -> "yes")
      )
    )
    try
      val called = serviceCall("scripted")
      assertEquals(
        (called.outcome, called.status, called.contentType, called.body, called.answer),
        ("response", 201, "application/json", """{"ok":true}""", "yes")
      )
    finally
      target.scripted.answer(_ => ServiceResponse(200, "text/plain", "ok".getBytes, Vector.empty))
  }

  test(
    "service.refusal-is-the-answer: a refusal by the service called reaches the calling handler as that refusal"
  ) {
    target.scripted.answer(_ => ServiceResponse(403, "text/plain", "no".getBytes, Vector.empty))
    try
      val raw = serviceCall("scripted")
      assertEquals((raw.outcome, raw.status, raw.body), ("response", 403, "no"))
      val typed = serviceCall("scripted", mode = "typed")
      assertEquals((typed.outcome, typed.status, typed.body), ("failed", 403, "no"))
    finally
      target.scripted.answer(_ => ServiceResponse(200, "text/plain", "ok".getBytes, Vector.empty))
  }

  test("service.unresolvable") {
    val called = serviceCall("unknown")
    assertEquals(called.outcome, "unresolvable", called.toString)
    assert(called.message.contains("unknown"), called.message)
    assertEquals(target.scripted.requests, Vector.empty)
  }

  test("service.unanswered") {
    val called = serviceCall("nobody-home")
    assertEquals(called.outcome, "unanswered", called.toString)
  }

  test(
    "service.identity-mismatch: a call is not sent to a workload that is not the service asked for"
  ) {
    val called = serviceCall("impostor")
    assertEquals(called.outcome, "mismatch", called.toString)
    assertEquals(target.scripted.requests, Vector.empty)
  }

  test("service.platform-headers-replaced") {
    assertEquals(serviceCall("scripted").outcome, "response")
    val request = target.scripted.requests.head
    assertEquals(request.header("x-ankka-caller"), None)
    assert(!request.header("host").contains("elsewhere"), request.headers.toString)
  }

  test("service.counted-from-handler") {
    assertEquals(serviceCall("scripted").outcome, "response")
    eventually() {
      Some(observedCalls())
        .filter(
          _.exists(o => o.to == "service:local/scripted" && o.caller.contains("service-call"))
        )
    }
  }

  // ── Topology ───────────────────────────────────────────────────────────────

  // `features/topology/languages.feature`: this target is one row of the outline's Examples, so
  // the scenario holds in a language when this case passes against that language's reference.
  test("topology.declared-sources: a service declares the same connections in every language") {
    val document = Json.parse(target.topology).fold(p => fail(s"not JSON: $p"), identity)
    def strings(of: Json, field: String) = of(field).flatMap(_.asString).getOrElse(fail(s"$of"))
    val nodes = document("nodes").flatMap(_.asArray).getOrElse(fail(target.topology))
    // The platform's own components are in every agent-capable service; what is compared is what
    // the reference service declares.
    val platform = nodes
      .filter(_("platform").flatMap(_.asBoolean).contains(true))
      .map(strings(_, "id"))
      .toSet
    val declared = document("declared")
      .flatMap(_.asArray)
      .getOrElse(fail(target.topology))
      .map(e => (strings(e, "from"), strings(e, "to"), strings(e, "kind")))
      .filterNot((from, to, _) => platform(from) || platform(to))

    // Equal, not contained: a connection missing and a connection invented both fail.
    assertEquals(
      declared.toSet,
      Set(
        ("shopping-cart", "cart-rows", "events"),
        ("tree-node", "tree-rows", "events"),
        ("joined-left", "joined-rows", "events"),
        ("joined-right", "joined-rows", "events"),
        ("shopping-cart", "checkout-recorder", "events"),
        ("shopping-cart", "checkout-fanout", "events"),
        ("checkout-fanout", "topic:conformance-fanout", "topic-publication"),
        ("shopping-cart", "cart-graph", "events"),
        ("cart-graph", "topic:conformance-graph", "topic-publication"),
        ("profile", "profile-graph", "state"),
        // A workflow read as a source (feature 046).
        ("checkout", "checkout-rows", "workflow"),
        ("checkout", "checkout-ends", "workflow"),
        ("profile-graph", "topic:conformance-profile-graph", "topic-publication"),
        ("topic:conformance-topic", "topic-rows", "topic-subscription"),
        ("topic:conformance-topic", "topic-relay", "topic-subscription"),
        ("topic-relay", "topic:conformance-topic-relayed", "topic-publication"),
        // A topic on a declared broker is named with it (feature 037).
        ("topic:legacy/conformance-contracts", "contract-relay", "topic-subscription"),
        ("contract-relay", "topic:legacy/conformance-contracted", "topic-publication"),
        // Another project's topic is named with its project (feature 040).
        ("topic:partner/conformance-shared", "partner-relay", "topic-subscription"),
        ("partner-relay", "topic:partner/conformance-shared-relayed", "topic-publication"),
        // A consumer of a replicated entity (feature 051).
        ("replicated-vehicle", "vehicle-feed", "events"),
        ("vehicle-feed", "topic:conformance-vehicle-steps", "topic-publication")
      )
    )
    val kinds = nodes.map(n => strings(n, "id") -> strings(n, "kind")).toMap
    assertEquals(kinds.get("cart-rows"), Some("View"))
    assertEquals(kinds.get("checkout-recorder"), Some("Consumer"))
    assertEquals(kinds.get("shopping-cart"), Some("EventSourcedEntity"))
  }

  // ── Workflow sources (feature 046, features/workflow-sources/languages.feature) ──

  private def checkoutRow(id: String) = get(s"/conformance/checkout/$id/row")

  /** The checkout's row once its standing is `standing`. */
  private def rowStanding(id: String, standing: String): Json =
    eventually(30.seconds)(
      Some(checkoutRow(id))
        .filter(_.status == 200)
        .map(_.json)
        .filter(_("standing").flatMap(_.asString).contains(standing))
    )

  private def end(id: String) = get(s"/conformance/checkout/$id/end")

  test("view.workflow-completed: a view reads a workflow in every language") {
    assertEquals(post("/conformance/checkout/ws1", "ok").body, "started")
    val row = rowStanding("ws1", "Completed")
    assertEquals(row("status").flatMap(_.asString), Some("charged"))
    assertEquals(row("id").flatMap(_.asString), Some("ws1"))
  }

  test("view.workflow-failure-recorded: a failed workflow's standing in every language") {
    assertEquals(post("/conformance/checkout/ws2", "abort").body, "started")
    val row = rowStanding("ws2", "Failed")
    assertEquals(row("status").flatMap(_.asString), Some("aborted"))
    assertEquals(row("failure").flatMap(_.asString), Some("payment declined"))
  }

  test("view.workflow-no-change-without-state: what records no state is no change") {
    assertEquals(post("/conformance/checkout/ws3", "drop").body, "started")
    // The last state recorded: reserved, and running on to `charge`, which fails.
    eventually(30.seconds)(
      Some(checkoutRow("ws3"))
        .filter(_.status == 200)
        .map(_.json)
        .filter(_("status").flatMap(_.asString).contains("reserved"))
    )
    // The compensation fails the workflow and records nothing: the journal has the failure...
    eventually(30.seconds)(
      Some(journal("checkout|ws3")).filter(_.exists(_._3.contains("payment declined")))
    )
    Thread.sleep(5000)
    // ...and the row is still that last state, running on the step that failed.
    val row = checkoutRow("ws3").json
    assertEquals(row("standing").flatMap(_.asString), Some("Running"))
    assertEquals(row("status").flatMap(_.asString), Some("reserved"))
    assertEquals(row("step").flatMap(_.asString), Some("charge"))
  }

  test("view.workflow-by-standing: a declared query lists the rows of one standing") {
    post("/conformance/checkout/ws4", "ok")
    post("/conformance/checkout/ws5", "abort")
    rowStanding("ws4", "Completed")
    rowStanding("ws5", "Failed")
    val failed = get("/conformance/checkout-rows/Failed").json.asArray.getOrElse(fail("no array"))
    val ids    = failed.flatMap(_.asString)
    assert(ids.contains("ws5"), ids.toString)
    assert(!ids.contains("ws4"), ids.toString)
  }

  test("consumer.workflow-end-once: a consumer is handed a failed workflow's standing") {
    post("/conformance/checkout/ws6", "ok")
    post("/conformance/checkout/ws7", "abort")
    post("/conformance/checkout/ws8", "drop")
    assertEquals(eventually(30.seconds)(Some(end("ws6")).filter(_.status == 200)).body, "Completed")
    assertEquals(
      eventually(30.seconds)(Some(end("ws7")).filter(_.status == 200)).body,
      "Failed: payment declined"
    )
    // A workflow that ends recording nothing hands the consumer no end.
    eventually(30.seconds)(
      Some(journal("checkout|ws8")).filter(_.exists(_._3.contains("payment declined")))
    )
    Thread.sleep(3000)
    assertEquals(end("ws8").status, 404)
  }

  test("discovery.workflow-source-needs-1.16: a runtime too old for a workflow source is refused") {
    // The target's own refusal, asked once: a sidecar retries a refused discovery until answered.
    val refused = target.refusalAt("1.15") match
      case None          => assume(false, "a target in this process has no discovery to refuse"); ""
      case Some(None)    => fail("a runtime stating 1.15 was answered")
      case Some(Some(m)) => m
    // A module refuses by panicking in its discovery export, which reaches the host as a trap
    // with no message; a process answers with its words, which name the components and versions.
    if !target.isModule then
      assert(refused.contains("1.16") && refused.contains("1.15"), refused)
      assert(refused.contains("checkout-rows") || refused.contains("CheckoutRows"), refused)
    // At 1.16 a workflow source is known. A process reference also declares a watched query, which
    // needs 1.17, so it may still be refused, but only for that and never for its workflow source.
    // Every reference also declares a replicated entity, which needs 1.19 (feature 051).
    target.refusalAt("1.16") match
      case Some(Some(m)) =>
        assert(!m.contains("checkout-rows") && !m.contains("CheckoutRows"), m)
        if !target.isModule then
          assert((m.contains("watched") && m.contains("1.17")) || m.contains("1.19"), m)
      case answered => assertEquals(answered, Some(None))
  }

  // ── Replicated entities (feature 051) ───────────────────────────────────────

  /** The kind of each platform record in an entity's journal, in order; domain records are 0. */
  private def journalKinds(persistenceId: String): Vector[Int] =
    given ActorSystem[?] = target.system
    val cbor             = com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper()
    Await.result(
      Database().query(
        SqlFragment.raw(
          s"SELECT event_payload FROM event_journal WHERE persistence_id = '$persistenceId' ORDER BY seq_nr"
        )
      )(r => cbor.readTree(r.get("event_payload", classOf[Array[Byte]])).path("kind").asInt(0)),
      10.seconds
    )

  test("es.replicated-declares: an entity declared replicated says so, and needs 1.19") {
    // Every SDK sends `replicated` on the entity's detail and `mirrored_changes` on a consumer's,
    // and refuses a runtime from before replication rather than be read as unreplicated.
    assert(target.componentIds.contains("replicated-vehicle"), target.componentIds.toString)
    target.refusalAt("1.18") match
      case None          => () // in this process: the descriptor is the Scala SDK's own
      case Some(None)    => fail("a runtime stating 1.18 was answered")
      case Some(Some(m)) =>
        // A module refuses by trapping, which carries no message.
        if !target.isModule then assert(m.contains("1.19"), m)
    // Every reference also declares an MCP endpoint, which needs 1.20 (feature 054): a 1.19
    // runtime may be refused for that, and never for replication.
    target.refusalAt("1.19") match
      case Some(Some(m)) if target.isModule || m.contains("1.20") => ()
      case Some(Some(m)) => fail(s"a runtime stating 1.19 was refused: $m")
      case _             => ()
  }

  test("consumer.mirrored-declares: a consumer given mirrored changes says so") {
    assert(target.componentIds.contains("vehicle-feed"), target.componentIds.toString)
    assertEquals(target.mirroredChanges("vehicle-feed"), Some(true))
    assertEquals(target.mirroredChanges("checkout-fanout"), Some(false))
  }

  test(
    "es.replicated-context: a command is told its installation and home, an event where written"
  ) {
    // The SDK reads `installation` and `at_home` off the command, and the event's `installation`
    // off each event it folds, and hands them to the handler and the event handler.
    val placed = post("/vehicles/rc1/steps", "frame")
    assertEquals(placed.status, 200, placed.body)
    assertEquals(placed.json, Json.parse("""{"installation":"a","home":true}""").toOption.get)
    post("/vehicles/rc1/steps", "wheels")
    // After a restart the state is rebuilt from the journal: the events' installations come back.
    target.restart()
    val read = get("/vehicles/rc1")
    assertEquals(read.status, 200, read.body)
    assertEquals(
      read.json,
      Json
        .parse(
          """{"steps":["frame","wheels"],"writtenAt":["a","a"],"local":[true,true]}"""
        )
        .toOption
        .get
    )
  }

  test("es.replicated-effects: a handover and a change to the replication set reach the journal") {
    // The SDK writes the effect's `ReplicationChange` on the reply; the runtime writes it as the
    // platform's own record after the events: a set change is kind 4, a handover kind 5.
    post("/vehicles/re1/steps", "frame")
    assertEquals(post("/vehicles/re1/replicate-to/b").status, 200)
    assertEquals(post("/vehicles/re1/stop-replicating-to/b").status, 200)
    val kinds = journalKinds("replicated-vehicle|re1")
    // The first command made `a` the home (kind 3) and its set (kind 4), then the step.
    assertEquals(kinds.filter(_ == 0).size, 1, kinds.toString)
    assertEquals(kinds.takeRight(2), Vector(4, 4), kinds.toString)
    post("/vehicles/re2/steps", "frame")
    // The home is handed only to an installation in the replication set.
    assertEquals(post("/vehicles/re2/hand-over/b").status, 400)
    assertEquals(post("/vehicles/re2/replicate-to/b").status, 200)
    val handed = post("/vehicles/re2/hand-over/b")
    assertEquals(handed.status, 200, handed.body)
    assertEquals(journalKinds("replicated-vehicle|re2").last, 5)
  }

  // ── Contracts, declared brokers and parallel partitions (feature 037) ──────

  // `features/topics/contracts.feature` and `features/topics/brokers.feature`, for every language:
  // what a component states reaches the runtime the same way from each SDK, and the fingerprint of
  // one schema document is the same in each.
  test(
    "contracts.declared: a consumer states its contract, broker and parallel reading in every language"
  ) {
    val (options, publication) = target.contractRelay.getOrElse(fail("no contract-relay"))
    val expected               = ConformanceReference.OrderContract
    assertEquals(options.contract, Some(expected))
    assertEquals(options.broker, Some(ConformanceReference.DeclaredBroker))
    assertEquals(options.parallel, true)
    assertEquals(publication.topic, ConformanceReference.Contracted)
    assertEquals(publication.contract, Some(expected))
    assertEquals(publication.broker, Some(ConformanceReference.DeclaredBroker))
    // The fixture's fingerprint: `protocol/fixtures/contracts/fingerprints.json`, row `order.v1`.
    assertEquals(
      expected.fingerprint,
      "sha256:79f2b2961c07b4565ebcf05e35163318c7cdcca7f70d1de60d8118e8562c17e0"
    )
  }

  test("contracts.edges: the topology says what a component states for a topic") {
    val document = Json.parse(target.topology).fold(p => fail(s"not JSON: $p"), identity)
    val edges    = document("declared").flatMap(_.asArray).getOrElse(fail(target.topology))
    val relay = edges.filter(e =>
      e("from").flatMap(_.asString).contains("contract-relay") ||
        e("to").flatMap(_.asString).contains("contract-relay")
    )
    assertEquals(relay.size, 2, target.topology)
    relay.foreach { e =>
      assertEquals(e("broker").flatMap(_.asString), Some("legacy"), e.render)
      assertEquals(
        e("contract").flatMap(_("fingerprint")).flatMap(_.asString),
        Some(ConformanceReference.OrderContract.fingerprint),
        e.render
      )
    }
  }

  // `features/topics/parallelism.feature`: a parallel consumer's process answers two handles at
  // once. The broker in memory has one partition, so the two are sent to the process directly, the
  // second before the first is answered; both are answered, each with its own message handled.
  test("contracts.parallel: two concurrent handles of a parallel consumer are both answered") {
    assertEquals(target.handleBoth(1, 10), Vector(Some(2), Some(11)))
  }

  /** The observed calls of the target's topology: who called whom, handler to handler. */
  private final case class Observed(
      from: String,
      to: String,
      caller: String,
      callee: String,
      ok: Long,
      refused: Long,
      failed: Long
  )

  private def observedCalls(): Vector[Observed] =
    val document = Json.parse(target.topology).fold(p => fail(s"not JSON: $p"), identity)
    def text(of: Json, field: String) = of(field).flatMap(_.asString).getOrElse(fail(s"$of"))
    def count(of: Json, field: String) =
      of("handled").flatMap(_(field)).flatMap(_.asDouble).map(_.toLong).getOrElse(fail(s"$of"))
    for
      call <- document("calls").flatMap(_.asArray).getOrElse(fail(target.topology))
      pair <- call("pairs").flatMap(_.asArray).getOrElse(fail(target.topology))
    yield Observed(
      text(call, "from"),
      text(call, "to"),
      text(pair, "caller"),
      text(pair, "callee"),
      count(pair, "ok"),
      count(pair, "refused"),
      count(pair, "failed")
    )

  // `features/topology/languages.feature`: this target is one row of the outline's Examples. The
  // reference service's consumer and its workflow step each call another component through the
  // client, from a handler that runs in the target's own language: the call is attributed only if
  // the target carries on what its handler was given. A target that drops it is not papered over
  // as an unknown caller; it fails here.
  test("topology.call-attributed: a call is attributed to its caller in every language") {
    postJson("/carts/ta1/items", cartJson("p1", "Pen", 1))
    assertEquals(post("/carts/ta1/checkout").status, 200)
    eventually()(Some(count("ta1")).filter(_ >= 1))
    post("/conformance/checkout/ta2", "ok")
    eventually()(Some(checkoutStatus("ta2")).filter(_ == "charged"))
    assertEquals(post("/conformance/ta3/refuse", "").status, 409)
    assertEquals(post("/conformance/ta3/misbehave").status, 500)

    def from(caller: String, handler: String, to: String, callee: String)(of: Vector[Observed]) =
      of.find(c => c.from == caller && c.caller == handler && c.to == to && c.callee == callee)

    // Waited for on the counts themselves: a call is counted where it ends.
    val calls = eventually() {
      val seen = observedCalls()
      Option.when(
        from("checkout-recorder", "on-message", "conformance", "record")(seen).exists(_.ok >= 1) &&
          from("checkout", "reserve", "shopping-cart", "total-quantity")(seen).exists(_.ok >= 1) &&
          seen.exists(c => c.to == "conformance" && c.callee == "refuse" && c.refused >= 1) &&
          seen.exists(c => c.to == "conformance" && c.callee == "misbehave" && c.failed >= 1)
      )(seen)
    }

    // The consumer's call and the step's are theirs, and nobody's else.
    assertEquals(
      calls.filter(c => c.from == "unknown" && c.to == "conformance" && c.callee == "record"),
      Vector.empty,
      "a call to the recorder came from nobody"
    )
    assertEquals(
      calls.filter(c =>
        c.from == "unknown" && c.to == "shopping-cart" && c.callee == "total-quantity"
      ),
      Vector.empty,
      "a call from the step came from nobody"
    )
    // A refusal and a failure are counted as what they were, and from the route that made them.
    val refusal = calls.find(c => c.to == "conformance" && c.callee == "refuse").get
    assert(refusal.from.startsWith("endpoint:"), refusal.toString)
    assertEquals(refusal.failed, 0L, "a refusal is not a failure")
    val failure = calls.find(c => c.to == "conformance" && c.callee == "misbehave").get
    assert(failure.from.startsWith("endpoint:"), failure.toString)
    assertEquals(failure.refused, 0L, "a failure is not a refusal")
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

  // ── An MCP endpoint (feature 054): the raw protocol at /mcp ─────────────────
  //
  // These cases own the outline row "an SDK's MCP endpoint answers as the Scala reference does" of
  // features/polyglot/conformance.feature, and the three scenarios of
  // features/mcp-endpoints/languages.feature, one row per language (no Gherkin suite runs either):
  //   "a Python, TypeScript or Rust service declares an MCP endpoint and the platform answers the
  //    protocol for it": mcp-endpoint.lists-tools, unknown-tool-is-protocol-error and
  //    bad-argument-is-protocol-error (the platform answers; the process only runs the tool);
  //   "a service's own code is asked to run a tool with typed arguments and the principal, and never
  //    sees the credential": mcp-endpoint.call-reaches-handler, client-key-admits-subject and
  //    token-admits-subject;
  //   "a service's own code issues, lists and revokes client keys through its client":
  //    mcp-endpoint.client-key-admits-subject (issues), client-key-listed (lists) and
  //    client-key-revoked (revokes).

  private def mcp(method: String, params: String, headers: (String, String)*): Reply =
    send(
      "POST",
      "/mcp",
      Some(s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""),
      headers = ("Accept" -> "application/json, text/event-stream") +: headers,
      contentType = "application/json"
    )

  private def callTool(name: String, arguments: String, headers: (String, String)*): Reply =
    mcp("tools/call", s"""{"name":"$name","arguments":$arguments}""", headers*)

  private def toolsListed(headers: (String, String)*): Vector[Json] =
    val r = mcp("tools/list", "{}", headers*)
    r.json("result").flatMap(_("tools")).flatMap(_.asArray).getOrElse(fail(s"no tools: $r"))

  private def resultOf(r: Reply): Json =
    assertEquals(r.status, 200, r.toString)
    r.json("result").getOrElse(fail(s"no result: $r"))

  private def errorOf(r: Reply): (Int, String) =
    val e = r.json("error").getOrElse(fail(s"no error: $r"))
    (
      e("code").flatMap(_.asDouble).map(_.toInt).getOrElse(0),
      e("message").flatMap(_.asString).getOrElse("")
    )

  private def textOf(result: Json): String =
    result("content")
      .flatMap(_.asArray)
      .flatMap(_.headOption)
      .flatMap(_("text"))
      .flatMap(_.asString)
      .getOrElse("")

  private def isError(result: Json): Boolean = result("isError").flatMap(_.asBoolean).contains(true)

  private def addTea(cartId: String): Unit =
    val r = postJson(s"/carts/$cartId/items", """{"productId":"tea","name":"Tea","quantity":2}""")
    assert(r.status < 300, r.toString)

  /** Issues a key through the reference's own route: `(id, key)`. */
  private def issueKey(
      subject: String,
      scope: Seq[String] = Nil,
      attributes: Map[String, String] = Map.empty
  ): (String, String) =
    val scopeJson = scope.map(s => s"\"$s\"").mkString("[", ",", "]")
    val attrsJson = attributes.map((k, v) => s"\"$k\":\"$v\"").mkString("{", ",", "}")
    val r = postJson(
      "/mcp-keys",
      s"""{"subject":"$subject","scope":$scopeJson,"attributes":$attrsJson}"""
    )
    assertEquals(r.status, 200, r.toString)
    (r.json("id").flatMap(_.asString).get, r.json("clientKey").flatMap(_.asString).get)

  private def keyed(key: String)         = "Authorization" -> s"Bearer $key"
  private def anyKey(): (String, String) = keyed(issueKey("conformance")._2)

  private def named(tools: Vector[Json], name: String): Json =
    tools.find(_("name").flatMap(_.asString).contains(name)).getOrElse(fail(s"$name not listed"))

  test("mcp-endpoint.lists-tools") {
    val tools = toolsListed(anyKey())
    assertEquals(
      tools.flatMap(_("name").flatMap(_.asString)).toSet,
      Set("get_cart", "add_item", "checkout")
    )
    def described(name: String) = named(tools, name)("description").flatMap(_.asString)
    assertEquals(described("get_cart"), Some("Reads one cart"))
    assertEquals(described("add_item"), Some("Adds an item to a cart"))
    assertEquals(described("checkout"), Some("Checks a cart out"))
    val schema = named(tools, "add_item")("inputSchema").getOrElse(fail("no schema"))
    assertEquals(schema("type").flatMap(_.asString), Some("object"))
    assertEquals(
      schema("properties").flatMap(_("quantity")).flatMap(_("type")).flatMap(_.asString),
      Some("integer")
    )
    assertEquals(
      schema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toSet),
      Some(Set("cartId", "productId", "quantity"))
    )
  }

  test("mcp-endpoint.call-reaches-handler") {
    val cartId = s"mcp-${System.nanoTime()}"
    addTea(cartId)
    val result = resultOf(callTool("get_cart", s"""{"cartId":"$cartId"}""", anyKey()))
    assert(!isError(result), result.render)
    val cart = result("structuredContent")
      .flatMap(_("cart"))
      .getOrElse(fail(s"no structured cart: ${result.render}"))
    assertEquals(cart("cartId").flatMap(_.asString), Some(cartId))
    assertEquals(
      cart("items").flatMap(_.asArray).map(_.flatMap(_("productId")).flatMap(_.asString)),
      Some(Vector("tea"))
    )
    // The same answer, as text.
    assertEquals(
      Json
        .parse(textOf(result))
        .toOption
        .flatMap(_("cart"))
        .flatMap(_("cartId"))
        .flatMap(_.asString),
      Some(cartId)
    )
  }

  test("mcp-endpoint.handler-error-is-tool-error") {
    val result = resultOf(
      callTool("add_item", """{"cartId":"mcp-zero","productId":"tea","quantity":0}""", anyKey())
    )
    assert(isError(result), result.render)
    assert(textOf(result).contains("quantity must be greater than zero"), result.render)
  }

  test("mcp-endpoint.unknown-tool-is-protocol-error") {
    val (code, message) = errorOf(callTool("refund", "{}", anyKey()))
    assertEquals(code, -32602)
    assert(message.contains("refund"), message)
  }

  test("mcp-endpoint.bad-argument-is-protocol-error") {
    val (code, message) = errorOf(
      callTool("add_item", """{"cartId":"c","productId":"tea","quantity":"two"}""", anyKey())
    )
    assertEquals(code, -32602)
    assert(message.contains("quantity"), message)
  }

  test("mcp-endpoint.client-key-admits-subject") {
    val (_, key) = issueKey("acme", attributes = Map("person" -> "alice"))
    val result   = resultOf(callTool("get_cart", """{"cartId":"mcp-acme"}""", keyed(key)))
    assert(!isError(result), result.render)
    val structured = result("structuredContent").getOrElse(fail(result.render))
    assertEquals(structured("subject").flatMap(_.asString), Some("acme"))
    assertEquals(structured("claims").flatMap(_("person")).flatMap(_.asString), Some("alice"))
    // The handler was told nothing that reads as the key, whole or in part.
    assert(!result.render.contains(key), "the key reached the handler")
    assert(!result.render.contains(key.takeRight(16)), "part of the key reached the handler")
  }

  test("mcp-endpoint.client-key-scope") {
    val (_, key) = issueKey("acme", scope = Seq("get_cart"))
    assertEquals(toolsListed(keyed(key)).flatMap(_("name").flatMap(_.asString)), Vector("get_cart"))
    val (code, message) = errorOf(callTool("checkout", """{"cartId":"c"}""", keyed(key)))
    assertEquals(code, -32602)
    assert(message.contains("checkout"), message)
  }

  test("mcp-endpoint.client-key-listed") {
    val (id, key) = issueKey("listed-subject")
    val r         = get("/mcp-keys")
    assertEquals(r.status, 200, r.toString)
    val listed = r.json.asArray.getOrElse(fail(r.toString))
    val mine =
      listed.find(_("id").flatMap(_.asString).contains(id)).getOrElse(fail(s"$id not listed: $r"))
    assertEquals(mine("subject").flatMap(_.asString), Some("listed-subject"))
    assert(!r.body.contains(key), "the key is in the listing")
    assert(!r.body.contains(key.takeRight(16)), "part of the key is in the listing")
  }

  test("mcp-endpoint.client-key-revoked") {
    val (id, key) = issueKey("acme")
    assertEquals(mcp("tools/list", "{}", keyed(key)).status, 200)
    assertEquals(delete(s"/mcp-keys/$id").status, 200)
    // On the instance that revoked, at once; the wait is for a slow machine.
    eventually()(Some(mcp("tools/list", "{}", keyed(key)).status).filter(_ == 401))
  }

  test("mcp-endpoint.token-admits-subject") {
    val token  = ConformanceTarget.issuer.token("ada")
    val result = resultOf(callTool("get_cart", """{"cartId":"mcp-token"}""", bearer(token)))
    assert(!isError(result), result.render)
    assertEquals(
      result("structuredContent").flatMap(_("subject")).flatMap(_.asString),
      Some("ada")
    )
  }

  test("mcp-endpoint.metadata-names-issuer") {
    val r = get("/.well-known/oauth-protected-resource/mcp")
    assertEquals(r.status, 200, r.toString)
    assertEquals(
      r.json("authorization_servers").flatMap(_.asArray).map(_.flatMap(_.asString)),
      Some(Vector("https://auth.conformance.test/realms/test"))
    )
  }

  test("mcp-endpoint.reference-tool") {
    val cartId = s"mcp-${System.nanoTime()}"
    addTea(cartId)
    val result = resultOf(callTool("checkout", s"""{"cartId":"$cartId"}""", anyKey()))
    assert(!isError(result), result.render)
    assertEquals(get(s"/carts/$cartId").json("checkedOut").flatMap(_.asBoolean), Some(true))
  }
