package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.time.LocalDate
import scala.util.Try

/**
 * A personal field through a whole service: stored only encrypted in every table, read back as the
 * value, read as erased everywhere once its subject is erased — after a restart too — and refused
 * for a new write; another subject untouched.
 */
class PersonalKitSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(PlayerEntity.descriptor, Profiles.descriptor),
      extensions = Seq(ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def player(id: String) = kit.componentClient.forEventSourcedEntity(EntityId(id))

  private def register(id: String, email: String, name: String): Unit =
    player(id)
      .call(PlayerEntity.register)
      .invoke(Registration(email, name, LocalDate.of(1815, 12, 10), "GBP")): Unit

  private def read(id: String): PlayerRead = player(id).call(PlayerEntity.get).invoke()

  private def profiles = kit.service.viewClient.forView(Profiles)

  test(
    "a registered player's personal fields are in no table readable, and read back as the values"
  ) {
    register("8c1f", "ada@example.com", "Ada Byron")
    assertEquals(read("8c1f"), PlayerRead("ada@example.com", "Ada Byron", "1815-12-10", "GBP", 0))
    val row = kit.eventually("the profile row")(profiles.get("8c1f"))
    assertEquals(row.email.toOption, Some("ada@example.com"))
    kit.assertNoPersonalValue("ada@example.com", "Ada Byron", "1815-12-10")
  }

  test("a view's row carries the lookup token of a field marked for lookup, and the journal none") {
    val token = com.thinkmorestupidless.ankka.core.personal.LookupTokens
      .forText(kit.service.keyring.lookupKey("local"), "ada@example.com")
    assert(rowText("8c1f").contains(s""""lookup":"$token""""), rowText("8c1f"))
    assert(
      !journalText.contains("lookup"),
      "a lookup token is written to a view's rows, never the journal"
    )
    assert(journalText.contains("\"subject\":\"player/8c1f\""), "the journal holds the envelopes")
  }

  test("a declared query finds a row by a personal field's lookup token, and by nothing else") {
    val token = kit.eventually("a token")(
      Some(
        com.thinkmorestupidless.ankka.core.personal.LookupTokens
          .forText(kit.service.keyring.lookupKey("local"), "ada@example.com")
      )
    )
    assertEquals(profiles.ask(Profiles.byEmail, "email" -> token).map(_.playerId), Vector("8c1f"))
    assertEquals(profiles.ask(Profiles.byEmail, "email" -> "ada@example.com").size, 0)
  }

  test("the check that no table holds a personal value fails when one does") {
    val failure = Try(kit.assertNoPersonalValue("GBP")).failed.get
    assert(failure.getMessage.contains("GBP"), failure.getMessage)
  }

  test(
    "an erased subject reads as erased in the entity and the view, after a restart too, and nothing else changes"
  ) {
    register("9d2e", "grace@example.com", "Grace Hopper")
    player("8c1f").call(PlayerEntity.deposit).invoke(25): Unit
    kit.eventually("the second profile row")(profiles.get("9d2e"))

    kit.erase("player/8c1f")

    assertEquals(read("8c1f"), PlayerRead("<erased>", "<erased>", "<erased>", "GBP", 25))
    assertEquals(profiles.get("8c1f").map(_.email), Some(Personal.Erased("player/8c1f")))
    kit.restartService()
    assertEquals(read("8c1f"), PlayerRead("<erased>", "<erased>", "<erased>", "GBP", 25))
    assertEquals(profiles.get("8c1f").map(_.email), Some(Personal.Erased("player/8c1f")))
    assertEquals(read("9d2e").email, "grace@example.com")
    assertEquals(profiles.get("9d2e").flatMap(_.email.toOption), Some("grace@example.com"))
  }

  test("a new personal field cannot be written for an erased subject, and nothing is recorded") {
    val refusal = Try(register("8c1f", "again@example.com", "Again")).failed.get
    assert(refusal.getMessage.contains("player/8c1f"), refusal.getMessage)
    assert(refusal.getMessage.contains("erased"), refusal.getMessage)
    assertEquals(read("8c1f").deposits, 25)
    kit.assertNoPersonalValue("again@example.com")
  }

  test("the redacted row holds the subject-only envelope, with no ciphertext and no lookup token") {
    val stored = rowText("8c1f")
    assert(stored.contains("""{"subject":"player/8c1f","project":"local"}"""), stored)
    assert(!stored.contains("\"data\""), stored)
  }

  private def journalText: String =
    import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    scala.concurrent.Await
      .result(
        Database().query(
          SqlFragment.raw("SELECT encode(event_payload, 'escape') FROM event_journal")
        )(_.get(0, classOf[String])),
        scala.concurrent.duration.Duration(10, "s")
      )
      .mkString("\n")

  private def rowText(key: String): String =
    import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    scala.concurrent.Await
      .result(
        Database().queryOne(
          SqlFragment.raw("SELECT payload FROM ankka_view_profiles WHERE row_key = ") ++ sql"$key"
        )(
          _.get(0, classOf[String])
        ),
        scala.concurrent.duration.Duration(10, "s")
      )
      .getOrElse(fail(s"no row $key"))
