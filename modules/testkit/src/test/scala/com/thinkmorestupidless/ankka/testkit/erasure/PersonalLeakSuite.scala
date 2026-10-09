package com.thinkmorestupidless.ankka.testkit.erasure

import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.thinkmorestupidless.ankka.agent.{SessionMemoryEntity, SessionMessage}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.personal.{Personal, PersonalScope}
import com.thinkmorestupidless.ankka.runtime.{Observability, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-009: nothing personal leaves a service by the side doors. A service with personal fields in an
 * entity's events, a view's rows and an agent session's memory is written to, read, looked up and
 * erased with every logger at DEBUG; then every line logged, the recorder's name table and the
 * local console's documents (the service, its topology, its traces and the session) are searched
 * for the plaintext values. A line planted with a value is found, so the search can fail.
 */
class PersonalLeakSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val Values =
    Vector("leak.ada@example.com", "Ada Leakwell", "my card ending 4417 was declined")

  /** Every event logged while the scenario runs, its message, arguments, MDC and throwable. */
  private object Seen extends AppenderBase[ILoggingEvent]:
    val lines = ConcurrentLinkedQueue[String]()
    override def append(event: ILoggingEvent): Unit =
      event.prepareForDeferredProcessing()
      val thrown =
        Option(event.getThrowableProxy).fold("")(t => s" ${t.getClassName}: ${t.getMessage}")
      lines.add(
        s"${event.getLoggerName} ${event.getFormattedMessage} ${event.getMDCPropertyMap.asScala}$thrown"
      ): Unit

  private def root: Logger =
    LoggerFactory.getILoggerFactory
      .asInstanceOf[LoggerContext]
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

  private def hits(text: String): Vector[String] = Values.filter(text.contains)

  test("no value written, read, looked up or erased is logged, interned or served to the console") {
    val registry = Files.createTempDirectory("ankka-leak-suite")
    sys.props.put("ankka.running.dir", registry.toString)
    val level = root.getLevel
    Seen.setContext(root.getLoggerContext)
    Seen.start()
    root.addAppender(Seen)
    root.setLevel(Level.DEBUG)
    val kit = AnkkaTestKit.start(
      Seq(PlayerEntity.descriptor, Profiles.descriptor, SessionMemoryEntity.descriptor),
      Seq(ProjectionRuntime())
    )
    try
      val id      = s"leak-${System.nanoTime()}"
      val subject = s"player/$id"
      val player  = kit.componentClient.forEventSourcedEntity(EntityId(id))
      player
        .call(PlayerEntity.register)
        .invoke(Registration(Values(0), Values(1), LocalDate.of(1815, 12, 10), "GBP")): Unit
      assertEquals(player.call(PlayerEntity.get).invoke().email, Values(0))
      kit.eventually("the row found by its lookup token")(
        PersonalScope.within(kit.service.keyring, "local")(
          kit.service.viewClient
            .forView(Profiles)
            .ask(Profiles.byEmail, "email" -> Personal.lookupToken(Values(0)))
            .find(_.playerId == id)
        )
      ): Unit
      val session = kit.componentClient.forEventSourcedEntity(EntityId(s"leak-session-$id"))
      session.call(SessionMemoryEntity.assignSubject).invoke(subject): Unit
      session
        .call(SessionMemoryEntity.addUserMessage)
        .invoke(SessionMessage.UserMessage(1L, Values(2), "helper")): Unit

      kit.awaitApplied(kit.erase(subject))
      assertEquals(player.call(PlayerEntity.get).invoke().email, "<erased>")

      val logged = Seen.lines.asScala.toVector
      assert(logged.nonEmpty, "the appender saw nothing, so it proves nothing")
      assertEquals(logged.filter(l => hits(l).nonEmpty), Vector.empty, "logged")

      val names    = Observability(kit.service.system).names
      val interned = (0 until names.size).flatMap(names.nameOf)
      assert(interned.nonEmpty, "the name table is empty, so it proves nothing")
      assertEquals(interned.filter(n => hits(n).nonEmpty), Vector.empty, "interned")

      val console = kit.service.observabilityAddress.getOrElse(fail("no local console endpoint"))
      val http    = HttpClient.newHttpClient()
      def document(path: String): String =
        val response = http.send(
          HttpRequest.newBuilder(URI.create(console + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(response.statusCode, 200, s"$path: ${response.body}")
        response.body
      val listed = Vector(
        "/observability/service",
        "/observability/topology",
        "/observability/traces",
        s"/observability/sessions/leak-session-$id"
      ).map(path => path -> document(path))
      // Each trace's own document too: the spans of every call the scenario made.
      val traceIds = """"traceId":"([0-9a-f]+)"""".r
        .findAllMatchIn(listed(2)._2)
        .map(_.group(1))
        .toVector
      val served = listed ++ traceIds.map(t =>
        s"/observability/traces/$t" -> document(s"/observability/traces/$t")
      )
      assert(
        listed(2)._2.contains("players#get") && listed(2)._2.contains("#add-user-message"),
        s"the traces show none of the scenario's calls, so they prove nothing: ${listed(2)._2}"
      )
      assert(
        listed(3)._2.contains("\"erased\":true"),
        s"the session does not read as erased: ${listed(3)._2}"
      )
      assertEquals(
        served.collect { case (p, body) if hits(body).nonEmpty => p -> hits(body) },
        Vector.empty
      )
    finally
      root.detachAppender(Seen): Unit
      root.setLevel(level)
      kit.stop()
      sys.props.remove("ankka.running.dir"): Unit
      deleteTree(registry)
  }

  test("a line planted with a value is found") {
    Seen.lines.clear()
    Seen.setContext(root.getLoggerContext)
    Seen.start()
    root.addAppender(Seen)
    try LoggerFactory.getLogger("leak.plant").info(s"declined for ${Values(0)}")
    finally root.detachAppender(Seen): Unit
    assert(Seen.lines.asScala.exists(l => hits(l).contains(Values(0))))
  }

  private def deleteTree(path: Path): Unit =
    if Files.exists(path) then
      Files.walk(path).iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists(_): Unit)
