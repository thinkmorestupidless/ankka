package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}

import scala.concurrent.duration.DurationInt

/** Key value entities on real sharding and Postgres durable state. */
class KeyValueIntegrationSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit = testKit = AnkkaTestKit.start(ProfileEntity.descriptor)
  override def afterAll(): Unit  = if testKit != null then testKit.stop()

  private def profile(id: String) =
    testKit.componentClient.forKeyValueEntity(EntityId(id))

  test("a write is durable and readable") {
    val id = "user-write"
    assertEquals(
      profile(id).call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 1)),
      Done
    )
    assertEquals(profile(id).call(ProfileEntity.get).invoke().name, "Ada")
  }

  test("the latest value survives losing every entity from memory") {
    val id = "user-replay"
    val _  = profile(id).call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 1))
    val _  = profile(id).call(ProfileEntity.rename).invoke("Ada Lovelace")
    assertEquals(profile(id).call(ProfileEntity.recordLogin).invoke(), 2)

    testKit.restartService()

    val recovered = profile(id).call(ProfileEntity.get).invoke()
    assertEquals(recovered.name, "Ada Lovelace")
    assertEquals(recovered.logins, 2, "only the latest value is kept, and it must be the latest")
  }

  test("rejections keep their classification across the wire") {
    val id = "user-reject"
    val _  = profile(id).call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 1))

    assertEquals(
      intercept[CommandError] {
        profile(id).call(ProfileEntity.register).invoke(Profile("Grace", "g@example.com", 1))
      }.code,
      ErrorCode.Conflict
    )
    assertEquals(
      intercept[CommandError](profile("user-absent").call(ProfileEntity.rename).invoke("X")).code,
      ErrorCode.NotFound
    )
  }

  test("delete empties the entity but leaves its id usable") {
    val id = "user-delete"
    val _  = profile(id).call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 1))
    assertEquals(profile(id).call(ProfileEntity.close).invoke(), Done)

    assertEquals(profile(id).call(ProfileEntity.get).invoke(), Profile("", "", 0))
    assertEquals(
      profile(id).call(ProfileEntity.register).invoke(Profile("Grace", "g@example.com", 1)),
      Done
    )
  }

  test("an expired entity reads as empty") {
    val id = "user-expiry"
    val _  = profile(id).call(ProfileEntity.register).invoke(Profile("Ada", "ada@example.com", 1))
    val _  = profile(id).call(ProfileEntity.expireIn).invoke(1.milli)

    // Expiry is observed at command time, so the very next read already sees it gone.
    Thread.sleep(50)
    assertEquals(profile(id).call(ProfileEntity.get).invoke(), Profile("", "", 0))
  }
