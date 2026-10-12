package com.thinkmorestupidless.ankka.core

/** A workflow's end as a caller reads it from a `WorkflowFailed` error's details. */
class WorkflowEndSuite extends munit.FunSuite:

  test("a failed workflow's error names the step and the reason") {
    val error = CommandError(
      "workflow quote 'q2' failed at step 'margin': no rates",
      ErrorCode.WorkflowFailed,
      WorkflowEnd.Failure(Some("margin"), "no rates", deleted = false).details
    )
    assertEquals(
      WorkflowEnd.failure(error),
      Some(WorkflowEnd.Failure(Some("margin"), "no rates", deleted = false))
    )
    assertEquals(error.details, Map("step" -> "margin", "reason" -> "no rates"))
  }

  test("a deleted workflow's error says so and names no step") {
    val failure = WorkflowEnd.Failure(None, "the workflow was deleted", deleted = true)
    val error   = CommandError("gone", ErrorCode.WorkflowFailed, failure.details)
    assertEquals(WorkflowEnd.failure(error), Some(failure))
    assertEquals(error.details.get("deleted"), Some("true"))
    assert(!error.details.contains("step"))
  }

  test("any other code describes no workflow's end, whatever its details") {
    assertEquals(
      WorkflowEnd.failure(CommandError("no", ErrorCode.Conflict, Map("step" -> "x"))),
      None
    )
    assertEquals(WorkflowEnd.failure(CommandError("slow", ErrorCode.Timeout)), None)
  }

  test("a WorkflowFailed with no details still has a reason: its message") {
    assertEquals(
      WorkflowEnd.failure(CommandError("it failed", ErrorCode.WorkflowFailed)),
      Some(WorkflowEnd.Failure(None, "it failed", deleted = false))
    )
  }

  test("a failed workflow is not something to retry unchanged") {
    assert(!ErrorCode.WorkflowFailed.retryable)
  }

  test("an error made without details has none, as every error before them") {
    assertEquals(CommandError("x").details, Map.empty[String, String])
  }
