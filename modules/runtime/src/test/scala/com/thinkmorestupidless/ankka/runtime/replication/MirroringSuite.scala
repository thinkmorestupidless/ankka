package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.runtime.EntityProtocol
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Keep, Source, SourceQueueWithComplete}

import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

/**
 * A follower persists the home's records as they came, in order, and nothing else; where a copy
 * stands follows from the records it holds (feature 051, T020 and T044).
 */
class MirroringSuite extends CopiesSuite:
  import Copy.*
  import EntityProtocol.ReplicateAck.{Persisted, Refused}

  test("records handed over in order are persisted byte for byte") {
    val c = copy("leipzig")
    val records = Vector(home("vienna"), set("vienna", Set("vienna", "leipzig"))) ++
      Vector("frame", "wheels", "doors").map(step("vienna", _))
    records.zipWithIndex.foreach((r, i) => c.replicate(i + 1L, r))
    (1L to 5L).foreach(n => c.acks.expectMessage(Persisted(n)))
    assertEquals(c.journal.map(fields), records.map(fields))
  }

  test("a record from before replication is stamped with its source and otherwise untouched") {
    val c         = copy("leipzig")
    val unstamped = step("vienna", "frame").at(None)
    c.replicate(1, unstamped)
    c.acks.expectMessage(Persisted(1))
    val held = c.journal.head
    assertEquals(held.installation, Some("vienna"))
    assertEquals(
      (held.kind, held.manifest, held.payload.toSeq),
      (unstamped.kind, unstamped.manifest, unstamped.payload.toSeq)
    )
  }

  test("a record that is not the next is refused, and nothing is persisted") {
    val c = copy("leipzig")
    c.follow("vienna", Set("vienna", "leipzig"), "frame", "wheels", "doors")
    c.replicate(7, step("vienna", "roof"))
    val refused = c.acks.expectMessageType[Refused]
    assert(refused.reason.contains("not the next record"), refused.reason)
    assertEquals(c.journal.size, 5)
  }

  test("a record the home did not write is refused") {
    // The task names an installation outside the set; what the copy checks is the writer against
    // the home, which also refuses a member of the set that is not the home.
    val c = copy("leipzig")
    c.follow("vienna", Set("vienna", "leipzig", "prague"), "frame")
    c.replicate(4, step("prague", "wheels"), source = "prague")
    val refused = c.acks.expectMessageType[Refused]
    assert(refused.reason.contains("written by 'prague'"), refused.reason)
    assertEquals(c.journal.size, 3)
  }

  test("a wake at a copy holding nothing locates once and pulls when the set names it") {
    val t      = ScriptedTransport()
    val answer = Promise[Option[Located]]()
    t.located = () => answer.future
    val c = copy("leipzig", t)
    // Two wakes while the first locate is unanswered: one locate.
    c.ref ! EntityProtocol.Wake("vienna", 3, joined = true)
    c.ref ! EntityProtocol.Wake("vienna", 3, joined = true)
    eventually()(Option.when(t.locates.get == 1)(()))
    answer.success(Some(Located("vienna", Set("vienna", "leipzig"))))
    eventually()(t.openedNow.headOption)
    assertEquals(t.openedNow, Vector("vienna" -> 1L))
    assertEquals(t.locates.get, 1)
  }

  test("a wake at a copy the set does not name locates and pulls nothing") {
    val t = ScriptedTransport()
    t.located = () => Future.successful(Some(Located("vienna", Set("vienna", "prague"))))
    val c = copy("leipzig", t)
    c.ref ! EntityProtocol.Wake("vienna", 3, joined = true)
    eventually()(Option.when(t.locates.get == 1)(()))
    Thread.sleep(500)
    assertEquals(t.openedNow, Vector.empty)
  }

  test("a record handed to the home is refused") {
    val c = copy("leipzig")
    c.invoke("add-step", "frame")
    assertEquals(text(c.replies.receiveMessage()), "frame")
    // The first write created the entity here: home, set, step.
    assertEquals(c.journal.map(_.kind), Vector(3, 4, 0))
    c.replicate(4, step("vienna", "wheels"))
    val refused = c.acks.expectMessageType[Refused]
    assert(refused.reason.contains("this installation is the home"), refused.reason)
  }

  test("a write at a follower is not handled there") {
    val c = copy("leipzig")
    c.follow("vienna", Set("vienna", "leipzig"), "frame")
    c.invoke("add-step", "wheels")
    // Forwarded to the home, which has not answered: nothing is written here.
    eventually()(Option.when(!c.transport.forwarded.isEmpty)(()))
    c.replies.expectNoMessage(300.millis)
    assertEquals(c.journal.size, 3)
  }

  test("a home and set naming this installation make it the home") {
    val c = copy("leipzig")
    // vienna's history, ending with the record that hands the home to this installation.
    c.follow("vienna", Set("vienna", "leipzig"))
    c.replicate(3, home("leipzig", writer = "vienna"))
    c.acks.expectMessage(Persisted(3))
    c.invoke("add-step", "frame")
    assertEquals(text(c.replies.receiveMessage()), "frame")
    assertEquals(c.journal.last.installation, Some("leipzig"))
    assert(c.transport.forwarded.isEmpty)
  }

  test("a set without this installation puts it outside: a read is refused naming the home") {
    val c = copy("leipzig")
    c.follow("vienna", Set("vienna"), "frame")
    c.invoke("get")
    val refused = rejected(c.replies.receiveMessage())
    assertEquals(refused.code, ErrorCode.NotHome)
    assert(refused.message.contains("outside the replication set"), refused.message)
    assert(refused.message.contains("vienna"), refused.message)
  }

  // ── T044: the replication set changes ───────────────────────────────────

  test("an installation added to the set pulls from the first record and ends identical") {
    val home = copy("leipzig")
    home.invoke("add-step", "frame")
    home.replies.receiveMessage()
    home.invoke("replicate-to", "prague")
    assertEquals(text(home.replies.receiveMessage()), "prague")
    home.invoke("add-step", "wheels")
    home.replies.receiveMessage()
    assertEquals(home.journal.map(_.kind), Vector(3, 4, 0, 4, 0))

    // Prague's transport reads the home's journal from where it is asked.
    val t = ScriptedTransport()
    t.located = () => Future.successful(Some(Located("leipzig", Set("leipzig", "prague"))))
    t.source = (_, from) =>
      Source(home.journal.zipWithIndex.map((r, i) => Replicated(i + 1L, r)).drop((from - 1).toInt))
        .concat(Source.never)
    val added = copy("prague", t)
    added.ref ! EntityProtocol.Wake("leipzig", 5, joined = true)
    eventually()(Option.when(added.journal.size == 5)(()))
    assertEquals(added.journal.map(fields), home.journal.map(fields))
    assertEquals(t.openedNow.head, "leipzig" -> 1L)
  }

  test("an installation removed from the set stops pulling, and its reads name the home") {
    val t                                                            = ScriptedTransport()
    @volatile var queue: Option[SourceQueueWithComplete[Replicated]] = None
    val ended                                                        = Promise[Unit]()
    t.source = (_, _) =>
      Source
        .queue[Replicated](16, OverflowStrategy.fail)
        .watchTermination()(Keep.both)
        .mapMaterializedValue { (q, done) =>
          queue = Some(q)
          done.onComplete(_ => ended.trySuccess(()))(using
            scala.concurrent.ExecutionContext.parasitic
          )
          org.apache.pekko.NotUsed
        }
    val c = copy("leipzig", t)
    c.follow("vienna", Set("vienna", "leipzig"), "frame")
    // Following vienna since its set record: its puller was opened from the record after it.
    val q = eventually()(queue)
    assertEquals(t.openedNow, Vector("vienna" -> 3L))
    q.offer(Replicated(4, set("vienna", Set("vienna"))))
    eventually()(Option.when(c.journal.size == 4)(()))
    scala.concurrent.Await.result(ended.future, 10.seconds)
    c.invoke("get")
    val refused = rejected(c.replies.receiveMessage())
    assertEquals(refused.code, ErrorCode.NotHome)
    assert(refused.message.contains("vienna"), refused.message)
  }

  test("the home cannot remove itself from the replication set") {
    val c = copy("leipzig")
    c.invoke("add-step", "frame")
    c.replies.receiveMessage()
    c.invoke("stop-replicating-to", "leipzig")
    val refused = rejected(c.replies.receiveMessage())
    assertEquals(refused.code, ErrorCode.BadRequest)
    assert(refused.message.contains("hand the home over first"), refused.message)
    assertEquals(c.journal.size, 3)
  }
