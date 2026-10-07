package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Records what a run's record says, as a follower would: so the suite never wakes the run by
 * reading it.
 */
final class RunWatcher extends Consumer[RunEvent, Nothing]:
  def onMessage(event: RunEvent): Effect =
    RunWatcher.seen.add(messageContext.subject -> event)
    effects.ignore()

object RunWatcher:
  val seen = ConcurrentLinkedQueue[(String, RunEvent)]()
  val descriptor: ConsumerDescriptor[RunWatcher, RunEvent, Nothing] =
    ConsumerDescriptor(
      ComponentId("run-watcher"),
      ChangeSource.eventsOf(RunEntity),
      None,
      None,
      _ => new RunWatcher,
      1
    )

/**
 * SC-002: a run whose service is restarted once in each of its steps completes, with every model
 * call recorded before a restart made exactly once. A step's turn is interrupted at its gate, so
 * the turn runs again from its start; the step before it, ended and recorded, does not.
 */
class BlueprintRestartSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 6.minutes

  private val model             = TestModelProvider()
  @volatile private var reached = CountDownLatch(1)

  /**
   * How many gate calls may pass: one after each restart, for the turn the new incarnation redoes.
   */
  private val passes = AtomicInteger(0)

  /**
   * Blocks the turn that reaches it until the suite ends. The call from the incarnation that was
   * restarted stays blocked, on a virtual thread of a stopped system: letting it go would have it
   * finish its turn against the shared scripted model and be counted.
   */
  private val gateTool =
    FunctionTool.named("gate").describedAs("Waits for the test.").handle { () =>
      if passes.getAndUpdate(n => (n - 1).max(0)) > 0 then "through"
      else
        reached.countDown()
        Thread.sleep(5.minutes.toMillis)
        "through"
    }

  /**
   * Restarts the service; the new incarnation's redo of the interrupted turn passes the gate once.
   */
  private def restart(): Unit =
    kit.restartService()
    reached = CountDownLatch(1)
    passes.set(1)
  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withBlueprints(_ => BlueprintRegistry.empty.tools(gateTool))
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      AgentRuntime.descriptors :+ RunWatcher.descriptor,
      Seq(agents, ProjectionRuntime())
    )
  override def afterAll(): Unit = if kit != null then kit.stop()

  private val steps = Vector("outline", "draft", "polish")

  private def userText(request: ModelRequest): String =
    request.messages.reverse
      .collectFirst { case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString
      }
      .getOrElse("")

  private def afterTool(request: ModelRequest): Boolean =
    request.messages.lastOption.exists {
      case _: ChatMessage.ToolResults => true
      case _                          => false
    }

  test(
    "SC-002 a run restarted once in each step completes, with no recorded model call made again"
  ) {
    // Every step: a call for its gate, then an answer once through.
    model.whenRequest(r => !afterTool(r))(
      ModelResponse("", Vector(ToolCall("c-gate", "gate", Json.obj())), StopReason.ToolUse)
    )
    model.whenRequest(r => afterTool(r))(ModelResponse("""{"text":"done"}"""))

    val writer = Worker("writer").instructions("Write.").tools("gate").budget(4)
    val shape  = Shape.obj("text" -> Shape.string)
    val brief = steps
      .foldLeft(Blueprint("restart-brief").worker(writer))((bp, s) =>
        bp.step(Step(s).ask("writer").reads("input").result(shape))
      )
    agents.blueprints.register(brief): Unit

    val runId = "restart-run"
    agents.runs.start("restart-brief", Json.obj(), runId): Unit

    steps.foreach { s =>
      assert(reached.await(60, TimeUnit.SECONDS), s"$s did not reach its gate")
      // The next incarnation runs the interrupted turn again from its start, and its gate call
      // passes; the step after it blocks at the gate until the next restart.
      restart()
      // Wait for the step to end, by the follower's record and never by reading the run (R20).
      val ended = System.nanoTime() + 60.seconds.toNanos
      while !RunWatcher.seen.asScala.exists { case (id, e) =>
          id == runId && e
            .isInstanceOf[RunEvent.StepEnded] && e.asInstanceOf[RunEvent.StepEnded].step == s
        } && System.nanoTime() < ended
      do Thread.sleep(100)
    }

    val ended = System.nanoTime() + 60.seconds.toNanos
    while !RunWatcher.seen.asScala.exists { case (id, e) =>
        id == runId && e.isInstanceOf[RunEvent.Ended]
      } && System.nanoTime() < ended
    do Thread.sleep(100)
    val run = agents.runs.get(runId)
    assertEquals(run.status, RunStatus.Completed, run.reason.toString)

    // Each step: one turn interrupted at its gate (one call), one whole turn (two calls): three.
    steps.foreach { s =>
      val calls = model.requests.filter(r => userText(r).contains(s"Step '$s'"))
      assertEquals(
        calls.count(r => !afterTool(r)),
        2,
        s"$s started ${calls.count(r => !afterTool(r))} turns"
      )
      assertEquals(calls.size, 3, s"$s made ${calls.size} model calls")
    }
    // Every ended step was ended once: a recorded step was never done again.
    steps.foreach { s =>
      assertEquals(
        RunWatcher.seen.asScala.count { case (id, e) =>
          id == runId && e
            .isInstanceOf[RunEvent.StepEnded] && e.asInstanceOf[RunEvent.StepEnded].step == s
        },
        1
      )
    }
  }
