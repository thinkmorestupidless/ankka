package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.cart.{LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TimerRuns}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.{CompletableFuture, Flow, TimeUnit}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * What the Java whole-service kit gives a test beyond calls: a workflow's end as server-sent
 * events, what the service's timers did, and a broker in memory for a consumer that publishes.
 */
class WholeServiceKitSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka
        .service("kit")
        .register(classOf[ShoppingCart])
        .register(classOf[CartLines])
        .register(classOf[Checkout])
        .register(classOf[CartTimers])
        .withEndpoint(classOf[CheckoutEvents])
    )

  override def afterAll(): Unit = if kit != null then kit.close()

  private def start(id: String, cart: String): Unit =
    kit
      .componentClient()
      .forWorkflow(id)
      .call(KindRefs.startCheckout)
      .invoke(Checkout.Start(cart, 300)): Unit

  test("a route returning a wait for a workflow's end sends the one event that ends it") {
    start("sse-1", "cart-sse")
    val response = kit
      .httpClient()
      .send(
        HttpRequest.newBuilder(URI.create(s"${kit.http()}/checkouts/sse-1/end")).build(),
        HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(response.statusCode(), 200, response.body)
    assert(response.body.contains("event:ended"), response.body)
    assert(response.body.contains("expired"), response.body)
  }

  test("the same wait read by a subscriber of its own ends with the state, or the failure") {
    start("sse-2", "cart-sub")
    def read(id: String): Vector[SseEvent] =
      val events = ConcurrentLinkedQueue[SseEvent]()
      val done   = CompletableFuture[Unit]()
      kit
        .componentClient()
        .forWorkflow(id)
        .awaitEndEvents(classOf[Checkout], Duration.ofSeconds(30))
        .subscribe(new Flow.Subscriber[SseEvent]:
          def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
          def onNext(e: SseEvent): Unit               = events.add(e): Unit
          def onError(t: Throwable): Unit             = done.completeExceptionally(t): Unit
          def onComplete(): Unit                      = done.complete(()): Unit)
      done.get(40, TimeUnit.SECONDS)
      events.asScala.toVector
    val ended = read("sse-2")
    assertEquals(ended.last.name.get, "ended")
    assert(ended.last.json.get.contains("expired"), ended.last.json.get)
    kit
      .componentClient()
      .forWorkflow("sse-3")
      .call(KindRefs.startCheckout)
      .invoke(Checkout.Start("cart-gone", 60000)): Unit
    kit.componentClient().forWorkflow("sse-3").call(KindRefs.cancelCheckout).invoke(): Unit
    val failed = read("sse-3")
    assertEquals(failed.map(_.name.get).last, "failed")
    assert(failed.last.json.get.contains("deleted\":true"), failed.last.json.get)
  }

  test("a timer's runs are read from the kit: its due, its outcome, and nothing left once run") {
    kit.timers().schedule("expire-cart-9", Duration.ZERO, KindRefs.expireTimer, "cart-9")
    kit.eventually(
      Duration.ofSeconds(20),
      () => assertEquals(kit.timerRuns().dueTimes("expire-cart-9").size, 1)
    )
    val run = kit.timerRuns().fired("expire-cart-9").get(0)
    assertEquals(run.outcome, TimerRuns.Outcome.DONE)
    assert(run.next.isEmpty && run.period.isEmpty, run.toString)
    assert(kit.timerRuns().scheduled("expire-cart-9").isEmpty)
  }

  test("a recurring timer as the database holds it names its action and its period") {
    kit
      .timers()
      .scheduleRecurring("ticking", Duration.ofMinutes(5), Duration.ofMinutes(1), KindRefs.tick)
    val scheduled = kit.timerRuns().scheduled("ticking").orElseThrow()
    assertEquals(scheduled.period.get, Duration.ofMinutes(1))
    assert(scheduled.action.endsWith("/tick"), scheduled.action)
    kit.timers().cancel("ticking")
  }

  test("a consumer that publishes starts in the kit, which holds a broker in memory") {
    kit
      .componentClient()
      .forEventSourcedEntity("lines-1")
      .call(KindRefs.addItem)
      .invoke(LineItem("p1", "socks", 2)): Unit
    kit.eventually(
      Duration.ofSeconds(20),
      () =>
        val lines =
          kit.published("cart-lines").asScala.filter(_.subject.toScala.contains("lines-1"))
        assertEquals(
          lines.map(_.text).toVector,
          Vector("""{"cartId":"lines-1","what":"added p1"}""")
        )
        assertEquals(lines.head.key.toScala, Some("lines-1"))
    )
  }
