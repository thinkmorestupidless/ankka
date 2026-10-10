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

  test("the platform's own project id is refused") {
    // A workload's identity is ankka://<project>/<service>, and the control plane's is
    // ankka://platform/controlplane: a tenant project of that id could be issued it.
    assertEquals(
      ProjectId.problems("platform"),
      Vector("project id 'platform' is reserved for the platform's own workloads")
    )
  }

  test("only the reserved id itself is refused, not ids that contain it") {
    for near <- Vector("platforms", "platform-tools", "my-platform", "plat")
    do assert(ProjectId.isValid(near), s"'$near' should be accepted")
  }

  test("the bound leaves room for the default namespace prefix") {
    // "ankka-" + id must fit in a 63 character DNS label.
    assert("ankka-".length + ProjectId.MaxLength <= 63)
  }

  test("the bound is the project's backup bucket's, since feature 041") {
    // platform.backups.<id> must fit in a bucket's 63 characters, and the rehearsal namespace in a label.
    assertEquals(ProjectId.MaxLength, 46)
    assert(ProjectId.problems("a" * 47).exists(_.contains("over the 46")))
    assert("ankka-".length + ProjectId.MaxLength + ProjectId.RehearsalSuffix.length <= 63)
  }

  test("a project id ending in -rehearsal is refused, since it would name another's rehearsals") {
    val problems = ProjectId.problems("shop-rehearsal")
    assertEquals(problems.size, 1)
    assert(problems.head.contains("-rehearsal"), problems.head)
    assert(ProjectId.isValid("rehearsals"))
    assert(ProjectId.isValid("rehearsal-tools"))
  }
