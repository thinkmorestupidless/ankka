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

  // features/topics/contracts.feature (feature 037)
  private def schema(text: String) =
    com.thinkmorestupidless.ankka.core.graph.GraphJson.parse(text.getBytes("UTF-8")).toOption.get

  test("a declaration with a contract and its schema is accepted") {
    val request = TopicDeclarationRequest(
      3,
      contract = Some(ContractDeclaration("order.v1", schema("""{"type":"object"}""")))
    )
    assertEquals(ProjectTopics.problems("orders", request), Vector.empty)
    assertEquals(
      ProjectTopics.problems("orders", TopicDeclarationRequest(3, compacted = true)),
      Vector.empty
    )
  }

  test("a contract name outside the rule is refused, naming it") {
    for name <- Seq("Order", ".v1", "a", "order v1") do
      val request =
        TopicDeclarationRequest(3, contract = Some(ContractDeclaration(name, schema("{}"))))
      assertEquals(
        ProjectTopics.problems("orders", request),
        Vector(
          s"topic 'orders': contract name '$name' is not ${com.thinkmorestupidless.ankka.core.Contract.NameRule}"
        ),
        name
      )
  }

  test("a schema larger than 64 KiB is refused, naming its size") {
    val big = "{" + (1 to 2000).map(i => s""""f$i":"${"x" * 30}"""").mkString(",") + "}"
    val request =
      TopicDeclarationRequest(3, contract = Some(ContractDeclaration("order.v1", schema(big))))
    val problems = ProjectTopics.problems("orders", request)
    assertEquals(problems.size, 1)
    assert(problems.head.startsWith("topic 'orders': the schema of 'order.v1' is "), problems.head)
    assert(problems.head.endsWith(" bytes, more than 65536"), problems.head)
  }

  test("the topic's own problems come with the contract's") {
    val request =
      TopicDeclarationRequest(0, contract = Some(ContractDeclaration("Bad", schema("{}"))))
    assertEquals(ProjectTopics.problems("orders", request).size, 2)
  }
