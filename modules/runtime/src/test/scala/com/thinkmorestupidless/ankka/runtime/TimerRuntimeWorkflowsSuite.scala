package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteTimedActionDescriptor,
  RemoteWorkflowDescriptor
}

/**
 * Which workflows a timer runtime's sweeper wakes from a pause. A workflow a process or a module
 * hosts runs on the engine a Scala one does, writes the same wake row when it pauses, and is woken
 * only if the sweeper counts it among its workflows: otherwise its row is retried as a timed action
 * that does not exist, and a pause that outlived its entity never ends.
 */
class TimerRuntimeWorkflowsSuite extends munit.FunSuite:

  private val hosted =
    RemoteWorkflowDescriptor(ComponentId("service-steps"), Map.empty, Set("call"))

  test("a workflow a process or a module hosts is one whose pauses the sweeper wakes") {
    assertEquals(TimerRuntime.workflowsOf(Seq(hosted)), Set(ComponentId("service-steps")))
  }

  test("a service whose only workflow is hosted for a process is given a timer runtime") {
    assert(TimerRuntime.neededBy(Seq(hosted)))
  }

  test("a service with no workflow needs none") {
    val reminder = RemoteTimedActionDescriptor(ComponentId("reminder"), Map.empty)
    assert(!TimerRuntime.neededBy(Seq(reminder)))
  }
