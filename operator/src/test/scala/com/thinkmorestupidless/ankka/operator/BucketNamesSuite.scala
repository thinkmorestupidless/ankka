package com.thinkmorestupidless.ankka.operator

/**
 * The contract's rule for a bucket's name in Google Cloud Storage (feature 039), as test code holds
 * it.
 */
class BucketNamesSuite extends munit.FunSuite:

  private val Valid = "[a-z0-9-]+".r

  test("a name is the prefix, the project, the service and eight hex characters of digest") {
    val n = BucketNames.name("ankka", "casino", "kyc")
    assertEquals(n, s"ankka-casino-kyc-${BucketNames.digest("casino", "kyc")}")
    assertEquals(BucketNames.digest("casino", "kyc").length, 8)
    assert(Valid.matches(n), n)
  }

  test("two pairs whose project and name join to the same hyphenated name are given two names") {
    // names.feature: "b" in "shop-a" and "a-b" in "shop" both read shop-a-b without a dot.
    val one = BucketNames.name("t", "shop-a", "b")
    val two = BucketNames.name("t", "shop", "a-b")
    assertNotEquals(one, two)
    assertEquals(one.dropRight(9), two.dropRight(9))
  }

  test("a pair too long to fit is shortened, service first, and keeps its whole digest") {
    val project = "p" * 40
    val service = "s" * 40
    val n       = BucketNames.name("ankka", project, service)
    assertEquals(n.length, BucketNames.MaxLength)
    assert(n.endsWith(BucketNames.digest(project, service)), n)
    assert(n.startsWith(s"ankka-${"p" * 40}-s"), n)
    assert(Valid.matches(n), n)
  }

  test("a project too long for the service to keep more than one character shortens too") {
    val n = BucketNames.name("ankka", "p" * 63, "service")
    assertEquals(n.length, BucketNames.MaxLength)
    assert(n.contains("-s-"), n)
  }

  test("a name that fits is never shortened") {
    val n = BucketNames.name("ankka", "casino", "kyc-documents")
    assert(n.contains("-casino-kyc-documents-"), n)
  }
