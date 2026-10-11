package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.application.MachineEntity
import com.thinkmorestupidless.ankka.controlplane.auth.MachineSecrets
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.MachineEvent.*
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

/**
 * A machine on an organization (feature 040): it keeps a digest and never the secret, answers a
 * presented secret only while it is registered, and may be registered again after it is deleted, as
 * a new machine with a new secret.
 */
class MachineEntitySuite extends munit.FunSuite with LogCapturing:

  private val now = java.time.Instant.parse("2026-10-09T10:00:00Z")
  private val bo  = Attribution(Actor("bo", Some("bo@affiliates.test")), now)

  private def kit = EventSourcedTestKit.of(MachineEntity, Machine.key("affiliates", "network"))

  private def registered(secret: String = "s3cret") =
    val k = kit
    val result = k.call(MachineEntity.register, bo.metadata)(
      RegisterMachine("affiliates", "network", MachineSecrets.digest(secret))
    )
    (k, result)

  test("registering keeps a digest and the registrant, and no secret") {
    val (_, result) = registered()
    result.events match
      case Vector(MachineRegistered("affiliates", "network", digest, Some(actor), Some(at))) =>
        assertEquals(digest, MachineSecrets.digest("s3cret"))
        assertEquals((actor, at), (bo.actor, now))
      case other => fail(s"unexpected events $other")
    assertEquals(result.replyValue.clientId, "machine:affiliates/network")
    assert(!result.events.toString.contains("s3cret"), result.events.toString)
  }

  test("a machine registered and not deleted cannot be registered again") {
    val (k, _) = registered()
    val again =
      k.call(MachineEntity.register, bo.metadata)(RegisterMachine("affiliates", "network", "x"))
    assertEquals(again.error.code, ErrorCode.Conflict)
  }

  test("a secret is answered only when it is the machine's own, and the machine is not deleted") {
    val (k, _) = registered()
    assertEquals(
      k.call(MachineEntity.checkSecret)("s3cret").replyValue.map(_.name),
      Some("network")
    )
    assertEquals(k.call(MachineEntity.checkSecret)("wrong").replyValue, None)
    k.call(MachineEntity.delete, bo.metadata): Unit
    assertEquals(k.call(MachineEntity.checkSecret)("s3cret").replyValue, None)
    // An unknown machine is told nothing either.
    assertEquals(kit.call(MachineEntity.checkSecret)("s3cret").replyValue, None)
  }

  test("deleted, a name may be registered again: a new machine, and the old secret is refused") {
    val (k, _) = registered()
    k.call(MachineEntity.delete, bo.metadata): Unit
    val again =
      k.call(MachineEntity.register, bo.metadata)(
        RegisterMachine("affiliates", "network", MachineSecrets.digest("fresh"))
      )
    assert(!again.isError, again.reply.toString)
    assertEquals(k.call(MachineEntity.checkSecret)("s3cret").replyValue, None)
    assertEquals(k.call(MachineEntity.checkSecret)("fresh").replyValue.map(_.name), Some("network"))
    assertEquals(again.replyValue.byteRates, None)
  }

  test("byte rates are kept on the machine; a machine that is not there has none to set") {
    val (k, _) = registered()
    val set = k.call(MachineEntity.setByteRates, bo.metadata)(SetMachineByteRates(1024, 2048, 50))
    assertEquals(set.replyValue.byteRates.map(_.requestPercentage), Some(50))
    assertEquals(
      kit.call(MachineEntity.setByteRates, bo.metadata)(SetMachineByteRates(1, 1, 1)).error.code,
      ErrorCode.NotFound
    )
  }

  test("a name outside the rule is refused") {
    val result = kit.call(MachineEntity.register, bo.metadata)(
      RegisterMachine("affiliates", "Not_A_Name", "x")
    )
    assert(
      result.errorMessage.contains("machine name 'Not_A_Name' is invalid"),
      result.errorMessage
    )
  }

  test("a client id is read back into its organization and name, and nothing else is one") {
    assertEquals(
      MachineSecrets.parseClientId("machine:affiliates/network"),
      Some(("affiliates", "network"))
    )
    assertEquals(MachineSecrets.parseClientId("service:a/b"), None)
    assertEquals(MachineSecrets.parseClientId("machine:a/b/c"), None)
    assertEquals(MachineSecrets.parseClientId("machine:/b"), None)
  }

  test("a minted secret is 64 hex characters, and its digest matches it alone, in constant time") {
    val minted = MachineSecrets.mint()
    assert(minted.secret.matches("[0-9a-f]{64}"), minted.secret)
    assert(MachineSecrets.matches(minted.digest, minted.secret))
    assert(!MachineSecrets.matches(minted.digest, minted.secret.reverse))
    assert(!MachineSecrets.matches("", minted.secret))
  }
