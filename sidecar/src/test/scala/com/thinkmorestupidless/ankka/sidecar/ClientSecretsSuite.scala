package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.sidecar.wasm.HostImports
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The secret store as a process and a module reach it: `ClientLogic`'s three calls, which the gRPC
 * service and the module imports both delegate to, against a real database.
 */
class ClientSecretsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  private def logic: ClientLogic =
    given ActorSystem[?] = testKit.service.system
    ClientLogic(testKit.service, settings, () => None)

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)

  test("a secret put is got back as its value") {
    val client = logic
    assertEquals(await(client.putSecret(PutSecretRequest("provider/acme", "sk-1"))).error, None)
    assertEquals(
      await(client.getSecret(GetSecretRequest("provider/acme"))).result,
      GetSecretReply.Result.Value("sk-1")
    )
  }

  test("a name never kept is absent, with no error") {
    assertEquals(
      await(logic.getSecret(GetSecretRequest("never"))).result,
      GetSecretReply.Result.Absent(pb.Empty())
    )
  }

  test("a removed secret is absent") {
    val client = logic
    await(client.putSecret(PutSecretRequest("gone", "sk-1"))): Unit
    assertEquals(await(client.deleteSecret(DeleteSecretRequest("gone"))).error, None)
    assert(await(client.getSecret(GetSecretRequest("gone"))).result.isAbsent)
  }

  test("a name that breaks the rule is refused in the reply, naming the rule") {
    val reply = await(logic.putSecret(PutSecretRequest("provider acme", "sk-1")))
    assertEquals(reply.error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
    assert(reply.error.exists(_.message.contains("'.', '_', '-' or '/'")), reply.toString)
    val got = await(logic.getSecret(GetSecretRequest("provider acme")))
    assertEquals(got.result.error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
  }

  test("with no secret key, put and get are refused naming the variable, and delete works") {
    val key = testKit.secretKey
    try
      testKit.restartService(secretKey = None)
      val client = logic
      val put    = await(client.putSecret(PutSecretRequest("acme", "sk-1")))
      assertEquals(put.error.map(_.code), Some(pb.ErrorCode.INTERNAL))
      assert(put.error.exists(_.message.contains("ANKKA_SECRET_KEY")), put.toString)
      val got = await(client.getSecret(GetSecretRequest("acme")))
      assert(got.result.error.exists(_.message.contains("ANKKA_SECRET_KEY")), got.toString)
      assertEquals(await(client.deleteSecret(DeleteSecretRequest("acme"))).error, None)
    finally testKit.restartService(secretKey = key)
  }

  test("a module may import the three secret calls, and is not shown the key") {
    val imports = HostImports(5.seconds, 5.seconds, Map("ANKKA_SECRET_KEY" -> "set").get)
    val names   = imports.values.functions().map(_.name()).toSet
    assert(Set("get_secret", "put_secret", "delete_secret").subsetOf(names), names.toString)
    assertEquals(imports.lookup("ANKKA_SECRET_KEY"), None)
  }
