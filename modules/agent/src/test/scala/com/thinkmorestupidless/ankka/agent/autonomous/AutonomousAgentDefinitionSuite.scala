package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.{FunctionTool, Guardrail}
import com.thinkmorestupidless.ankka.core.{ComponentId, ComponentKind}

class AutonomousAgentDefinitionSuite extends munit.FunSuite:

  private val answer = Task.named("answer").describedAs("Answer a question")
  private val review = Task.named("review").describedAs("Review a change")

  private final class Plain(context: AutonomousAgentContext) extends AutonomousAgent(context)

  private def companion(build: AutonomousAgentDefinition => AutonomousAgentDefinition) =
    new AutonomousAgent.Companion[Plain](ComponentId("helper")):
      def create(context: AutonomousAgentContext) = new Plain(context)
      def definition                              = build(define)

  private def problemsOf(build: AutonomousAgentDefinition => AutonomousAgentDefinition): String =
    intercept[IllegalArgumentException](companion(build).descriptor).getMessage

  test("a valid definition registers as an autonomous agent listing what it accepts") {
    val d = companion(
      _.describedAs("Helps")
        .capability(TaskAcceptance.of(answer).maxIterationsPerTask(5))
        .capability(TaskAcceptance.of(review))
    ).descriptor
    assertEquals(d.kind, ComponentKind.AutonomousAgent)
    assertEquals(
      d.definition.acceptances.map(a => a.taskType.name -> a.budget),
      Vector("answer" -> 5, "review" -> 10)
    )
    assertEquals(d.definition.accepted("review").map(_.budget), Some(10))
    assertEquals(d.definition.accepted("other"), None)
  }

  test("every problem is reported at once, naming the agent") {
    val message = problemsOf(identity)
    assert(message.contains("invalid autonomous agent 'helper'"), message)
    assert(message.contains("a description is required"), message)
    assert(message.contains("accepts no task type"), message)
  }

  test("a type accepted twice, a zero budget and a duplicated guardrail are refused") {
    val message = problemsOf(
      _.describedAs("Helps")
        .capability(TaskAcceptance.of(answer))
        .capability(TaskAcceptance.of(answer).maxIterationsPerTask(0))
        .guardrails(Guardrail.maxInputLength(10), Guardrail.maxInputLength(10))
    )
    assert(message.contains("task type 'answer' is accepted 2 times"), message)
    assert(message.contains("at least one iteration"), message)
    assert(message.contains("is declared 2 times"), message)
  }

  test("settings out of range are refused") {
    val message = problemsOf(
      _.describedAs("Helps")
        .capability(TaskAcceptance.of(answer))
        .settings(AutonomousAgentSettings(approachingBudgetAt = 1.5, maxConsecutiveFailures = 0))
    )
    assert(message.contains("approachingBudgetAt"), message)
    assert(message.contains("maxConsecutiveFailures"), message)
  }

  test("tools named like the built-ins, or twice, are refused") {
    def tool(name: String) = FunctionTool.named(name).describedAs("does a thing").handle(() => "ok")
    val problems = AutonomousAgentDefinition.toolProblems(
      Seq(tool("complete_task"), tool("lookup"), tool("lookup"), tool("fail_task"))
    )
    assert(problems.exists(_.contains("'complete_task' is reserved")), problems.toString)
    assert(problems.exists(_.contains("'fail_task' is reserved")), problems.toString)
    assert(problems.exists(_.contains("tool 'lookup' is declared 2 times")), problems.toString)
    assertEquals(AutonomousAgentDefinition.toolProblems(Seq(tool("lookup"))), Vector.empty)
  }
