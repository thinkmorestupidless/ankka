package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{Database, HistoryLines}
import com.typesafe.config.ConfigFactory

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * The lines of history a service's database has been (feature 041), against a real database: the
 * row a start writes, the one a second start does not, and which line an event's moment belongs to.
 */
class HistoryLinesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(LedgerEntity.descriptor),
      settings = ConfigFactory.parseString("ankka.history-line = \"ankka-db\"")
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def database = Database()(using testKit.service.system)

  test("a service records the line it starts on, once, with the database's own clock") {
    val lines = HistoryLines(testKit.service.system).lines
    assertEquals(lines.map(_._2), Vector("ankka-db"))
    val again = new HistoryLines("ankka-db", () => database)
    again.start()
    assertEquals(again.lines.map(_._2), Vector("ankka-db"), "a second start writes no row")
  }

  test("a new line begins at its first start; an event belongs to the line it was written on") {
    val before = Instant.now()
    Thread.sleep(50)
    val restore = new HistoryLines("ankka-db-r202610090000", () => database)
    restore.start()
    val lines = restore.lines
    assertEquals(lines.map(_._2), Vector("ankka-db", "ankka-db-r202610090000"))
    val begun = lines.last._1
    assertEquals(restore.lineOf(before), Some("ankka-db"))
    assertEquals(restore.lineOf(begun), Some("ankka-db-r202610090000"))
    assertEquals(restore.lineOf(begun.plusSeconds(60)), Some("ankka-db-r202610090000"))
    assertEquals(restore.lineOf(Instant.EPOCH), Some("ankka-db"), "older than every row: the first")
  }

  test("with no line, nothing is recorded and no event has one") {
    val none = new HistoryLines("", () => fail("the database is not asked"))
    none.start()
    assertEquals(none.lineOf(Instant.now()), None)
  }

  test("a database without the table keeps random ids rather than failing the start") {
    val broken = new HistoryLines("ankka-db", () => throw IllegalStateException("no table"))
    broken.start()
    assertEquals(broken.lineOf(Instant.now()), None)
  }

  test("on the platform the line is the cluster the database host names, unless one is given") {
    def config(text: String) = ConfigFactory.parseString(text)
    val host                 = "pekko.persistence.r2dbc.connection-factory.host"
    assertEquals(
      HistoryLines.lineFrom(
        config(s"""ankka.cluster.formation = bootstrap, $host = "ankka-db-rw"""")
      ),
      "ankka-db"
    )
    assertEquals(
      HistoryLines.lineFrom(
        config(
          s"""ankka.cluster.formation = bootstrap, $host = "ankka-db-r1-rw", ankka.history-line = "ankka-db-r1""""
        )
      ),
      "ankka-db-r1"
    )
    assertEquals(HistoryLines.lineFrom(config(s"""$host = "ankka-db-rw"""")), "", "local: none")
    assertEquals(
      HistoryLines.lineFrom(
        config(s"""ankka.cluster.formation = bootstrap, $host = "db.example"""")
      ),
      "",
      "a database the service supplies"
    )
  }
