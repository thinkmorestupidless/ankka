package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.{CartRows, CartTimers, KindRefs}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Java samples of the testing, timers and views pages: the kits, and what they show. */
class DocsTestingSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  test("the view kit writes the row one change leads to") {
    assertEquals(ViewKits.cartRow().toScala, Some(CartRows.Row("c1", 1, false)))
  }

  test("the keyed view kit answers the view's query as the test says, and writes the rows named") {
    val kit = ViewKits.rightNotesEveryRow()
    assertEquals(kit.row("r1").toScala.map(_.notes.asScala.toList), Some(List("left", "right")))
    assertEquals(kit.row("r2").toScala.map(_.notes.asScala.toList), Some(List("left", "right")))
    assertEquals(kit.row("r3").toScala.map(_.notes.asScala.toList), Some(List("left")))
  }

  test("the workflow kit runs a command's steps by hand until the pause") {
    val started = WorkflowKits.startCheckout()
    assertEquals(started.reply, Done.done())
    assertEquals(started.steps.asScala.toList, List("reserve", "charge"))
    assert(started.lifecycle.isPaused, started.lifecycle.toString)
    assertEquals(started.pausedFor.toScala, Some(Duration.ofMinutes(1)))
    assertEquals(started.onTimeout.toScala, Some("expire"))
    assertEquals(started.state.status, "awaiting")
  }

  test("the whole service projects the view, read through the view client") {
    val kit = ViewKits.cartService()
    try
      kit.componentClient
        .forEventSourcedEntity("c-7")
        .call(KindRefs.addItem)
        .invoke(LineItem("p1", "Pen", 2)): Unit
      kit.eventually(
        Duration.ofSeconds(20),
        () =>
          assertEquals(
            ViewCalls.row(kit.viewClient, "c-7").toScala,
            Some(CartRows.Row("c-7", 1, false))
          )
      )
      assert(ViewCalls.rows(kit.viewClient).asScala.exists(_.cartId == "c-7"))
    finally kit.close()
  }

  test("two installations replicate a ledger, and the follower holds the mirrored entries") {
    val state = FleetKits.mirrored()
    assertEquals(
      state.entries.asScala.toList,
      List("step-1@smoke-a (mirrored)", "step-2@smoke-a (mirrored)", "step-3@smoke-a (mirrored)")
    )
  }

  test("a scripted model answers the tool call and the text, in order, and the tool runs") {
    assertEquals(ModelChecks.weatherWithScriptedModel(), "Lisbon is 18C and sunny.")
    assert(
      WeatherAgent.TOOL_CALLS.contains("get_weather(Lisbon,-)"),
      WeatherAgent.TOOL_CALLS.toString
    )
  }

  test("a recurring timer's due times are a period apart, whenever its runs started") {
    val kit = AnkkaTestKit.start(Ankka.service("timers").register(classOf[CartTimers]))
    try
      val due = TimerChecks.threeDueTimes(kit).asScala.toList
      assert(due.size >= 3, due.toString)
      assertEquals(due(1), due(0).plusSeconds(1))
      assertEquals(due(2), due(1).plusSeconds(1))
    finally kit.close()
  }
