package com.thinkmorestupidless.ankka.testkit

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{Observability, ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.sdk.*
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** A member who logs everything it is handed, as a careless service would. */
final class ChattyMember(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Int, Joined]:
  private val log                = LoggerFactory.getLogger(classOf[ChattyMember])
  private val subject            = DataSubject(s"member/${context.entityId}")
  def emptyState: Int            = 0
  def applyEvent(e: Joined): Int =
    log.info(s"applying $e to $currentState")
    currentState + 1
  def join(email: String): Effect[Done] =
    val event = Joined(context.entityId, Personal(subject, email), "Leeds")
    log.info(s"recording $event")
    effects.persist(event).thenReply(_ => Done)
  def email: ReadOnlyEffect[Int] = effects.reply(currentState)

object ChattyMember
    extends EventSourcedEntity.Companion[ChattyMember, Int, Joined](
      componentId = ComponentId("chatty-member"),
      stateSerializer = Codecs.serializer[Int]("chatty-count"),
      eventSerializer = Codecs.serializer[Joined]("chatty-joined")
    ):
  def create(context: EventSourcedEntityContext) = new ChattyMember(context)
  override def snapshotEvery: Option[Int]        = None
  val join                                       = command("join")(_.join)
  val count                                      = query("count")(_.email)

final class ChattyRowsView extends View[Joined, MemberRow]:
  private val log                     = LoggerFactory.getLogger(classOf[ChattyRowsView])
  def onChange(event: Joined): Effect =
    val row = MemberRow(event.memberId, event.email, event.city)
    log.info(s"writing $row from $event")
    effects.updateRow(row)

object ChattyRows
    extends View.Companion[ChattyRowsView, Joined, MemberRow](
      componentId = ComponentId("chatty-rows"),
      source = ChangeSource.eventsOf(ChattyMember),
      rowSerializer = Codecs.serializer[MemberRow]("chatty-row")
    ):
  def create(ctx: ViewComponentContext) = new ChattyRowsView
  lookup(_.email)
  val byEmail =
    query("by-email")(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email")

final class ChattyListener extends Consumer[Joined, Nothing]:
  private val log                      = LoggerFactory.getLogger(classOf[ChattyListener])
  def onMessage(event: Joined): Effect =
    log.info(s"handed $event, email ${event.email}")
    effects.done()

object ChattyListener
    extends Consumer.Companion[ChattyListener, Joined, Nothing](
      componentId = ComponentId("chatty-listener"),
      source = ChangeSource.eventsOf(ChattyMember)
    ):
  def create(ctx: ConsumerContext) = new ChattyListener

/**
 * Feature 042, SC-009: no log line, span or recorder entry of a run of the feature's flows holds a
 * personal value they wrote, though every handler here interpolates whole events into its log.
 */
class PersonalLeakSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val Values = Vector("leak-ada@example.com", "leak-bo@example.com")

  private def context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

  /** Every event logged while `body` runs, at every level, from every logger. */
  private def logged(body: => Unit): Vector[ILoggingEvent] =
    val appender = ListAppender[ILoggingEvent]()
    val root     = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val ankka    = context.getLogger("com.thinkmorestupidless")
    val was      = ankka.getLevel
    appender.start()
    root.addAppender(appender)
    ankka.setLevel(Level.TRACE)
    try body
    finally
      ankka.setLevel(was)
      root.detachAppender(appender): Unit
      appender.stop()
    appender.list.asScala.toVector

  /** What `event` says: its message, its arguments, its exception's chain. */
  private def text(event: ILoggingEvent): String =
    val thrown = Iterator
      .iterate(Option(event.getThrowableProxy))(_.flatMap(t => Option(t.getCause)))
      .takeWhile(_.nonEmpty)
      .flatten
      .map(t => s"${t.getClassName}: ${t.getMessage}")
    (Iterator(event.getFormattedMessage) ++ thrown ++ event.getMDCPropertyMap.asScala.values)
      .mkString("\n")

  /** Each of `values` found in `texts`, with where. */
  private def leaks(texts: Vector[String], values: Vector[String]): Vector[String] =
    for
      t <- texts
      v <- values
      if t.contains(v)
    yield s"'$v' in: ${t.take(300)}"

  test("the flows of writing, reading, erasing and recovering leak no personal value") {
    val keyring  = InMemoryKeyring()
    var recorded = Vector.empty[String]
    val events   = logged {
      val kit = AnkkaTestKit.start(
        Seq(ChattyMember.descriptor, ChattyRows.descriptor, ChattyListener.descriptor),
        Seq(ProjectionRuntime()),
        serviceIdentity = ServiceIdentity.deployed("brand", "members"),
        keyring = keyring
      )
      def keep(): Unit =
        recorded ++= Observability(kit.service.system).recorder.snapshot().map(_.toString)
      try
        def member(id: String) = kit.componentClient.forEventSourcedEntity(EntityId(id))
        // Asked of the running service each time: a restart makes a new one.
        def rows = kit.service.viewClient.forView(ChattyRows)
        Values.zipWithIndex.foreach((email, i) =>
          assertEquals(member(s"m$i").call(ChattyMember.join).invoke(email), Done)
        )
        val found = AnkkaTestKit.eventually("the rows by lookup", 30.seconds)(
          Some(rows.ask(ChattyRows.byEmail, "email" -> Values(0))).filter(_.nonEmpty)
        )
        assertEquals(found.map(_.email.orElse("erased")), Vector(Values(0)))
        keep()
        kit.restartService()
        assertEquals(member("m0").call(ChattyMember.count).invoke(), 1)
        kit.erase(DataSubject("member/m0")): Unit
        AnkkaTestKit.eventually("the row redacted", 30.seconds)(
          rows.get("m0").filter(_.email.isErased)
        ): Unit
        keep()
        kit.restartService()
        assertEquals(member("m0").call(ChattyMember.count).invoke(), 1)
        assertEquals(rows.get("m1").map(_.email.orElse("erased")), Some(Values(1)))
        keep()
      finally kit.stop()
    }
    // The run did log what it was handed: every handler's line is there, without the values.
    assert(events.exists(_.getFormattedMessage.contains("recording Joined(")), "nothing was logged")
    assert(
      events.exists(_.getFormattedMessage.contains("handed Joined(")),
      "the consumer logged nothing"
    )
    assert(recorded.nonEmpty, "nothing was recorded")
    val found = leaks(events.map(text), Values) ++ leaks(recorded, Values)
    assert(found.isEmpty, found.mkString("\n"))
  }

  test("the check finds a value a handler logs in plain") {
    val events = logged {
      LoggerFactory.getLogger(classOf[PersonalLeakSuite]).info(s"careless ${Values(0)}")
    }
    assertEquals(leaks(events.map(text), Values).size, 1)
  }
