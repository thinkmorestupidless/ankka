package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import org.apache.pekko.cluster.{Cluster, MemberStatus}

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A rolling replacement, without Kubernetes: a task is being worked on one node when that node
 * leaves, and the node that remains finishes it — no recorded model call repeated.
 */
class AutonomousAgentHandoffSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  test("the node that remains finishes a task the leaving node was working") {
    val model = TestModelProvider()
    val kit = AnkkaTestKit.start(
      Seq(Answerer.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime())
    )
    try
      Answerer.calls.clear()
      val latch = CountDownLatch(1)
      Answerer.gate.set(latch)
      model
        .expectToolCall("lookup", Json.obj("topic" -> Json.str("hold-handoff")))
        .expectCompleteTask(Answer("handed over", List("lookup")))

      // One node: the instance can only be here.
      val id =
        kit.componentClient.forAutonomousAgent(Answerer).runSingleTask(Tasks.answer, "Hand me over")
      kit.eventually("the tool is running")(
        Option.when(Answerer.calls.contains("lookup(hold-handoff)"))(())
      )

      val peer = kit.startPeer(Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime()))
      try
        kit.eventually("two members up") {
          val up = Cluster(kit.service.system).state.members.count(_.status == MemberStatus.Up)
          Option.when(up == 2)(())
        }

        // The first node leaves, gracefully, mid-tool; then the tool is let go.
        kit.service.terminate()
        scala.concurrent.Await.ready(kit.service.whenTerminated, 60.seconds): Unit
        latch.countDown()

        val done = peer.componentClient.forTask(id).await(Tasks.answer, 90.seconds)
        assertEquals(done.status, TaskStatus.Completed)
        assertEquals(done.result, Some(Answer("handed over", List("lookup"))))
        assertEquals(model.callCount, 2)
        assertEquals(Answerer.calls.asScala.count(_ == "lookup(hold-handoff)"), 2)
      finally peer.stop()
    finally kit.stop()
  }
