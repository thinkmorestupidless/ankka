package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.util.Base64

class SecretCipherSuite extends munit.FunSuite:

  private val key   = SecretKey.generate()
  private val other = SecretKey.generate()

  test("a value round-trips under its name") {
    val stored = SecretCipher.encrypt(key, "provider/acme", "sk-acme-1")
    assertEquals(SecretCipher.decrypt(key, "provider/acme", stored), "sk-acme-1")
  }

  test("the stored form is a version byte, a nonce, the ciphertext and a tag") {
    val value  = "sk-acme-1"
    val stored = SecretCipher.encrypt(key, "acme", value)
    assertEquals(stored(0), 0x01.toByte)
    assertEquals(stored.length, 1 + 12 + value.length + 16)
    assert(!String(stored, "ISO-8859-1").contains(value))
  }

  test("two encryptions of one value differ, by their nonce") {
    val a = SecretCipher.encrypt(key, "acme", "sk-acme-1")
    val b = SecretCipher.encrypt(key, "acme", "sk-acme-1")
    assert(!a.sameElements(b))
  }

  test("another key fails, saying the key is not the one the secret was kept with") {
    val stored = SecretCipher.encrypt(key, "acme", "sk-acme-1")
    val error  = intercept[CommandError](SecretCipher.decrypt(other, "acme", stored))
    assertEquals(error.code, ErrorCode.Internal)
    assert(error.message.contains("not the one 'acme' was kept with"), error.message)
  }

  test("a value moved under another name does not decrypt") {
    val stored = SecretCipher.encrypt(key, "acme", "sk-acme-1")
    intercept[CommandError](SecretCipher.decrypt(key, "globex", stored))
  }

  test("a changed byte does not decrypt") {
    val stored = SecretCipher.encrypt(key, "acme", "sk-acme-1")
    stored(stored.length - 1) = (stored(stored.length - 1) ^ 1).toByte
    intercept[CommandError](SecretCipher.decrypt(key, "acme", stored))
  }

  test("an unknown version is refused, naming it") {
    val stored = SecretCipher.encrypt(key, "acme", "sk-acme-1")
    stored(0) = 0x02
    val error = intercept[CommandError](SecretCipher.decrypt(key, "acme", stored))
    assert(error.message.contains("version 0x02"), error.message)
  }

  test("a key must be the base64 of 32 bytes, and the refusal names the variable") {
    val notBase64 = SecretKey.parse("not a key!")
    assert(notBase64.left.exists(_.contains("ANKKA_SECRET_KEY")), notBase64)
    assert(notBase64.left.exists(_.contains("32 bytes")), notBase64)
    val tooShort = SecretKey.parse(Base64.getEncoder.encodeToString(new Array[Byte](16)))
    assert(tooShort.left.exists(_.contains("16 bytes")), tooShort)
    assert(SecretKey.parse(key.encoded).isRight)
  }

  test("a key never shows its bytes") {
    assertEquals(key.toString, "SecretKey(<32 bytes>)")
    assert(!SecretKey.parse("not a key!").left.exists(_.contains("not a key")))
  }
