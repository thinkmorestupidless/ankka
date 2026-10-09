package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

/**
 * The secret store on the Postgres backend: the shared scenarios, and those that name the database
 * or the secret key — one encrypted row per name, no key, another key, a malformed key.
 */
final class PostgresSecretStoreSuite extends SecretStoreBehaviours:

  protected def backend(): SecretBackendChoice = SecretBackendChoice.postgres

  protected def held(name: String, rows: Vector[(String, String)]): Int =
    rows.count((table, row) => table == "ankka_secrets" && row.contains(s"\"name\":\"$name\""))

  protected def storedAsExpected(rows: Vector[String]): Unit =
    assertEquals(rows.size, 1, rows.toString)
    assert(
      rows.head.contains("\"ciphertext\":\"\\\\x01"),
      "the stored form begins with its version"
    )

  // The cases below restart the service under other keys; each puts back the kit's own.

  test("a service with no secret key starts, and cannot keep or read a service secret") {
    val key = testKit.secretKey
    try
      testKit.restartService(secretKey = None)
      for work <- Vector(() => secrets.put("acme", "sk-1"), () => secrets.get("acme")) do
        val error = intercept[CommandError](work())
        assertEquals(error.code, ErrorCode.Internal)
        assert(error.message.contains("ANKKA_SECRET_KEY"), error.message)
      // Removing decrypts nothing, so it needs no key.
      secrets.delete("acme")
    finally testKit.restartService(secretKey = key)
  }

  test("a service secret cannot be read with another secret key") {
    val key = testKit.secretKey
    secrets.put("rotated", "sk-rotated")
    try
      testKit.restartService(secretKey = Some(AnkkaTestKit.generateSecretKey()))
      val error = intercept[CommandError](secrets.get("rotated"))
      assertEquals(error.code, ErrorCode.Internal)
      assert(error.message.contains("not the one 'rotated' was kept with"), error.message)
    finally testKit.restartService(secretKey = key)
    assertEquals(secrets.get("rotated"), Some("sk-rotated"))
  }

  test("a service whose secret key is malformed does not start") {
    val key = testKit.secretKey
    val failure = intercept[IllegalArgumentException](
      testKit.restartService(secretKey = Some("not-a-key"))
    )
    assert(failure.getMessage.contains("ANKKA_SECRET_KEY"), failure.getMessage)
    assert(!failure.getMessage.contains("not-a-key"), "the refusal never repeats the key")
    testKit.restartService(secretKey = key)
    assertEquals(secrets.get("provider/initech"), Some("sk-initech"))
  }
