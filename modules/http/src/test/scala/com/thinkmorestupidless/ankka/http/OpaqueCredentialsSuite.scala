package com.thinkmorestupidless.ankka.http

/**
 * The shape a client key shares with a deploy token (feature 054), minted and checked in one place.
 */
class OpaqueCredentialsSuite extends munit.FunSuite:

  private val keys = OpaqueCredentials("ankka_ck_")

  private def isHex(s: String) = s.forall(c => c.isDigit || ('a' to 'f').contains(c))

  test("a minted key is the prefix, 16 hex of id and 64 hex of secret, and parses back") {
    val minted = keys.mint()
    assertEquals(minted.presented, s"ankka_ck_${minted.id}${minted.secret}")
    assertEquals(minted.id.length, 16)
    assertEquals(minted.secret.length, 64)
    assert(isHex(minted.id) && isHex(minted.secret), minted.presented)
    assertEquals(keys.parse(minted.presented), Some((minted.id, minted.secret)))
  }

  test("the digest is lower-case hex SHA-256 of the secret half") {
    val minted = keys.mint()
    val expected = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(minted.secret.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(minted.digest, expected)
    assertEquals(keys.digest(minted.secret), expected)
    assert(!minted.digest.contains(minted.secret))
  }

  test("matches the minted secret, and not a one-character change") {
    val minted = keys.mint()
    assert(keys.matches(minted.digest, minted.secret))
    val changed = minted.secret.init :+ (if minted.secret.last == 'a' then 'b' else 'a')
    assert(!keys.matches(minted.digest, changed.mkString))
    assert(!keys.matches(minted.digest, ""))
  }

  test("looks like one by its prefix alone, and parses only the exact shape") {
    val minted = keys.mint()
    assert(keys.looksLikeOne("ankka_ck_nonsense"))
    assert(!keys.looksLikeOne("ankka_0123"))
    Vector(
      "ankka_ck_",
      s"ankka_ck_${minted.id}",
      s"ankka_ck_${minted.id}${minted.secret}a",
      s"ankka_ck_${minted.id}_${minted.secret}",
      s"ankka_ck_${minted.id.toUpperCase}${minted.secret}",
      s" ankka_ck_${minted.id}${minted.secret}"
    ).foreach(value => assertEquals(keys.parse(value), None, value))
  }

  test("a deploy token presented as a client key is refused") {
    val deploy = OpaqueCredentials("ankka_", separator = "_").mint()
    assertEquals(keys.parse(deploy.presented), None)
    assert(!keys.looksLikeOne(deploy.presented))
  }
