package com.thinkmorestupidless.ankka.runtime.secrets

import com.thinkmorestupidless.ankka.core.secrets.DerivedIds

import com.thinkmorestupidless.ankka.sdk.SecretRules

import scala.util.Random

/**
 * The id derivation is the isolation design: a service is admitted to the ids under its prefix and
 * refused the rest. So two names must never share an id, every id must be one Secret Manager takes,
 * and no service's prefix may be the start of another's.
 */
final class DerivedIdsSuite extends munit.FunSuite:

  private val IdShape = "[A-Za-z0-9_-]{1,255}".r

  /** A service secret's alphabet, from `SecretRules`, weighted towards the characters escaped. */
  private val nameAlphabet = ("abcXYZ019-" + "......////").toVector

  /** A project secret's name is a Kubernetes Secret name; an entry a Secret's key. */
  private val secretAlphabet = "abz09.-".toVector
  private val entryAlphabet  = "aZ09._-".toVector

  private def word(random: Random, alphabet: Vector[Char], max: Int): String =
    val length = 1 + random.nextInt(max)
    Vector.fill(length)(alphabet(random.nextInt(alphabet.size))).mkString

  test("two names of one service never share an id, and every id is one Secret Manager takes") {
    val random = Random(38)
    val names  = Vector.fill(20000)(word(random, nameAlphabet, 12)).distinct
    names.foreach(n => assert(SecretRules.nameProblem(n).isEmpty, n))
    val ids = names.map(DerivedIds.service("shop", "cart", _))
    assertEquals(ids.distinct.size, names.size)
    ids.foreach(id => assert(IdShape.matches(id), id))
  }

  test("two entries of a project's secrets never share an id, across secrets") {
    val random = Random(44)
    val pairs = Vector
      .fill(20000)((word(random, secretAlphabet, 6), word(random, entryAlphabet, 6)))
      .distinct
    val ids = pairs.map((secret, entry) => DerivedIds.projectEntry("shop", secret, entry))
    assertEquals(ids.distinct.size, pairs.size)
    ids.foreach(id => assert(IdShape.matches(id), id))
  }

  test("the escape is a prefix code: '.', '/' and '_' never collide with their spelled-out forms") {
    assertNotEquals(DerivedIds.service("p", "s", "a.b"), DerivedIds.service("p", "s", "a_pb"))
    assertNotEquals(DerivedIds.service("p", "s", "a/b"), DerivedIds.service("p", "s", "a_sb"))
    assertNotEquals(
      DerivedIds.projectEntry("p", "a", "b_c"),
      DerivedIds.projectEntry("p", "a_ub", "c")
    )
    assertNotEquals(
      DerivedIds.projectEntry("p", "a.b", "c"),
      DerivedIds.projectEntry("p", "a", "pb_uc")
    )
  }

  test("no service's prefix begins another's, and a service secret's never begins a project's") {
    val prefixes = Vector(
      DerivedIds.servicePrefix("shop", "cart"),
      DerivedIds.servicePrefix("shop", "cart2"),
      DerivedIds.servicePrefix("shop", "car"),
      DerivedIds.servicePrefix("shop-2", "cart"),
      DerivedIds.servicePrefix("shop", "cart-2"),
      DerivedIds.projectPrefix("shop"),
      DerivedIds.projectPrefix("shop-2"),
      DerivedIds.projectPrefix("s")
    )
    for a <- prefixes; b <- prefixes if a != b do assert(!b.startsWith(a), s"'$a' begins '$b'")
  }

  test("an id is under its own prefix and no other service's") {
    val id = DerivedIds.service("shop", "cart", "psp/acme/api-key")
    assert(id.startsWith(DerivedIds.servicePrefix("shop", "cart")))
    assert(!id.startsWith(DerivedIds.servicePrefix("shop", "car")))
    assert(!id.startsWith(DerivedIds.projectPrefix("shop")))
    val entry = DerivedIds.projectEntry("shop", "checkout", "STRIPE_KEY")
    assert(entry.startsWith(DerivedIds.projectPrefix("shop")))
    assert(!entry.startsWith(DerivedIds.projectPrefix("sho")))
  }

  test("the longest name fits, by its digest, and stays under its prefix and unique") {
    val longest = "a" * (SecretRules.MaxNameLength - 1)
    val one     = DerivedIds.service("p" * 63, "s" * 63, longest + ".")
    val two     = DerivedIds.service("p" * 63, "s" * 63, longest + "/")
    assert(IdShape.matches(one), one)
    assert(one.length <= DerivedIds.MaxLength)
    assert(one.startsWith(DerivedIds.servicePrefix("p" * 63, "s" * 63) + "_"), one)
    assertNotEquals(one, two)
  }

  test("a digest id is never an encoded one: after the prefix '__' is followed by hex only there") {
    val long    = DerivedIds.service("shop", "cart", "x" * 253)
    val escaped = DerivedIds.service("shop", "cart", ".x")
    val prefix  = DerivedIds.servicePrefix("shop", "cart")
    assert(
      long.startsWith(prefix + "_") && long
        .drop(prefix.length + 1)
        .forall("0123456789abcdef".contains(_))
    )
    assert(escaped.startsWith(prefix + "_p"), escaped)
  }

  test("a readable name stays readable") {
    assertEquals(
      DerivedIds.service("spinvibe", "payments", "psp/acme/api-key"),
      "s_spinvibe_payments_psp_sacme_sapi-key"
    )
    assertEquals(
      DerivedIds.projectEntry("shop", "checkout", "STRIPE_KEY"),
      "p_shop_checkout__STRIPE_uKEY"
    )
  }

  test("the annotations carry the names, under keys Secret Manager accepts") {
    val KeyShape = "[A-Za-z0-9]([A-Za-z0-9._-]{0,61}[A-Za-z0-9])?".r
    val keys = DerivedIds.serviceAnnotations("p", "s", "n").keys ++
      DerivedIds.entryAnnotations("p", "c", "e").keys
    keys.foreach(k => assert(KeyShape.matches(k), k))
    assertEquals(DerivedIds.serviceAnnotations("p", "s", "a/b")(DerivedIds.NameAnnotation), "a/b")
  }

  test("the backend setting: unset is Postgres, an unknown word is refused by name") {
    assertEquals(SecretBackend.parse(""), Right(SecretBackend.Postgres))
    assertEquals(SecretBackend.parse("postgres"), Right(SecretBackend.Postgres))
    assertEquals(SecretBackend.parse("secret-manager"), Right(SecretBackend.SecretManager))
    val refused = SecretBackend.parse("vault").left.getOrElse(fail("accepted 'vault'"))
    assert(refused.contains("ANKKA_SECRET_BACKEND") && refused.contains("vault"), refused)
  }
