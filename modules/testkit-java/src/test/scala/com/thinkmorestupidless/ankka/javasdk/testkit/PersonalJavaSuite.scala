package com.thinkmorestupidless.ankka.javasdk.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import com.thinkmorestupidless.ankka.core.{
  DataSubject,
  KeyAnswer,
  Keyring,
  Personal,
  PersonalCipher,
  PersonalScope
}
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Players
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * features/erasure/languages.feature's JVM outline, its Java row (feature 042): a Java service's
 * journal holds the envelope a Scala service writes for the same field under the same key and
 * nonce, and the entity recovered in Java reads the field. The Kotlin row is the Kotlin SDK's
 * PersonalServiceTest.
 */
class PersonalJavaSuite extends munit.FunSuite with LogCapturing:

  private val Envelope =
    """"email":(\{"\$personal":1,"project":"([^"]*)","subject":"([^"]*)","c":"([^"]*)"\})""".r

  test("a service written in Java writes the personal envelope a service written in Scala writes") {
    val kit = AnkkaTestKit.start(Ankka.service("players").register(classOf[Players]))
    try
      kit.componentClient
        .forEventSourcedEntity("8c1f")
        .call(PlayerRefs.register)
        .invoke("ada@example.com")
      val scala   = kit.bridge().scalaKit
      val journal = Await
        .result(
          Database()(using scala.service.system).query(
            SqlFragment.raw(
              "SELECT event_payload FROM event_journal WHERE persistence_id = 'player|8c1f'"
            )
          )(row => new String(row.get(0, classOf[Array[Byte]]), UTF_8)),
          30.seconds
        )
        .mkString
      val m = Envelope.findFirstMatchIn(journal).getOrElse(fail(s"no envelope in $journal"))
      val (written, project, subject, c) = (m.group(1), m.group(2), m.group(3), m.group(4))
      assertEquals(subject, "player/8c1f")

      // What Scala writes for the same field, under the same key and the same nonce.
      val subjectKey = scala.keyring.key(project, DataSubject(subject), create = false) match
        case KeyAnswer.Key(k) => k
        case other            => fail(s"the keyring holds no key for $subject: $other")
      val nonce = Base64.getDecoder.decode(c).take(12)
      val fixed = new Keyring:
        def project: String                                            = scala.project
        def key(p: String, s: DataSubject, create: Boolean): KeyAnswer = KeyAnswer.Key(subjectKey)
      val ofScala = Keyring.binding(fixed)(
        PersonalScope.within("player-registered", Some(nonce))(
          new String(writeToArray(Personal(DataSubject(subject), "ada@example.com")), UTF_8)
        )
      )
      assertEquals(written, ofScala)
      val opened = PersonalCipher.open(
        subjectKey,
        Base64.getDecoder.decode(c),
        PersonalCipher.associatedData(subject, project, "player-registered")
      )
      assertEquals(opened.map(new String(_, UTF_8)), Some("\"ada@example.com\""))
      kit.assertNoPersonalValue("ada@example.com")

      // Recovered in Java, the field reads as its value; erased, it reads as erased.
      kit.restartService()
      assertEquals(
        kit.componentClient.forEventSourcedEntity("8c1f").call(PlayerRefs.email).invoke(),
        "ada@example.com"
      )
      kit.erase("player/8c1f")
      kit.restartService()
      assertEquals(
        kit.componentClient.forEventSourcedEntity("8c1f").call(PlayerRefs.email).invoke(),
        "erased"
      )
    finally kit.close()
  }

  test("a Java test erases through the kit's keyring, and an outage refuses a key not held") {
    val kit = AnkkaTestKit.start(Ankka.service("players").register(classOf[Players]))
    try
      val players = kit.componentClient
      players.forEventSourcedEntity("a1").call(PlayerRefs.register).invoke("ada@example.com")
      kit.keyring().outage(java.time.Duration.ofMinutes(10))
      // The service holds a1's key: it is read in an outage. b2's it never fetched.
      assertEquals(
        players.forEventSourcedEntity("a1").call(PlayerRefs.email).invoke(),
        "ada@example.com"
      )
      val refused = scala.util.Try(
        players.forEventSourcedEntity("b2").call(PlayerRefs.register).invoke("grace@example.com")
      )
      assert(
        refused.failed.toOption.exists(_.getMessage.contains("keyring is unavailable")),
        refused.toString
      )
      assert(scala.util.Try(kit.keyring().erase("player/a1")).isFailure, "erased in an outage")
      kit.keyring().recover()
      kit.keyring().erase("player/a1"): Unit
      kit.restartService()
      assertEquals(
        kit.componentClient.forEventSourcedEntity("a1").call(PlayerRefs.email).invoke(),
        "erased"
      )
      kit.assertNoPersonalValue("ada@example.com", "grace@example.com")
    finally kit.close()
  }
