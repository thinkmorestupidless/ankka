package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment
}
import com.thinkmorestupidless.ankka.runtime.keyring.ViewRedaction
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** Feature 042, T034 (R14): an erased data subject's envelopes, reduced in a view's rows. */
final class ViewRedactionSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private def envelope(subject: String, c: Option[String]) =
    s"""{"$$personal":1,"project":"brand","subject":"$subject"${c.fold("")(v =>
        s""","c":"$v""""
      )}}"""

  test("a subject's envelopes are reduced to the subject; another's in the same row survive") {
    val kit = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = InMemoryKeyring()
    )
    try
      given ExecutionContext   = kit.service.system.executionContext
      val db                   = Database()(using kit.service.system)
      def run(f: SqlFragment)  = Await.result(db.execute(f), 30.seconds)
      def payload(key: String) = Await
        .result(
          db.query(sql"SELECT payload, lookup::text FROM ankka_view_members WHERE row_key = $key")(
            r => (r.get(0, classOf[String]), Option(r.get(1, classOf[String])))
          ),
          30.seconds
        )
        .head
      val both =
        s"""{"memberId":"both","email":${envelope("member/a", Some("AAAA"))},"city":"Leeds",""" +
          s""""friends":[{"email":${envelope("member/b", Some("BBBB"))}},{"email":${envelope(
              "member/a",
              Some("CCCC")
            )}}]}"""
      val other =
        s"""{"memberId":"other","email":${envelope("member/b", Some("DDDD"))},"city":"York"}"""
      val t = """{"email":"t"}"""
      val u = """{"email":"u"}"""
      run(
        sql"INSERT INTO ankka_view_members (row_key, payload, lookup) VALUES ('both', $both, $t::jsonb)"
      )
      run(
        sql"INSERT INTO ankka_view_members (row_key, payload, lookup) VALUES ('other', $other, $u::jsonb)"
      )
      val tables = Vector(ViewRedaction.Table("ankka_view_members", lookup = true))

      val first = Await.result(ViewRedaction.redact(db, tables, "brand", "member/a"), 30.seconds)
      assertEquals(first, ViewRedaction.Redacted(rows = 1, tokens = 1))
      val (after, lookup) = payload("both")
      assert(!after.contains("AAAA") && !after.contains("CCCC"), after)
      assert(after.contains("BBBB"), after)
      assert(after.contains("\"city\": \"Leeds\""), after)
      assertEquals(lookup, None)
      assertEquals(payload("other"), (other, Some("""{"email": "u"}""")))

      // Again: nothing left of the subject to rewrite.
      assertEquals(
        Await.result(ViewRedaction.redact(db, tables, "brand", "member/a"), 30.seconds),
        ViewRedaction.Redacted.none
      )
      // Another project's subject of the same name is not this one.
      assertEquals(
        Await.result(ViewRedaction.redact(db, tables, "elsewhere", "member/b"), 30.seconds),
        ViewRedaction.Redacted.none
      )
    finally kit.stop()
  }

  test("a redacted row reads its field as erased, and the rest as written") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )
    try
      given ExecutionContext = kit.service.system.executionContext
      kit.componentClient
        .forEventSourcedEntity(EntityId("8c1f"))
        .call(MemberEntity.join)
        .invoke("ada@example.com"): Unit
      val rows = kit.service.viewClient.forView(Members)
      Iterator
        .continually { Thread.sleep(100); rows.get("8c1f") }
        .take(300)
        .find(_.nonEmpty)
        .getOrElse(fail("no row"))
      val db       = Database()(using kit.service.system)
      val redacted = Await.result(
        ViewRedaction.redact(
          db,
          Vector(ViewRedaction.Table("ankka_view_members", lookup = true)),
          "brand",
          "member/8c1f"
        ),
        30.seconds
      )
      assertEquals(redacted.rows, 1L)
      val row = rows.get("8c1f").getOrElse(fail("the row is gone"))
      assert(row.email.isErased, row.toString)
      assertEquals(row.city, "Leeds")
      assertEquals(rows.ask(Members.byEmail, "email" -> "ada@example.com"), Vector.empty)
    finally kit.stop()
  }
