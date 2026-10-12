package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

final class Squatter extends Workflow[String]:
  def emptyState: String      = ""
  def waitForMe: Effect[Done] = effects.reply(Done)

/**
 * A workflow that names a handler as the engine's own wait would be refused, so it cannot shadow
 * it.
 */
object Squatter
    extends Workflow.Companion[Squatter, String](
      ComponentId("squatter"),
      Codecs.serializer[String]("text")
    ):
  def create(context: WorkflowContext) = new Squatter
  val awaitEnd                         = command("ankka:await-end")(_.waitForMe)

class ReservedWorkflowNamesSuite extends munit.FunSuite:

  test("the engine's wait has a name under the reserved prefix") {
    assert(WorkflowLifecycle.AwaitEnd.startsWith(WorkflowLifecycle.MethodPrefix))
    assertNotEquals(WorkflowLifecycle.AwaitEnd, WorkflowLifecycle.Method)
  }

  test("a workflow handler named as the engine's wait is refused") {
    val refused = intercept[IllegalArgumentException](Squatter.descriptor)
    assert(
      refused.getMessage.contains("'ankka:await-end' uses the reserved 'ankka:' prefix"),
      refused.getMessage
    )
  }
