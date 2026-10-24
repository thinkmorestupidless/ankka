package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.bridge.{Adapters, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken.ReplicatedNote
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, EventSourcedTestKit}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.Duration
import java.util.List as JList
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * A replicated Java entity: declared by annotation, its copies driven as home and follower in the
 * component kit, its events' contexts read in `applyEvent`, and a group of installations holding
 * it, read and handed over through the Java component client.
 */
class ReplicationSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def entries(state: Any): Vector[String] =
    state.asInstanceOf[Ledger.State].entries.asScala.toVector

  test("@Replicated and @SnapshotEvery reach the Scala companion the runtime hosts") {
    val read      = JavaServiceBuilder.read(Vector(classOf[Ledger] -> None), Map.empty)
    val companion = Adapters.eventSourcedCompanion(read.plans.head, read.refs)
    assert(companion.replicated, "replicated")
    assertEquals(companion.snapshotEvery, None)
    val plain = JavaServiceBuilder.read(
      Vector(classOf[com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart] -> None),
      Map.empty
    )
    val cart = Adapters.eventSourcedCompanion(plain.plans.head, plain.refs)
    assert(!cart.replicated, "a cart is not replicated")
    assertEquals(cart.snapshotEvery, Some(100))
  }

  test("a component other than an event sourced entity declared replicated is refused") {
    val e = intercept[ServiceProblems](
      Ankka.service("notes").register(classOf[ReplicatedNote]).start()
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("only an event sourced entity can be"), messages)
    assert(messages.contains("snapshots every -5 events"), messages)
  }

  test("at its home a copy runs every command, and reports the change to its set it asks for") {
    val home    = EventSourcedTestKit.home(classOf[Ledger], "l1", "a")
    val entered = home.call(KindRefs.enter, "first")
    assertEquals(entries(entered.state), Vector("first@a"))
    val shared = home.call(KindRefs.share, "b")
    assertEquals(shared.replicatedTo.asScala.toSet, Set("b"))
    assertEquals(home.call(KindRefs.unshare, "b").stoppedReplicatingTo.asScala.toSet, Set("b"))
    val handed = home.call(KindRefs.enterAndHandOver, "b")
    assertEquals(handed.handover.toScala, Some("b"))
    assertEquals(handed.events.size, 1)
    assertEquals(home.call(KindRefs.where).reply(), "a")
  }

  test("a follower is refused a command that may persist, and answers from what it was given") {
    val follower = EventSourcedTestKit.follower(classOf[Ledger], "l2", "a", "b")
    follower.replay("a", JList.of(Ledger.Entered("from a")))
    follower.replay(JList.of(Ledger.Entered("from here")))
    assertEquals(
      entries(follower.call(KindRefs.ledger).reply()),
      Vector("from a@a (mirrored)", "from here@b")
    )
    val refused = follower.call(KindRefs.enter, "not here")
    assertEquals(refused.problem.status, 421, refused.error)
  }

  test("a handler that answers no reply, and one that persists then answers none") {
    val kit    = EventSourcedTestKit.home(classOf[Ledger], "l3", "a")
    val silent = kit.call(KindRefs.ignore)
    assert(!silent.isError)
    intercept[AssertionError](silent.reply()): Unit
    val forgotten = kit.call(KindRefs.forget)
    assertEquals(forgotten.events.size, 1)
    intercept[AssertionError](forgotten.reply()): Unit
  }

  test("an unreplicated entity's events were written nowhere in particular, and here") {
    val kit = EventSourcedTestKit.of(classOf[Ledger], "l4")
    assertEquals(entries(kit.call(KindRefs.enter, "x").state), Vector("x@nowhere"))
  }

  test(
    "a group of installations mirrors the home's events, reports its status and hands it over"
  ) {
    val group = AnkkaTestKit.installations("a", "b")
    try
      def service = Ankka.service("ledgers").register(classOf[Ledger])
      val a       = group.start("a", service)
      val b       = group.start("b", service)
      val atA     = a.componentClient().forEventSourcedEntity("shared")
      val atB     = b.componentClient().forEventSourcedEntity("shared")
      atA.call(KindRefs.share).invoke("b"): Unit
      atA.call(KindRefs.enter).invoke("first"): Unit
      b.eventually(
        Duration.ofSeconds(30),
        () =>
          val status = atB.replication(classOf[Ledger])
          assertEquals(status.home.toScala, Some("a"))
          assertEquals(status.replicationSet.asScala.toSet, Set("a", "b"))
          assertEquals(entries(atB.call(KindRefs.ledger).invoke()), Vector("first@a (mirrored)"))
      )
      atA.call(KindRefs.handOver).invoke("b"): Unit
      // The old home records the handover once the new one holds everything before it.
      atA.awaitHandover(classOf[Ledger], "b", Duration.ofSeconds(30))
      assertEquals(atA.replication(classOf[Ledger]).home.toScala, Some("b"))
      b.eventually(
        Duration.ofSeconds(30),
        () => assertEquals(atB.replication(classOf[Ledger]).home.toScala, Some("b"))
      )
    finally group.close()
  }
