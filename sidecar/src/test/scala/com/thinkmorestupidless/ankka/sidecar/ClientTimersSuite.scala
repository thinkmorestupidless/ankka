package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.runtime.TimerRuntime
import com.thinkmorestupidless.ankka.sidecar.wasm.HostImports
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, TimerProbe}
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * A recurring timer as a process and a module set one: `ClientLogic.scheduleRecurring`, which the
 * gRPC service and the module import both delegate to, against a real database.
 *
 * No timed action is registered, so no sweeper runs and a timer stays as it was set.
 */
class ClientTimersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private val probe                 = TimerProbe()
  private val timers                = TimerRuntime(200.millis, probe)

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq.empty, Seq(timers))
    probe.bind(testKit): Unit

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def logic(running: Boolean = true): ClientLogic =
    given ActorSystem[?] = testKit.service.system
    ClientLogic(testKit.service, settings, () => Option.when(running)(timers.timerScheduler))

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 30.seconds)

  private def request(
      id: String,
      period: Long = 60_000L,
      delay: Long = 5_000L,
      payload: Array[Byte] = "c1".getBytes
  ) =
    ScheduleRecurringRequest(
      timerId = id,
      delayMillis = delay,
      periodMillis = period,
      componentId = "reminder",
      name = "tick",
      payload = Some(pb.Payload(manifest = "string", data = ByteString.copyFrom(payload)))
    )

  test("a recurring timer is scheduled with its period and its first due") {
    val before = java.time.Instant.now()
    assertEquals(await(logic().scheduleRecurring(request("r1"))).error, None)
    val timer = probe.scheduled("r1").getOrElse(fail("nothing was scheduled"))
    assertEquals(timer.period, Some(60.seconds))
    assert(!timer.dueTime.isBefore(before.plusMillis(4_999)), s"${timer.dueTime}")
    assertEquals(timer.target.method.toString, "tick")
  }

  test("the same recurring timer again keeps its next due") {
    val client = logic()
    assertEquals(await(client.scheduleRecurring(request("r2"))).error, None)
    val first = probe.scheduled("r2").get.dueTime
    assertEquals(await(client.scheduleRecurring(request("r2", delay = 50_000L))).error, None)
    assertEquals(probe.scheduled("r2").get.dueTime, first)
  }

  test("a period of zero is refused in the reply, naming the timer, and nothing is stored") {
    val reply = await(logic().scheduleRecurring(request("r3", period = 0L)))
    val error = reply.error.getOrElse(fail("the period was accepted"))
    assertEquals(error.code, pb.ErrorCode.BAD_REQUEST)
    assert(error.message.contains("r3"), error.message)
    assertEquals(probe.scheduled("r3"), None)
  }

  test("an empty timer id and a payload over the limit are refused in the reply") {
    val client = logic()
    assertEquals(
      await(client.scheduleRecurring(request(""))).error.map(_.code),
      Some(pb.ErrorCode.BAD_REQUEST)
    )
    val big = new Array[Byte](TimerRuntime.MaxPayloadBytes + 1)
    // The whole Payload is what is stored, so its size is the payload's and its manifest's.
    val refused = await(client.scheduleRecurring(request("r4", payload = big))).error
    assertEquals(refused.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
    assertEquals(probe.scheduled("r4"), None)
  }

  test("a component or handler name that does not parse is refused in the reply") {
    val reply = await(logic().scheduleRecurring(request("r5").copy(componentId = "")))
    assertEquals(reply.error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
  }

  test("with timers not running, the answer is unavailable") {
    val reply = await(logic(running = false).scheduleRecurring(request("r6")))
    assertEquals(reply.error.map(_.code), Some(pb.ErrorCode.UNAVAILABLE))
  }

  test("a module may import schedule_recurring, which answers through the client once bound") {
    val imports = HostImports(5.seconds, 5.seconds, Map.empty[String, String].get)
    assert(imports.values.functions().map(_.name()).contains("schedule_recurring"))
    // Before the service is bound: unavailable, in the reply.
    val early =
      ScheduleRecurringReply.parseFrom(imports.scheduleRecurring(request("m1").toByteArray))
    assertEquals(early.error.map(_.code), Some(pb.ErrorCode.UNAVAILABLE))
    imports.bind(logic())
    val bound =
      ScheduleRecurringReply.parseFrom(imports.scheduleRecurring(request("m1").toByteArray))
    assertEquals(bound.error, None)
    assertEquals(probe.scheduled("m1").flatMap(_.period), Some(60.seconds))
    // A refused period is a reply the guest reads, not a trap.
    val refused =
      ScheduleRecurringReply.parseFrom(
        imports.scheduleRecurring(request("m2", period = -1L).toByteArray)
      )
    assertEquals(refused.error.map(_.code), Some(pb.ErrorCode.BAD_REQUEST))
  }

  test("a recurring timer is cancelled by the cancel every timer has") {
    val client = logic()
    assertEquals(await(client.scheduleRecurring(request("r7"))).error, None)
    val _ = await(client.cancel(CancelRequest("r7")))
    assertEquals(probe.scheduled("r7"), None)
  }
