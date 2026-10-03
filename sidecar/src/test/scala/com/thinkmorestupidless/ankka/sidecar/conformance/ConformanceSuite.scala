package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.testkit.LogCapturing
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

  /** A module answers every call whole: its handlers and routes cannot stream (WASM-ABI.md). */
  private def onlyWhereStreaming(): Unit =
    assume(!target.isModule, "a module cannot stream; refused at its discovery")

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
      "GET /private/",
      "GET /callers/whoami",
      "POST /autonomous/tasks/{type}",
      "GET /autonomous/tasks/{id}"
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

  test("secret.put-then-get") {
    assertEquals(post(secretPath("acme"), "sk-acme-1").status, 204)
    val r = get(secretPath("acme"))
    assertEquals(r.status, 200)
    assertEquals(r.body, "sk-acme-1")
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
    assertEquals(secretRows("overwritten").size, 1)
  }

  test("secret.delete") {
    post(secretPath("removed"), "sk-gone"): Unit
    assertEquals(delete(secretPath("removed")).status, 204)
    assertEquals(get(secretPath("removed")).status, 404)
    assertEquals(secretRows("removed"), Vector.empty)
  }

  test("secret.stored-encrypted") {
    val value = "sk-conformance-91c2e"
    post(secretPath("encrypted"), value): Unit
    val hex   = value.getBytes("UTF-8").map(b => f"${b & 0xff}%02x").mkString
    val leaks = everyRow().filter((_, row) => row.contains(value) || row.contains(hex))
    assertEquals(leaks.map(_._1).distinct, Vector.empty)
    assertEquals(secretRows("encrypted").size, 1)
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
