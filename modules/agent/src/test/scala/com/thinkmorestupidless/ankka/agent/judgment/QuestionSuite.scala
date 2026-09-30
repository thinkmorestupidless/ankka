package com.thinkmorestupidless.ankka.agent.judgment

/** Every rule a question is held to, refused where the question is built and naming it. */
class QuestionSuite extends munit.FunSuite:

  private def refused(expected: String)(body: => Any)(using munit.Location): Unit =
    val failure = intercept[IllegalArgumentException](body)
    assert(failure.getMessage.contains(expected), failure.getMessage)

  private val two = Seq("a" -> "the first", "b" -> "the second")

  test("an empty id or instructions is refused, naming the question") {
    refused("question '': an id is required")(Question.yesNo("", "Is it?"))
    refused("question 'q': instructions are required")(Question.yesNo("q", " "))
    refused("question 'q': instructions are required")(Question.score("q", "")("low", "high"))
  }

  test("a choice needs two to 255 options") {
    refused("question 'c': a choice needs at least two options")(
      Question.choiceByKey("c", "Which?")("a" -> "the only one")
    )
    val many = (1 to 256).map(i => s"k$i" -> s"option $i")
    refused("question 'c': a choice may offer at most 255 options")(
      Question.choiceByKey("c", "Which?")(many*)
    )
    assertEquals(Question.choiceByKey("c", "Which?")(many.take(255)*).options.size, 255)
  }

  test("a choice's keys and values are unique, and every option has a key and a description") {
    refused("option key 'a' is used twice")(
      Question.choiceByKey("c", "Which?")("a" -> "one", "a" -> "two")
    )
    refused("the value Billing is offered twice")(
      Question.choice[Team]("c", "Which?")(
        Team.Billing -> ("billing", "Payments"),
        Team.Billing -> ("money", "Also payments")
      )
    )
    refused("every option needs a key")(Question.choiceByKey("c", "Which?")("" -> "none", two.head))
    refused("option 'b' needs a description")(
      Question.choiceByKey("c", "Which?")("a" -> "one", "b" -> "")
    )
  }

  test("a score needs two to ten levels, each described") {
    refused("question 's': a score needs at least two levels")(
      Question.score("s", "How much?")("only")
    )
    refused("question 's': a score may have at most 10 levels")(
      Question.score("s", "How much?")((0 to 10).map(i => s"level $i")*)
    )
    refused("question 's': level 1 needs a description")(
      Question.score("s", "How much?")("low", "")
    )
  }

  test("a yes/no that is described needs both descriptions") {
    refused("describing needs both a yes and a no")(
      Question.yesNo("y", "Is it?").describing(yes = "It is", no = "")
    )
  }

  test("a valid question of each kind exposes what was declared, in order") {
    val route = Questions.route
    assertEquals(route.id, "route")
    assertEquals(route.options.map(_.key), Vector("billing", "technical", "sales"))
    assertEquals(route.options.map(_.value), Vector(Team.Billing, Team.Technical, Team.Sales))
    assertEquals(route.option("technical").map(_.value), Some(Team.Technical))
    assertEquals(route.optionFor(Team.Sales).map(_.key), Some("sales"))

    assertEquals(Questions.frustration.levels.head, "Calm, stating facts")
    assertEquals(Questions.frustration.top, 3)

    assertEquals(Questions.refund.yes, None)
    assertEquals(Questions.urgent.yes, Some("A deadline, an outage or money at risk"))
    assertEquals(Questions.urgent.no, Some("It can wait"))
  }

  test("a choice by key reads back its key") {
    val topic = Question.choiceByKey("topic", "What is it about?")(two*)
    assertEquals(topic.options.map(o => o.value -> o.key), Vector("a" -> "a", "b" -> "b"))
  }

  test("a choice over an enumeration need not offer every case") {
    val notSales = Question.choice[Team]("support", "Which support team?")(
      Team.Billing   -> ("billing", "Payments"),
      Team.Technical -> ("technical", "Bugs")
    )
    assertEquals(notSales.optionFor(Team.Sales), None)
  }
