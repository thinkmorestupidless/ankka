package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.agent.javasdk.Agents
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue, Flow, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** The Java samples of the streaming page: a stream handler, its consumer and an SSE route. */
class DocsStreamingSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model             = TestModelProvider()
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka
        .service("weather")
        .withModule(Agents.module().withModelProvider(model))
        .register(classOf[WeatherAgent])
        .withEndpoint(classOf[ChatRoutes])
    )
  override def afterAll(): Unit = if kit != null then kit.close()

  private def collect(publisher: Flow.Publisher[String]): String =
    val parts = ConcurrentLinkedQueue[String]()
    val done  = CompletableFuture[Unit]()
    publisher.subscribe(new Flow.Subscriber[String]:
      def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
      def onNext(part: String): Unit              = parts.add(part): Unit
      def onError(t: Throwable): Unit             = done.completeExceptionally(t): Unit
      def onComplete(): Unit                      = done.complete(()): Unit)
    done.get(30, TimeUnit.SECONDS)
    parts.asScala.mkString

  test("a stream handler's tokens reach a subscriber as they are written") {
    model.expectText("Dry and warm.")
    assertEquals(
      collect(ChatCalls.tokens(kit.componentClient, "s-1", "Will it rain?")),
      "Dry and warm."
    )
  }

  test("an SSE route passes the agent's tokens on, one event each") {
    model.expectText("Mild, with rain.")
    val request  = HttpRequest.newBuilder(kit.http().resolve("/chat/s-2")).GET().build()
    val response = kit.httpClient().send(request, HttpResponse.BodyHandlers.ofLines())
    assertEquals(response.statusCode, 200)
    val tokens = response.body.iterator.asScala
      .filter(_.startsWith("data:"))
      .map(_.stripPrefix("data:\"").stripSuffix("\""))
      .mkString
    assertEquals(tokens, "Mild, with rain.")
  }
