package com.thinkmorestupidless.ankka.operator

import java.time.Instant

class RecoveryPointSuite extends munit.FunSuite:
  private val now    = Instant.parse("2026-10-09T17:45:00Z")
  private val moment = now.minusSeconds(60)

  /** A source database: what each statement answers, and every statement it was sent. */
  private final class Source(var archived: String, switched: String = "000000010000000000000008"):
    val sent = scala.collection.mutable.ArrayBuffer.empty[String]
    def ask(sql: String): Option[String] =
      sent += sql
      sql match
        case RecoveryPoint.Commit   => Some("812")
        case RecoveryPoint.Switch   => Some(switched)
        case RecoveryPoint.Archived => Some(archived)
        case _                      => None

  override def beforeEach(context: BeforeEach): Unit = RecoveryPoint.forget()

  test("a recovery waits until a commit after its moment has been archived") {
    val source = Source(archived = "000000010000000000000007")
    assert(!RecoveryPoint.reached("shop/r1", moment, now, source.ask))
    // The commit is its own transaction, written before the segment is switched.
    assertEquals(source.sent.take(2).toVector, Vector(RecoveryPoint.Commit, RecoveryPoint.Switch))
    source.archived = "000000010000000000000008"
    assert(RecoveryPoint.reached("shop/r1", moment, now, source.ask))
  }

  test("the commit is written once per recovery, however many passes wait for it") {
    val source = Source(archived = "000000010000000000000007")
    (1 to 5).foreach(_ => RecoveryPoint.reached("shop/r1", moment, now, source.ask))
    assertEquals(source.sent.count(_ == RecoveryPoint.Commit), 1)
  }

  test("a moment not yet passed, or a source that cannot be asked, is not reached") {
    assert(!RecoveryPoint.reached("shop/r2", now.plusSeconds(5), now, Source("x").ask))
    assert(!RecoveryPoint.reached("shop/r3", moment, now, _ => None))
  }
