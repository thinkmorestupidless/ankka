package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.runtime.{EntityProtocol, JournalRecord}
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

/**
 * A follower's puller asks the home for its records from the one after the last the copy holds, and
 * opens again from there after any end (feature 051, T023).
 */
class PullerSuite extends CopiesSuite:
  import Copy.*

  /** vienna's history: its home and set records, then a step for each name. */
  private def history(set: Set[String], steps: Int): Vector[JournalRecord] =
    Vector(home("vienna"), Copy.set("vienna", set)) ++ (1 to steps).map(n => step("vienna", s"s$n"))

  private def numbered(records: Vector[JournalRecord], from: Long): Vector[Replicated] =
    records.zipWithIndex.map((r, i) => Replicated(i + 1L, r)).drop((from - 1).toInt)

  /** A copy at leipzig woken as one vienna's set names. */
  private def joined(t: ScriptedTransport): Copy =
    t.located = () => Future.successful(Some(Located("vienna", Set("vienna", "leipzig"))))
    val c = copy("leipzig", t)
    c.ref ! EntityProtocol.Wake("vienna", 1, joined = true)
    c

  test("a follower pulls every record the home holds") {
    val t       = ScriptedTransport()
    val records = history(Set("vienna", "leipzig"), 8)
    t.source = (_, from) => Source(numbered(records, from)).concat(Source.never)
    val c = joined(t)
    eventually()(Option.when(c.journal.size == 10)(()))
    assertEquals(c.journal.map(fields), records.map(fields))
    assertEquals(t.openedNow, Vector("vienna" -> 1L))
  }

  test("a source that fails is opened again from the record after the last one held") {
    val t                            = ScriptedTransport()
    val records                      = history(Set("vienna", "leipzig"), 8)
    val calls                        = AtomicInteger()
    @volatile var held: Option[Copy] = None
    // Fails once the copy holds the three it was handed: a failure overtakes records in flight.
    val holdsThree = Future {
      while !held.exists(_.journal.size >= 3) do Thread.sleep(10)
    }(using ExecutionContext.global)
    t.source = (_, from) =>
      if calls.incrementAndGet() == 1 then
        Source(numbered(records, from).take(3))
          .concat(
            Source
              .future(holdsThree)
              .flatMapConcat(_ => Source.failed(IllegalStateException("the link dropped")))
          )
      else Source(numbered(records, from)).concat(Source.never)
    val c = joined(t)
    held = Some(c)
    eventually()(Option.when(c.journal.size == 10)(()))
    assertEquals(t.openedNow, Vector("vienna" -> 1L, "vienna" -> 4L))
    assertEquals(c.journal.map(fields), records.map(fields))
  }

  test("a new home is pulled from the same position") {
    val t = ScriptedTransport()
    // vienna hands the home to prague: its last record names prague.
    val records =
      history(Set("vienna", "leipzig", "prague"), 1) :+ home("prague", writer = "vienna")
    t.source = {
      case ("vienna", from) => Source(numbered(records, from)).concat(Source.never)
      case _                => Source.never
    }
    val c = joined(t)
    eventually()(Option.when(c.journal.size == 4)(()))
    eventually()(t.openedNow.find(_._1 == "prague"))
    assertEquals(t.openedNow, Vector("vienna" -> 1L, "prague" -> 5L))
  }

  test(
    "a refused record opens the source again from the copy's position, not from the refused one"
  ) {
    val t       = ScriptedTransport()
    val records = history(Set("vienna", "leipzig"), 3)
    val calls   = AtomicInteger()
    t.source = (_, from) =>
      if calls.incrementAndGet() == 1 then
        // Records 1 and 2, then 4: a gap the copy refuses.
        Source(numbered(records, from).take(2) :+ Replicated(4, records(3)))
      else Source(numbered(records, from)).concat(Source.never)
    val c = joined(t)
    eventually()(Option.when(c.journal.size == 5)(()))
    assertEquals(t.openedNow, Vector("vienna" -> 1L, "vienna" -> 3L))
    assertEquals(c.journal.map(fields), records.map(fields))
  }
