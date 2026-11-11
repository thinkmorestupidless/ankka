package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{DataSubject, Done, EntityId, Keyring}

/** The test kit's keyring (feature 042): handed to the service, and handed again on a restart. */
final class AnkkaTestKitKeyringSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq(ContactCardEntity.descriptor))

  override def afterAll(): Unit = if kit != null then kit.stop()

  private def card(id: String) = kit.componentClient.forKeyValueEntity(EntityId(id))

  test("a personal field is kept encrypted and read back as its value") {
    assertEquals(card("8c1f").call(ContactCardEntity.setEmail).invoke("ada@example.com"), Done)
    assertEquals(card("8c1f").call(ContactCardEntity.email).invoke(), "ada@example.com")
    assert(kit.keyring.holdsKey(kit.project, DataSubject("player/8c1f")))
    kit.assertNoPersonalValue("ada@example.com")
  }

  test("a restarted kit's codec uses the new service's keyring, over the same keys") {
    assertEquals(card("41d2").call(ContactCardEntity.setEmail).invoke("grace@example.com"), Done)
    val before = kit.service.keyring
    assert(Keyring.current eq before, s"${Keyring.current} is not $before")
    kit.restartService()
    val after = kit.service.keyring
    assert(after ne before, "the restarted service kept the stopped one's keyring handle")
    assert(Keyring.current eq after, s"${Keyring.current} is not $after")
    assertEquals(card("41d2").call(ContactCardEntity.email).invoke(), "grace@example.com")
  }

  test("an erased player's email reads as erased, from the database") {
    assertEquals(card("77a0").call(ContactCardEntity.setEmail).invoke("ida@example.com"), Done)
    // docs:start erase
    kit.erase(DataSubject("player/77a0")) // destroys the key now and sends the notice
    kit.restartService()                  // read from the database, not from memory
    assertEquals(card("77a0").call(ContactCardEntity.email).invoke(), "erased")

    kit.assertNoPersonalValue("ida@example.com") // fails naming the table and column holding it
    // docs:end erase
  }
