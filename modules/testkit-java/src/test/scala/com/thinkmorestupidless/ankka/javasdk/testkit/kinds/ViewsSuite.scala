package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.cart.{LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.client.CallerError
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken.BrokenViews
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.testkit as stk
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.util.concurrent.{CompletableFuture, Flow, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Java views and keyed views, hosted by the Scala view runtime under the Scala `AnkkaTestKit`: the
 * facade builds the descriptors and the runtime does what it does for a Scala view.
 */
class ViewsSuite extends munit.FunSuite with LogCapturing:

  private val read = JavaServiceBuilder.read(
    Vector(
      classOf[ShoppingCart],
      classOf[TreeNode],
      classOf[Nodes],
      classOf[CartRows],
      classOf[ProductCarts]
    ).map(_ -> None),
    Map.empty
  )

  private var kit: stk.AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = stk.AnkkaTestKit.start(read.descriptors, extensions = read.platformExtensions)

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def client = Clients.of(kit.componentClient, read.refs)
  private def views  = read.viewClient(kit.service.viewClient)
  private def nodes  = views.forView(classOf[Nodes])

  private def place(id: String, name: String, under: String = ""): Unit =
    client.forKeyValueEntity(id).call(KindRefs.place).invoke(TreeNode.Placed(name, under)): Unit

  private def eventually(what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + 20.seconds.toNanos
    while !check do
      if System.nanoTime() > deadline then fail(s"never: $what")
      Thread.sleep(100)

  private def collect[A](publisher: Flow.Publisher[A]): Vector[A] =
    val done = CompletableFuture[Vector[A]]()
    publisher.subscribe(new Flow.Subscriber[A]:
      private val got                             = Vector.newBuilder[A]
      def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
      def onNext(item: A): Unit                   = got += item
      def onError(t: Throwable): Unit             = done.completeExceptionally(t): Unit
      def onComplete(): Unit                      = done.complete(got.result()): Unit)
    done.get(20, TimeUnit.SECONDS)

  test("a row per source id, removed when the source is deleted") {
    place("row-1", "one")
    place("row-2", "two")
    eventually("both rows")(nodes.get("row-1").isPresent && nodes.get("row-2").isPresent)
    assertEquals(nodes.get("row-1").get, Nodes.Row("row-1", "one", ""))
    client.forKeyValueEntity("row-1").call(KindRefs.remove).invoke(): Unit
    eventually("the deleted source's row is gone")(nodes.get("row-1").isEmpty)
    assert(nodes.get("row-2").isPresent)
  }

  test("a declared recursive query finds every row under one, to any depth") {
    place("t-root", "root")
    place("t-a", "a", under = "t-root")
    place("t-b", "b", under = "t-a")
    place("t-c", "c", under = "t-b")
    place("t-other", "other")
    def under = nodes.ask(Nodes.UNDER, Map("row" -> "t-root").asJava).asScala.map(_.id).toSet
    eventually("three rows under the root")(under == Set("t-a", "t-b", "t-c"))
    val limited = nodes.ask(Nodes.UNDER, 2, Map("row" -> "t-root").asJava)
    assertEquals(limited.size, 2)
  }

  test("each query has a form that does not wait, answered as the waiting one is") {
    place("as-1", "async")
    place("as-2", "async")
    eventually("both rows")(nodes.get("as-2").isPresent && nodes.get("as-1").isPresent)
    def now[A](stage: java.util.concurrent.CompletionStage[A]): A =
      stage.toCompletableFuture.get(20, TimeUnit.SECONDS)
    assertEquals(now(nodes.getAsync("as-1")).get, Nodes.Row("as-1", "async", ""))
    assert(now(nodes.getAsync("nowhere")).isEmpty)
    assertEquals(now(nodes.countAsync()).longValue, nodes.count())
    assertEquals(now(nodes.allAsync(1000)).size, nodes.all().size)
    val named = now(nodes.askAsync(Nodes.NAMED, Map("name" -> "async").asJava))
    assertEquals(named.asScala.map(_.id).toSet, Set("as-1", "as-2"))
  }

  test("a declared query's rows as a stream, read as the subscriber asks") {
    place("s-1", "streamed")
    place("s-2", "streamed")
    eventually("both rows")(nodes.get("s-2").isPresent && nodes.get("s-1").isPresent)
    val rows = collect(nodes.askStream(Nodes.NAMED, Map("name" -> "streamed").asJava))
    assertEquals(rows.map(_.id).toSet, Set("s-1", "s-2"))
  }

  test("a view over an entity's events folds them, and a declared query reads the fold") {
    val carts = views.forView(classOf[CartRows])
    client.forEventSourcedEntity("cart-1").call(KindRefs.addItem).invoke(LineItem("p1", "Pen", 1))
    client.forEventSourcedEntity("cart-1").call(KindRefs.addItem).invoke(LineItem("p2", "Ink", 1))
    client.forEventSourcedEntity("cart-2").call(KindRefs.addItem).invoke(LineItem("p1", "Pen", 1))
    client.forEventSourcedEntity("cart-2").call(KindRefs.checkout).invoke()
    eventually("cart-2 checked out")(carts.get("cart-2").map(_.checkedOut).orElse(false))
    eventually("cart-1 has two lines")(carts.get("cart-1").map(_.lines).orElse(0) == 2)
    val open = carts.ask(CartRows.OPEN, Map.empty[String, String].asJava).asScala.map(_.cartId)
    assert(open.contains("cart-1") && !open.contains("cart-2"), open.toString)
    assert(carts.count() >= 2)
  }

  test("a view's declared version is the version the runtime records for it") {
    client.forEventSourcedEntity("cart-v").call(KindRefs.addItem).invoke(LineItem("p", "P", 1))
    val db       = Database()(using kit.service.system)
    def recorded = Await.result(
      db.queryOne(
        SqlFragment.raw("SELECT version FROM ankka_view_versions WHERE component_id = ") ++
          sql"${"cart-rows"}"
      )(_.get("version", classOf[Integer]).intValue),
      10.seconds
    )
    eventually("version 2 recorded")(recorded == Some(2))
  }

  test("a keyed view reads two sources, reads its own rows, and handles a deletion") {
    val products = views.forKeyedView(classOf[ProductCarts])
    client.forEventSourcedEntity("k-1").call(KindRefs.addItem).invoke(LineItem("k-pen", "Pen", 1))
    client.forEventSourcedEntity("k-2").call(KindRefs.addItem).invoke(LineItem("k-pen", "Pen", 1))
    eventually("two carts added the pen")(products.get("k-pen").map(_.count).orElse(0) == 2)
    place("k-node", "named")
    eventually("the node's row")(products.get("node:k-node").isPresent)
    assertEquals(products.get("node:k-node").get.key, "named")
    client.forKeyValueEntity("k-node").call(KindRefs.remove).invoke(): Unit
    eventually("the node's row is deleted with it")(products.get("node:k-node").isEmpty)
  }

  test("a view the service does not register is refused by the client, naming it") {
    val e = intercept[CallerError](views.forView(classOf[BrokenViews.Twice]))
    assert(e.getMessage.contains("BrokenViews$Twice"), e.getMessage)
  }

  test("a view declared wrongly stops the start, saying what is wrong with each") {
    val e = intercept[ServiceProblems](
      JavaServiceBuilder.read(
        Vector(classOf[Nodes], classOf[BrokenViews.Twice], classOf[BrokenViews.Misshapen])
          .map(_ -> None),
        Map.empty
      )
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("reads TreeNode, which the service does not register"), messages)
    assert(messages.contains("declares the query 'same' 2 times"), messages)
    assert(messages.contains("takes the change, or the change and a KeyedChange"), messages)
    assert(messages.contains("by a class or by a topic: exactly one"), messages)
  }
