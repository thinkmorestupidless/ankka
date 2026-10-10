package shoppingcart

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.{InMemoryPublisher, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.application.{CartRows, CheckoutNotifier, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Views and consumers against real projections.
 *
 * Projections are asynchronous, so every assertion here polls to a deadline rather than reading
 * once. That is not test hygiene papering over a race — it is the actual consistency model, and a
 * test that pretended otherwise would be testing a system ankka does not provide.
 */
class CartViewSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val publisher             = InMemoryPublisher()

  override def beforeAll(): Unit =
    // docs:start start
    testKit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CartRows.descriptor, CheckoutNotifier.descriptor),
      Seq(ProjectionRuntime.withPublisher(publisher))
    )
    // docs:end start

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def cart(id: String) =
    testKit.componentClient.forEventSourcedEntity(EntityId(id))

  // docs:start for-view
  private def rows = testKit.service.viewClient.forView(CartRows)
  // docs:end for-view

  /** Polls until `check` returns a value or the deadline passes. */
  private def eventually[A](
      description: String,
      within: FiniteDuration = 30.seconds
  )(check: => Option[A]): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("events reach the view as a queryable row") {
    val id = "view-basic"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 5))

    val row = eventually("the cart row appears with both products") {
      rows.get(id).filter(_.quantities.size == 2)
    }
    assertEquals(row.cartId, id)
    assertEquals(row.totalQuantity, 7)
    assertEquals(row.productIds, List("p1", "p2"))
    assert(!row.checkedOut)
  }

  test("the view folds repeated adds the way the entity does") {
    val id = "view-fold"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 3))

    val row = eventually("quantities are folded") {
      rows.get(id).filter(_.totalQuantity == 5)
    }
    assertEquals(row.quantities, Map("p1" -> 5))

    // The projection must agree with the entity, which is the whole point of a view.
    assertEquals(row.totalQuantity, cart(id).call(ShoppingCartEntity.totalQuantity).invoke())
  }

  test("a removal is projected too") {
    val id = "view-remove"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1))
    val _  = eventually("both products land")(rows.get(id).filter(_.quantities.size == 2))

    val _   = cart(id).call(ShoppingCartEntity.removeItem).invoke("p1")
    val row = eventually("the removal lands")(rows.get(id).filter(!_.quantities.contains("p1")))
    assertEquals(row.productIds, List("p2"))
  }

  test("rows can be queried by a field inside the payload") {
    val _ = cart("view-q-1").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _ = cart("view-q-2").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _ = eventually("both rows exist") {
      Option.when(rows.get("view-q-1").isDefined && rows.get("view-q-2").isDefined)(())
    }

    // docs:start where
    // Query by an attribute rather than by key — the reason views exist.
    val found = rows.where(jsonText("cartId") ++ sql" = ${"view-q-1"}")
    // docs:end where
    assertEquals(found.map(_.cartId), Vector("view-q-1"))
  }

  test("a checked-out cart's row is marked checked out and keeps what it held") {
    val id = "view-checked-out"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 4))
    val _  = eventually("the row exists")(rows.get(id))

    val _ = cart(id).call(ShoppingCartEntity.checkout).invoke()

    val row = eventually("the row is marked checked out")(rows.get(id).filter(_.checkedOut))
    assertEquals(row.totalQuantity, 4)
    // The cart itself is kept too.
    assert(cart(id).call(ShoppingCartEntity.getCart).invoke().checkedOut)
  }

  test("a discarded cart leaves the listing") {
    val id = "view-discarded"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 4))
    val _  = eventually("the row exists")(rows.get(id))

    val _ = cart(id).call(ShoppingCartEntity.discard).invoke()

    // The deletion runs the view's default handler, which removes the row. Retried on the row going,
    // since the projection follows the journal.
    val _ = eventually("the row is removed")(Option.when(rows.get(id).isEmpty)(()))
  }

  test("count and all see every projected row") {
    val _ = eventually("at least the rows written by earlier tests exist") {
      Option.when(rows.count() > 0L)(())
    }
    // docs:start count
    assert(rows.count() >= 1L)
    assert(rows.all().nonEmpty)
    assert(rows.count(jsonText("cartId") ++ sql" = ${"nope-does-not-exist"}") == 0L)
    // docs:end count
  }

  test("a consumer publishes only the events it selected") {
    val id = "consume-checkout"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _  = cart(id).call(ShoppingCartEntity.checkout).invoke()

    val published = eventually("the checkout notice is published") {
      Option(publisher.publishedTo("cart-checkouts").filter(_.text.contains(id)))
        .filter(_.nonEmpty)
    }
    assertEquals(published.size, 1)
    assert(published.head.text.contains(s"\"cartId\":\"$id\""), published.head.text)

    // ce-subject carries the entity id, so per-cart ordering survives on a broker.
    assertEquals(published.head.metadata.subject, Some(id))

    // ItemAdded was ignored, so nothing about it was published.
    assert(
      !publisher.publishedTo("cart-checkouts").exists(_.text.contains("Widget")),
      "ignored events must not be published"
    )
  }

  test("a watcher of the open carts is given them, then told it is caught up, then each change") {
    import com.thinkmorestupidless.ankka.sdk.WatchEvent
    import org.apache.pekko.stream.scaladsl.Sink
    given org.apache.pekko.stream.Materializer =
      org.apache.pekko.stream.Materializer(testKit.service.system)
    val id = "watched-open"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _ =
      eventually("the cart is in the view")(testKit.service.viewClient.forView(CartRows).get(id))
    val events =
      java.util.concurrent.LinkedBlockingQueue[WatchEvent[shoppingcart.application.CartRow]]()
    val running = testKit.service.viewClient
      .forView(CartRows)
      .watch(CartRows.openCarts)
      .filter {
        case WatchEvent.Row(key, _)  => key == id
        case WatchEvent.Removed(key) => key == id
        case WatchEvent.CaughtUp     => true
      }
      .take(4)
      .runWith(Sink.foreach(events.put))
    def next() = Option(events.poll(30, java.util.concurrent.TimeUnit.SECONDS))
    assertEquals(next().collect { case WatchEvent.Row(_, row) => row.productIds }, Some(List("p1")))
    assertEquals(next(), Some(WatchEvent.CaughtUp))
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1))
    assertEquals(
      next().collect { case WatchEvent.Row(_, row) => row.productIds },
      Some(List("p1", "p2"))
    )
    val _ = cart(id).call(ShoppingCartEntity.checkout).invoke()
    assertEquals(next(), Some(WatchEvent.Removed(id)))
    scala.concurrent.Await.result(running, 10.seconds): Unit
  }

  test("every row of the view is given as a stream, past the limit of a whole answer") {
    import org.apache.pekko.stream.scaladsl.Sink
    given org.apache.pekko.stream.Materializer =
      org.apache.pekko.stream.Materializer(testKit.service.system)
    val id = "streamed"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _  = eventually("the cart is in the view")(rows.get(id))
    // docs:start stream
    // Every row, as the database yields it: nothing is collected, and there is no limit.
    val everyCart = rows.allStream().runWith(Sink.seq)
    // docs:end stream
    val streamed = scala.concurrent.Await.result(everyCart, 30.seconds)
    assert(streamed.exists(_.cartId == id), s"$streamed")
    assertEquals(streamed.size.toLong, rows.count())
  }
