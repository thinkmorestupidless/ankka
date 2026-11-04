package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.DeclaredQuery

/** Feature 042, T032 (R12, R13): what a declared query may do with a view's personal fields. */
class QueryCheckPersonalSuite extends munit.FunSuite:

  private val table  = "ankka_view_players"
  private val view   = ComponentId("players")
  private val fields =
    ViewFields(
      Vector("email", "name", "address.line1", "contacts[*].value"),
      Vector("email", "address.line1")
    )

  private def check(statement: String, f: ViewFields = fields) =
    QueryCheck.check(view, table, DeclaredQuery(view, "q", statement), f)

  private def refused(statement: String): String =
    check(statement).fold(identity, ok => fail(s"accepted: $ok"))

  test("an equality of a lookup field with a value is rewritten to compare its token") {
    val ok =
      check(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email").toOption.get
    assert(ok.sql.contains("lookup ->> 'email' = $1"), ok.sql)
    assert(!ok.sql.contains("payload::jsonb ->> 'email'"), ok.sql)
    assertEquals(ok.values, Vector("email"))
    assertEquals(ok.lookups, Map("email" -> "email"))
  }

  test("a nested lookup field, written with -> and ->>, the value on either side") {
    val ok = check(
      s"SELECT payload FROM $table WHERE :line = payload::jsonb->'address'->>'line1' AND payload::jsonb->>'currency' = :c"
    ).toOption.get
    assert(ok.sql.contains("$1 = lookup ->> 'address.line1'"), ok.sql)
    assertEquals(ok.values, Vector("line", "c"))
    assertEquals(ok.lookups, Map("line" -> "address.line1"))
  }

  test(
    "a predicate on a personal field not marked for lookup is refused, naming the view, query and field"
  ) {
    val why = refused(s"SELECT payload FROM $table WHERE payload::jsonb->>'name' = :name")
    assert(why.contains("view 'players'"), why)
    assert(why.contains("query 'q'"), why)
    assert(why.contains("'name'"), why)
    assert(why.contains("lookup(_.name)"), why)
  }

  test("a lookup field compared other than by equality with a value is refused") {
    assert(
      refused(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' LIKE :e").contains(
        "'email'"
      )
    )
    assert(
      refused(s"SELECT payload FROM $table ORDER BY payload::jsonb->>'email'").contains("'email'")
    )
    assert(
      refused(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = 'ada@example.com'")
        .contains("'email'")
    )
  }

  test("every way of reading a personal field in a predicate is seen") {
    assert(
      refused(
        s"SELECT payload FROM $table WHERE jsonb_extract_path_text(payload::jsonb, 'name') = :n"
      ).contains("'name'")
    )
    assert(
      refused(s"SELECT payload FROM $table WHERE (payload::jsonb)->>'name' = :n").contains("'name'")
    )
    assert(
      refused(s"SELECT payload FROM $table WHERE payload::jsonb->'contacts'->0->>'value' = :v")
        .contains("contacts[*].value")
    )
    assert(
      refused(s"SELECT payload FROM $table WHERE payload::jsonb->'address' = :a::jsonb").contains(
        "address.line1"
      )
    )
    assert(
      refused(
        s"SELECT payload FROM $table WHERE row_key IN (SELECT row_key FROM $table WHERE payload::jsonb->>'name' = :n)"
      ).contains("'name'")
    )
  }

  test(
    "the data subject beside an envelope, a field that is not personal, and selecting are allowed"
  ) {
    val ok = check(
      s"SELECT payload, payload::jsonb->>'name' AS shown FROM $table WHERE payload::jsonb->'name'->>'subject' = :s AND payload::jsonb->>'currency' = :c"
    )
    assert(ok.isRight, ok.toString)
    assertEquals(ok.toOption.get.lookups, Map.empty[String, String])
  }

  test("a lookup value used again elsewhere is refused") {
    val why = refused(
      s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :e OR payload::jsonb->>'currency' = :e"
    )
    assert(why.contains("':e'"), why)
  }

  test("a view with no personal fields is checked as before") {
    val ok =
      check(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email", ViewFields.none)
    assertEquals(ok.map(_.lookups), Right(Map.empty[String, String]))
    assert(ok.toOption.get.sql.contains("payload::jsonb->>'email' = $1"), ok.toString)
  }
