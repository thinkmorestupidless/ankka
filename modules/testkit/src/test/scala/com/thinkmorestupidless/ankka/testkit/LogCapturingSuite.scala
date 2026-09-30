package com.thinkmorestupidless.ankka.testkit

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import munit.MUnitRunner
import org.junit.runner.notification.RunNotifier
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

// The suites LogCapturingSuite runs. Abstract, so that sbt does not discover and run them itself (one
// fails on purpose); the suite runs each as an anonymous subclass.

abstract class PassingThenFailing(log: org.slf4j.Logger) extends munit.FunSuite with LogCapturing:
  test("passes")(log.info("from the passing test"))
  test("fails") {
    log.info("from the failing test")
    fail("on purpose")
  }
  test("passes again")(log.info("from the second passing test"))

abstract class SetupFails(log: org.slf4j.Logger) extends munit.FunSuite with LogCapturing:
  override def beforeAll(): Unit =
    log.info("from a setup that fails")
    throw IllegalStateException("on purpose")
  test("never runs")(log.info("from a test that never runs"))

abstract class EachSetupFails(log: org.slf4j.Logger) extends munit.FunSuite with LogCapturing:
  override def beforeEach(context: BeforeEach): Unit =
    log.info(s"from the setup of ${context.test.name}")
    throw IllegalStateException("on purpose")
  test("its body never runs")(log.info("from a body that never runs"))

/**
 * What reaches the console with `LogCapturing` mixed in. A `ListAppender` on the root logger stands
 * in for the console: capture moves it aside with the real one, and replays to both.
 */
class LogCapturingSuite extends munit.FunSuite:

  private val log = LoggerFactory.getLogger("capture-test")

  private val root =
    LoggerFactory.getILoggerFactory
      .asInstanceOf[LoggerContext]
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

  private def run(suite: () => munit.Suite, cls: Class[? <: munit.Suite]): List[String] =
    val console = ListAppender[ILoggingEvent]()
    console.setContext(root.getLoggerContext)
    console.start()
    root.addAppender(console)
    try
      MUnitRunner(cls, suite).run(RunNotifier())
      console.list.asScala.toList.map(_.getFormattedMessage)
    finally
      val _ = root.detachAppender(console)

  private def appenders(logger: Logger) = logger.iteratorForAppenders().asScala.toList

  test("a passing test prints nothing it logged; a failing one prints what it logged") {
    val printed = run(() => new PassingThenFailing(log) {}, classOf[PassingThenFailing])
    assertEquals(printed, List("from the failing test"))
  }

  test("a suite whose setup failed prints what the setup logged") {
    val printed = run(() => new SetupFails(log) {}, classOf[SetupFails])
    assertEquals(printed, List("from a setup that fails"))
  }

  test("a test whose beforeEach failed prints what its setup logged") {
    val printed = run(() => new EachSetupFails(log) {}, classOf[EachSetupFails])
    assertEquals(printed, List("from the setup of its body never runs"))
  }

  test("the root logger's appenders are put back when the suite ends") {
    val before = appenders(root)
    val _      = run(() => new PassingThenFailing(log) {}, classOf[PassingThenFailing])
    assertEquals(appenders(root), before)
    // And a suite without capture, running after one with it, prints as it always did.
    val console = ListAppender[ILoggingEvent]()
    console.setContext(root.getLoggerContext)
    console.start()
    root.addAppender(console)
    try
      LoggerFactory.getLogger("capture-test").info("after the capturing suite")
      assertEquals(
        console.list.asScala.toList.map(_.getFormattedMessage),
        List("after the capturing suite")
      )
    finally
      val _ = root.detachAppender(console)
  }

  test("ankka.test.logs=all turns capture off") {
    sys.props("ankka.test.logs") = "all"
    try
      val printed = run(() => new PassingThenFailing(log) {}, classOf[PassingThenFailing])
      assertEquals(
        printed,
        List("from the passing test", "from the failing test", "from the second passing test")
      )
    finally
      val _ = sys.props.remove("ankka.test.logs")
  }
