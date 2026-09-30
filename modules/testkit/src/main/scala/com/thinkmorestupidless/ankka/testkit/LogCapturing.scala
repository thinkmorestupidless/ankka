package com.thinkmorestupidless.ankka.testkit

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.{Appender, AppenderBase}
import munit.{AnyFixture, Fixture, FunSuite, TestValues}
import org.slf4j.LoggerFactory

import java.util.ArrayDeque
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/**
 * Keeps a munit suite's log quiet unless something fails.
 *
 * While the suite runs, everything logged through logback's root logger is held in memory instead
 * of printed. A test that passes discards what was logged since the previous test finished; a test
 * that fails prints it, under a header naming the test, so the log that explains a failure is still
 * there and the log of a hundred passing tests is not. A suite whose setup failed, so that no test
 * ran, prints what its setup logged.
 *
 * {{{
 * class CartIntegrationSuite extends munit.FunSuite with LogCapturing:
 *   ...
 * }}}
 *
 * Levels still come from the logback configuration: capture changes where an event goes, not
 * whether it is logged. It starts when the suite is constructed, which munit does just before
 * running it, and ends as the suite finishes, so a suite after it in the same JVM that does not mix
 * this in logs as it always did. The suite's own `afterAll` runs after capture has ended, so what a
 * teardown logs is printed.
 *
 * Set `ANKKA_TEST_LOGS=all` (or `-Dankka.test.logs=all` on the test JVM) to turn capture off and
 * see everything, as when debugging a test that passes. Without logback, or with no appender on the
 * root logger, capture does nothing.
 */
trait LogCapturing extends FunSuite:

  LogCapture.start()

  // munit runs one test at a time within a suite; these are read on the runner's threads.
  @volatile private var completed = 0
  @volatile private var bodyRan   = false

  // The nearest named class: an anonymous subclass's simple name is empty.
  private def suiteName =
    Iterator
      .iterate[Class[?]](getClass)(_.getSuperclass)
      .find(!_.isAnonymousClass)
      .get
      .getSimpleName

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "log capturing",
      test =>
        test.withBodyMap(
          _.transform { result =>
            bodyRan = true
            completed += 1
            result match
              case Failure(_) | Success(_: TestValues.FlakyFailure) =>
                LogCapture.print(s"$suiteName: ${test.name}")
              case _ => LogCapture.discard()
            result
          }(using munitExecutionContext)
        )
    )

  private val logCaptureFixture: Fixture[Unit] = new Fixture[Unit]("log capturing"):
    def apply(): Unit                                  = ()
    override def beforeEach(context: BeforeEach): Unit = bodyRan = false
    // A test whose beforeEach failed never runs its body, so the transform never sees it fail.
    override def afterEach(context: AfterEach): Unit =
      if !bodyRan then LogCapture.print(s"$suiteName: ${context.test.name} (its setup failed)")
    override def afterAll(): Unit =
      if completed == 0 then LogCapture.print(s"$suiteName: no test ran")
      LogCapture.stop()

  override def munitFixtures: Seq[AnyFixture[?]] = super.munitFixtures :+ logCaptureFixture

/**
 * The capture itself: one per JVM, because logback's root logger is. `start` moves the root
 * logger's appenders aside and puts a buffer in their place; `stop` puts them back.
 */
private[testkit] object LogCapture:

  /** Beyond this many events the oldest are dropped, so a chatty test cannot exhaust the heap. */
  private val Limit = 20000

  private val lock    = new Object
  private val events  = new ArrayDeque[ILoggingEvent]()
  private var dropped = 0L
  private var moved   = Vector.empty[Appender[ILoggingEvent]]
  private var context = Option.empty[LoggerContext]

  private object Buffer extends AppenderBase[ILoggingEvent]:
    setName("ankka-log-capture")
    override def append(event: ILoggingEvent): Unit =
      // The message's arguments, the thread name and the MDC are read now, not when printed.
      event.prepareForDeferredProcessing()
      lock.synchronized {
        events.addLast(event)
        if events.size > Limit then
          val _ = events.removeFirst()
          dropped += 1
      }

  // The property first: an environment variable cannot be set from inside a test.
  private def enabled =
    !sys.props.get("ankka.test.logs").orElse(sys.env.get("ANKKA_TEST_LOGS")).contains("all")

  def start(): Unit = lock.synchronized {
    if context.isEmpty && enabled then
      LoggerFactory.getILoggerFactory match
        case logback: LoggerContext =>
          val root      = logback.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
          val appenders = root.iteratorForAppenders().asScala.toVector
          if appenders.nonEmpty then
            Buffer.setContext(logback)
            Buffer.start()
            root.addAppender(Buffer)
            appenders.foreach(a => root.detachAppender(a))
            moved = appenders
            context = Some(logback)
        case _ => ()
  }

  def stop(): Unit = lock.synchronized {
    discard()
    context.foreach { logback =>
      val root = logback.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      moved.foreach(root.addAppender)
      val _ = root.detachAppender(Buffer)
    }
    moved = Vector.empty
    context = None
  }

  def discard(): Unit = lock.synchronized {
    events.clear()
    dropped = 0
  }

  /** Replays what was captured to the appenders it was taken from, under a header. */
  def print(header: String): Unit =
    val (captured, lost, targets) = lock.synchronized {
      val out = (events.asScala.toVector, dropped, moved)
      discard()
      out
    }
    if captured.nonEmpty && targets.nonEmpty then
      val earlier = if lost > 0 then s", $lost earlier events dropped" else ""
      System.out.println(s"── log captured for $header (${captured.size} events$earlier) ──")
      captured.foreach(event => targets.foreach(_.doAppend(event)))
      System.out.println(s"── end of log captured for $header ──")
