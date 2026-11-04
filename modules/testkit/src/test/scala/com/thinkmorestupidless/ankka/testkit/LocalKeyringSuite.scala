package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment
}
import com.thinkmorestupidless.ankka.runtime.keyring.{KeyringHandle, LocalKeyring}

import com.typesafe.config.ConfigFactory

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.util.{Try, Using}
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

  private def localKit(settings: com.typesafe.config.Config = ConfigFactory.empty()) =
    AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      configure = _.withLocalKeyring(),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      settings = settings
    )

  private def localOf(kit: AnkkaTestKit): LocalKeyring =
    kit.service.keyring match
      case h: KeyringHandle =>
        h.client.store match
          case l: LocalKeyring => l
          case other           => fail(s"the service's keyring is $other")
      case other => fail(s"the service's keyring is $other")

  private def post(
      address: String,
      contentType: String,
      body: String,
      path: String = "/_ankka/erasures"
  ) =
    HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(URI.create(s"$address$path"))
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

  test(
    "an outage asked on the local console refuses every request, erasures included, until it ends"
  ) {
    // The address a test kit's sidecar container names, here on loopback: the endpoint listens
    // where it is told.
    val port = Using(ServerSocket(0))(_.getLocalPort).get
    val kit  = localKit(
      ConfigFactory.parseString(s"""ankka.observability.local-address = "127.0.0.1:$port"""")
    )
    try
      val address = kit.service.observabilityAddress.getOrElse(fail("no local console endpoint"))
      assertEquals(address, s"http://127.0.0.1:$port")
      val outage = "/_ankka/keyring/outage"
      kit.componentClient
        .forEventSourcedEntity(EntityId("8c1f"))
        .call(MemberEntity.join)
        .invoke("ada@example.com"): Unit

      assertEquals(post(address, "text/plain", """{"seconds":600}""", outage).statusCode, 415)
      assertEquals(post(address, "application/json", """{"seconds":-1}""", outage).statusCode, 400)
      val started = post(address, "application/json", """{"seconds":600}""", outage)
      assertEquals(started.statusCode, 200, started.body)
      assert(started.body.startsWith("""{"until":""""), started.body)

      // A key not held is refused as unavailable, by the keyring and through a component.
      val refused = Try(localOf(kit).key("brand", DataSubject("member/9d2e"), create = true))
      assert(
        refused.failed.toOption.exists {
          case e: CommandError =>
            e.code == ErrorCode.Unavailable &&
            e.details.get(Personal.ErrorKey).contains(Personal.Unavailable)
          case _ => false
        },
        refused.toString
      )
      val write = Try(
        kit.componentClient
          .forEventSourcedEntity(EntityId("9d2e"))
          .call(MemberEntity.join)
          .invoke("grace@example.com")
      )
      assert(
        write.failed.toOption.exists {
          case e: CommandError => e.code == ErrorCode.Unavailable; case _ => false
        },
        write.toString
      )
      // An erasure destroys nothing in an outage.
      val erasure = post(address, "application/json", """{"subject":"member/8c1f"}""")
      assertEquals(erasure.statusCode, 503, erasure.body)
      assert(erasure.body.contains("keyring is unavailable"), erasure.body)

      val ended = post(address, "application/json", """{"seconds":0}""", outage)
      assertEquals(ended.body, """{"until":null}""")
      assert(
        localOf(kit)
          .key("brand", DataSubject("member/8c1f"), create = false)
          .isInstanceOf[KeyAnswer.Key]
      )
      assertEquals(
        post(address, "application/json", """{"subject":"member/8c1f"}""").statusCode,
        200
      )
      assertEquals(
        localOf(kit).key("brand", DataSubject("member/8c1f"), create = false),
        KeyAnswer.Destroyed
      )
    finally kit.stop()
  }
