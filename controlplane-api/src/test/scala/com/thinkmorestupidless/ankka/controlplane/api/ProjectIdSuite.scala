package com.thinkmorestupidless.ankka.controlplane.api

/**
 * A project id has to survive becoming a Kubernetes namespace name.
 *
 * `ServiceKey` has always documented project ids as DNS labels; until now nothing checked it.
 */
class ProjectIdSuite extends munit.FunSuite:

  test("an ordinary id is accepted") {
    assert(ProjectId.isValid("checkout"))
    assert(ProjectId.isValid("checkout-v2"))
    assert(ProjectId.isValid("a1"))
  }

  test("an empty id is rejected") {
    assertEquals(ProjectId.problems(""), Vector("project id must not be empty"))
  }

  test("an id that is not a DNS label is rejected") {
    // Each of these would produce a namespace name the API server refuses.
    for bad <- Vector("Checkout", "1checkout", "check_out", "checkout-", "check out", "che.ckout")
    do assert(ProjectId.problems(bad).nonEmpty, s"'$bad' should be rejected")
  }

  test("an id too long for a namespace name is rejected") {
    val tooLong = "a" * (ProjectId.MaxLength + 1)
    assert(ProjectId.problems(tooLong).exists(_.contains("over the")))
    assert(ProjectId.isValid("a" * ProjectId.MaxLength))
  }

  test("the bound leaves room for the default namespace prefix") {
    // "ankka-" + id must fit in a 63 character DNS label.
    assertEquals("ankka-".length + ProjectId.MaxLength, 63)
  }
