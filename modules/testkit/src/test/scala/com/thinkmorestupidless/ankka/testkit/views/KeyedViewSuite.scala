package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/**
 * A keyed view against a real database, for what its features cannot say: that it handles one
 * change at a time on two instances and loses no write (SC-006), that its row count is the keys it
 * named across a restart (SC-003), that a change's rows are written together or not at all (E3,
 * E4), and that a handler that fails, waits too long or writes too much fails only its own change,
 * which is handled again (E1, E6, O3, FR-014).
 *
 * Every event carries a script (`KeyedKit`) saying what the view does with it, and every case uses
 * keys of its own, so no case reads another's rows.
 */
class KeyedViewSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 10.minutes

  private given ExecutionContext = ExecutionContext.global

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(
        Shipment.descriptor,
        Customer.descriptor,
        Supplier.descriptor,
        Account.descriptor,
        NodeEntity.descriptor,
        Nodes.descriptor,
        Shipments.descriptor,
        ThreeSourced.descriptor,
        AccountShipments.descriptor
      ),
      Seq(ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def record(entity: ScriptedCompanion, id: String, ops: Scripts.Op*): String =
    val script = Scripts.add(Scripts.Script(s"from-$id", ops.toVector))
    kit.componentClient.forEventSourcedEntity(EntityId(id)).call(entity.record).invoke(script)
    script

  private def shipments = kit.service.viewClient.forView(Shipments)

  private def row(key: String) = shipments.get(key)

  test(
    "R3: a keyed view handles a change, its lock taken through the projection's own transaction"
  ) {
    record(Shipment, "r3-s1", Scripts.Op.Touch(Vector("r3-s1"), Some("r3-c1")))
    val found = kit.eventually("the row r3-s1")(row("r3-s1"))
    assertEquals(found.customer, Some("r3-c1"))
    assertEquals(found.notes, Vector("from-r3-s1"))
  }

  test("a keyed view of three sources writes rows from all three") {
    val three = kit.service.viewClient.forView(ThreeSourced)
    record(Shipment, "t3-s", Scripts.Op.Touch(Vector("t3-1")))
    record(Customer, "t3-c", Scripts.Op.Touch(Vector("t3-2")))
    record(Supplier, "t3-p", Scripts.Op.Touch(Vector("t3-3")))
    kit.eventually("three rows")(
      Option.when(Seq("t3-1", "t3-2", "t3-3").forall(k => three.get(k).isDefined))(())
    )
  }

  test("SC-006: two sources each writing one row a thousand times on two instances lose no write") {
    val peer = kit.startPeer(Seq(ProjectionRuntime()))
    try
      val count = Scripts.add(Scripts.Script("count", Vector(Scripts.Op.Count("sc6"))))
      def thousand(
          entity: ScriptedCompanion,
          id: String,
          client: com.thinkmorestupidless.ankka.sdk.ComponentClient
      ) =
        Future(
          (1 to 1000).foreach(_ =>
            client.forEventSourcedEntity(EntityId(id)).call(entity.record).invoke(count)
          )
        )
      Await.result(
        Future.sequence(
          Seq(
            thousand(Shipment, "sc6-s", kit.componentClient),
            thousand(Customer, "sc6-c", peer.componentClient)
          )
        ),
        5.minutes
      )
      val counted =
        kit.eventually("2000 counted", 5.minutes)(row("sc6").filter(_.count >= 2000).map(_.count))
      Thread.sleep(1000)
      assertEquals(row("sc6").map(_.count), Some(2000), s"counted $counted")
    finally peer.stop()
  }

  test("SC-003: the rows are exactly the keys named, before a restart and after it") {
    record(Shipment, "sc3-a", Scripts.Op.Touch(Vector("sc3-1", "sc3-2")))
    record(Customer, "sc3-b", Scripts.Op.Touch(Vector("sc3-2", "sc3-3")))
    record(Shipment, "sc3-a", Scripts.Op.Delete(Vector("sc3-1")))
    def keys = shipments.all(10000).map(_.key).filter(_.startsWith("sc3-")).toSet
    kit.eventually("the rows sc3-2 and sc3-3")(Option.when(keys == Set("sc3-2", "sc3-3"))(()))
    kit.restartService()
    record(Customer, "sc3-b", Scripts.Op.Touch(Vector("sc3-4")))
    kit.eventually("sc3-4 after the restart")(
      Option.when(keys == Set("sc3-2", "sc3-3", "sc3-4"))(())
    )
    // Written by both sources; the order between two sources is not defined.
    assertEquals(row("sc3-2").map(_.notes.toSet), Some(Set("from-sc3-a", "from-sc3-b")))
  }

  test("E3: a key value source's change writes its rows together") {
    val script =
      Scripts.add(Scripts.Script("from-account", Vector(Scripts.Op.Touch(Vector("e3-1", "e3-2")))))
    kit.componentClient.forKeyValueEntity(EntityId("e3")).call(Account.record).invoke(script)
    val accounts = kit.service.viewClient.forView(AccountShipments)
    kit.eventually("both rows")(
      Option.when(accounts.get("e3-1").isDefined && accounts.get("e3-2").isDefined)(())
    )
  }

  test(
    "E4: a change naming a row that cannot be written writes none of its rows, and is handled again"
  ) {
    record(Shipment, "e4-s", Scripts.Op.Touch(Vector("e4-1")))
    kit.eventually("e4-1")(row("e4-1"))
    val failing = record(
      Shipment,
      "e4-s",
      Scripts.Op.Touch(Vector("e4-1")),
      Scripts.Op.ForAttempts(2, Scripts.Op.Unwritable("e4-2"))
    )
    kit.eventually("a second attempt")(Option.when(Scripts.attemptsOf(failing) >= 2)(()))
    // Neither row of a refused attempt was written.
    assertEquals(row("e4-1").map(_.notes), Some(Vector("from-e4-s")))
    assertEquals(row("e4-2"), None)
    // Once the row can be written, the change is applied whole.
    kit.eventually("the third attempt written", 2.minutes)(
      row("e4-1").filter(_.notes.size == 2)
    )
  }

  test("E1: an empty row key fails the change, which is handled again") {
    val failing = record(
      Shipment,
      "e1-s",
      Scripts.Op.ForAttempts(1, Scripts.Op.Touch(Vector(""))),
      Scripts.Op.Touch(Vector("e1-1"))
    )
    kit.eventually("handled again", 2.minutes)(row("e1-1"))
    assert(Scripts.attemptsOf(failing) >= 2)
    assertEquals(row(""), None)
  }

  test("E6: rows weighing more than one change may carry fail the change, and none is written") {
    val failing = record(
      Shipment,
      "e6-s",
      Scripts.Op.ForAttempts(1, Scripts.Op.Big(80)),
      Scripts.Op.Touch(Vector("e6-1"))
    )
    kit.eventually("handled again", 2.minutes)(row("e6-1"))
    assertEquals(row(s"$failing-big-1"), None)
  }

  test("FR-014: a handler asking another view's query through its own rows fails its change") {
    val failing = record(
      Shipment,
      "f14-s",
      Scripts.Op.ForAttempts(1, Scripts.Op.AskOther),
      Scripts.Op.Touch(Vector("f14-1"))
    )
    kit.eventually("handled again", 2.minutes)(row("f14-1"))
    assert(Scripts.attemptsOf(failing) >= 2)
  }

  test("O3: a handler waiting on a read that never ends fails its change, and the view goes on") {
    val failing = record(
      Shipment,
      "o3-s",
      Scripts.Op.ForAttempts(1, Scripts.Op.AskForever),
      Scripts.Op.Touch(Vector("o3-1"))
    )
    kit.eventually("handled again", 2.minutes)(row("o3-1"))
    assert(Scripts.attemptsOf(failing) >= 2)
    record(Customer, "o3-c", Scripts.Op.Touch(Vector("o3-2")))
    kit.eventually("the next change")(row("o3-2"))
  }

  test("the view handled every change one at a time") {
    // Over everything this suite did, view by view: no handler began before the one before it ended.
    val log = Scripts.log.asScala.toVector.filter(_.split(' ')(1) == "shipments")
    val overlapping = log
      .sliding(2)
      .collect {
        case Vector(a, b) if a.startsWith("begin") && b.startsWith("begin") => (a, b)
      }
      .toVector
    assertEquals(overlapping, Vector.empty)
  }
