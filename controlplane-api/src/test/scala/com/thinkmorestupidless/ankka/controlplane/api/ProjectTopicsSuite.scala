package com.thinkmorestupidless.ankka.controlplane.api

/**
 * The rules a topic's declaration is checked by, identically in the CLI and the control plane, one
 * case per refusal of `contracts/project-topics.md`, each message exact.
 */
class ProjectTopicsSuite extends munit.FunSuite:

  test("a name of the wrong shape is refused, naming the rule") {
    for name <- Seq("not a topic name", "Transactions", "-leading", "trailing-", "", "a" * 101) do
      assertEquals(
        ProjectTopics.problems(name, 12),
        Vector(
          s"topic '$name': a name is lower-case letters, digits, \"-\" and \".\", starting and " +
            "ending with a letter or digit, at most 100 characters"
        ),
        name
      )
  }

  test("a name the broker can hold is accepted") {
    for name <- Seq("transactions", "cart-graph", "a", "money.v2", "a" * 100) do
      assertEquals(ProjectTopics.problems(name, 12), Vector.empty, name)
  }

  test("partitions outside 1 to 1000 are refused, naming them") {
    for n <- Seq(0, -1, 1001) do
      assertEquals(
        ProjectTopics.problems("transactions", n),
        Vector(s"topic 'transactions': partitions $n is outside the range 1-1000")
      )
    for n <- Seq(1, 1000) do assertEquals(ProjectTopics.problems("transactions", n), Vector.empty)
  }

  test("every problem is reported at once") {
    assertEquals(ProjectTopics.problems("Bad", 0).size, 2)
  }

  test("fewer partitions than the project declares is worded as the contract says") {
    assertEquals(
      ProjectTopics.fewer("transactions", 12, 6),
      "topic 'transactions' has 12 partitions and cannot have fewer; 6 was asked"
    )
  }
