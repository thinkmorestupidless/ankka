package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokens

/** The credential's shape, and what may and may not be mistaken for one. */
class DeployTokensSuite extends munit.FunSuite:

  test("a minted token has the documented shape, and its parts round-trip") {
    val minted = DeployTokens.mint()
    assertEquals(minted.presented, s"ankka_${minted.id}_${minted.secret}")
    assertEquals(minted.id.length, 16)
    assertEquals(minted.secret.length, 64)
    assert(minted.id.forall(c => c.isDigit || ('a' to 'f').contains(c)), minted.id)
    assert(minted.secret.forall(c => c.isDigit || ('a' to 'f').contains(c)), minted.secret)
    assertEquals(DeployTokens.parse(minted.presented), Some((minted.id, minted.secret)))
  }

  test("two mints do not collide") {
    val many = (1 to 500).map(_ => DeployTokens.mint().presented).toSet
    assertEquals(many.size, 500)
  }

  test("the digest verifies the secret it was made from, and nothing else") {
    val minted = DeployTokens.mint()
    assertEquals(minted.digest, DeployTokens.digest(minted.secret))
    assert(DeployTokens.matches(minted.digest, minted.secret))

    // One character different, in the first position and the last.
    val head = (if minted.secret.head == 'a' then 'b' else 'a') +: minted.secret.tail
    val tail = minted.secret.init :+ (if minted.secret.last == 'a' then 'b' else 'a')
    assert(!DeployTokens.matches(minted.digest, head.mkString))
    assert(!DeployTokens.matches(minted.digest, tail.mkString))
    assert(!DeployTokens.matches(minted.digest, ""))
  }

  test("the digest is not the secret") {
    val minted = DeployTokens.mint()
    assertNotEquals(minted.digest, minted.secret)
    assert(!minted.digest.contains(minted.secret))
  }

  test("a JWT is not a deploy token, and neither is a near miss") {
    val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl"
    assertEquals(DeployTokens.parse(jwt), None)
    assert(!DeployTokens.looksLikeOne(jwt))

    val minted = DeployTokens.mint()
    // Right prefix, wrong body: these must be refused as malformed rather than fall through to
    // the OIDC verifier, which is what `looksLikeOne` is for.
    val nearMisses = Vector(
      "ankka_",
      "ankka_short_short",
      s"ankka_${minted.id}",                              // no secret
      s"ankka_${minted.id}_${minted.secret.tail}",        // one character short
      s"ankka_${minted.id}_${minted.secret}a",            // one character long
      s"ankka_${minted.id.toUpperCase}_${minted.secret}", // not lowercase
      s"ankka_${minted.id}_${minted.secret.init}Z",       // not hex
      s" ankka_${minted.id}_${minted.secret}",            // leading space
      s"ankka_${minted.id}_${minted.secret} "             // trailing space
    )
    nearMisses.foreach(value => assertEquals(DeployTokens.parse(value), None, value))
    nearMisses
      .filter(_.startsWith("ankka_"))
      .foreach(value => assert(DeployTokens.looksLikeOne(value), value))
  }

  test("a presented token contains no character that needs quoting in a shell or YAML") {
    val presented = DeployTokens.mint().presented
    val awkward   = Set('.', '+', '/', '=', '-', '"', '\'', '$', '`', '\\', ' ')
    assert(!presented.exists(awkward.contains), presented)
  }
