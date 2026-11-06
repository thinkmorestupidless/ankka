package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{Done, EntityId}

import scala.concurrent.duration.*

/**
 * A recovery during a keyring outage (feature 042, R4): retried, answered once the keyring is back,
 * and never read as erased in between.
 */
final class RecoveryRetrySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq(ContactCardEntity.descriptor))

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def card(id: String) = kit.componentClient.forKeyValueEntity(EntityId(id))

  test("a key value entity recovered while the keyring is out is answered when it returns") {
    assertEquals(card("r-1").call(ContactCardEntity.setEmail).invoke("ada@example.com"), Done)
    // A fresh service holds no key, so its first read must ask the keyring.
    kit.restartService(whileStopped = kit.keyring.outage(3.seconds))
    val started = System.nanoTime()
    assertEquals(card("r-1").call(ContactCardEntity.email).invoke(), "ada@example.com")
    val waited = (System.nanoTime() - started).nanos
    assert(waited >= 1.second, s"answered after $waited, before the outage could have ended")
  }
