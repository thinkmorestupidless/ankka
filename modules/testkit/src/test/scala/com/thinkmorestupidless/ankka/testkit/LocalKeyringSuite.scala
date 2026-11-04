package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment
}
import com.thinkmorestupidless.ankka.runtime.keyring.{KeyringHandle, LocalKeyring}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Feature 042, T037 (R20): the keyring of a service run on a developer's machine. */
final class LocalKeyringSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def eventually[A](what: String)(probe: => Option[A]): A =
    Iterator
      .continually { Thread.sleep(100); probe }
      .take(300)
      .collectFirst { case Some(a) => a }
      .getOrElse(fail(s"never: $what"))

  private def bytes(answer: KeyAnswer): Vector[Byte] = answer match
    case KeyAnswer.Key(k) => IArray.genericWrapArray(k).toVector
    case other            => fail(s"no key: $other")

  private def localKit() =
    AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      configure = _.withLocalKeyring(),
      serviceIdentity = ServiceIdentity.deployed("brand", "members")
    )

  private def localOf(kit: AnkkaTestKit): LocalKeyring =
    kit.service.keyring match
      case h: KeyringHandle =>
        h.client.store match
          case l: LocalKeyring => l
          case other           => fail(s"the service's keyring is $other")
      case other => fail(s"the service's keyring is $other")

  private def post(address: String, contentType: String, body: String) =
    HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"$address/_ankka/erasures"))
          .header("Content-Type", contentType)
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )

  test("a key is kept in the service's database, survives a restart, and an erasure destroys it") {
    val kit = localKit()
    try
      val keyring = localOf(kit)
      val subject = DataSubject("member/8c1f")
      val made    = keyring.key("brand", subject, create = true)
      assertEquals(bytes(keyring.key("brand", subject, create = true)), bytes(made))
      assertEquals(
        keyring.key("brand", DataSubject("member/none"), create = false),
        KeyAnswer.Absent
      )
      kit.restartService()
      assertEquals(bytes(localOf(kit).key("brand", subject, create = false)), bytes(made))

      val seq = localOf(kit).erase(subject)
      assertEquals(localOf(kit).key("brand", subject, create = false), KeyAnswer.Destroyed)
      // A tombstoned subject's first write is refused: no key is made again.
      assertEquals(localOf(kit).key("brand", subject, create = true), KeyAnswer.Destroyed)
      val db = Database()(using kit.service.system)
      assertEquals(
        Await.result(
          db.query(SqlFragment.raw("SELECT erasure_seq FROM ankka_keyring.tombstone"))(
            _.get(0, classOf[java.lang.Long]).longValue
          ),
          30.seconds
        ),
        Vector(seq)
      )
      assertEquals(
        Await.result(
          db.query(SqlFragment.raw("SELECT count(*) FROM ankka_keyring.subject_key"))(
            _.get(0, classOf[java.lang.Long]).longValue
          ),
          30.seconds
        ),
        Vector(0L)
      )
    finally kit.stop()
  }

  test("an erasure filed on the local console destroys the key and runs the service's duties") {
    val kit = localKit()
    try
      kit.componentClient
        .forEventSourcedEntity(EntityId("8c1f"))
        .call(MemberEntity.join)
        .invoke("ada@example.com"): Unit
      val rows = kit.service.viewClient.forView(Members)
      eventually("the row")(rows.get("8c1f"))
      val address = kit.service.observabilityAddress.getOrElse(fail("no local console endpoint"))

      assertEquals(post(address, "text/plain", """{"subject":"member/8c1f"}""").statusCode, 415)
      val bad = post(address, "application/json", """{"subject":"member 8c1f"}""")
      assertEquals(bad.statusCode, 400)
      assert(bad.body.contains("1 to 253 characters"), bad.body)

      val filed = post(address, "application/json", """{"subject":"member/8c1f"}""")
      assertEquals(filed.statusCode, 200, filed.body)
      assert(filed.body.contains(""""rowsRedacted":1"""), filed.body)
      assert(filed.body.contains(""""service":"members""""), filed.body)
      assert(rows.get("8c1f").exists(_.email.isErased))
      kit.assertNoPersonalValue("ada@example.com")
    finally kit.stop()
  }
