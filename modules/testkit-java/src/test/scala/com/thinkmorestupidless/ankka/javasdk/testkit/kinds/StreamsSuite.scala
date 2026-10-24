package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.{LogCapturing, TestSocket}

import java.io.{ByteArrayInputStream, IOException, InputStream, SequenceInputStream}
import java.net.http.{HttpRequest, HttpResponse}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** A Java endpoint's server-sent events, socket and streamed body, served by the Scala server. */
class StreamsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Ankka.service("streams").withEndpoint(classOf[Streams]))

  override def afterAll(): Unit = if kit != null then kit.close()

  private def dataLines(path: String): Vector[String] =
    val request  = HttpRequest.newBuilder(kit.http().resolve(path)).GET().build()
    val response = kit.httpClient().send(request, HttpResponse.BodyHandlers.ofLines())
    assertEquals(response.statusCode, 200)
    response.body.iterator.asScala
      .filter(l => l.startsWith("data:") || l.startsWith("event:"))
      .toVector

  test("an @Sse route's stream of events reaches the client, each as JSON") {
    assertEquals(
      dataLines("/streams/count/3"),
      Vector("data:\"tick 1\"", "data:\"tick 2\"", "data:\"tick 3\"")
    )
  }

  test("an @Sse route's publisher is read as it publishes, named events carrying their JSON") {
    assertEquals(
      dataLines("/streams/published"),
      Vector(1, 2, 3).flatMap(n => Vector(s"data:{\"n\":$n}", "event:line"))
    )
  }

  test("an @Socket route answers each frame, and closes the socket when its handler returns") {
    val socket = TestSocket
      .open(kit.http().toString.replaceFirst("^http", "ws").stripSuffix("/") + "/streams/echo")
      .fold(r => fail(s"refused: $r"), identity)
    socket.send("one")
    assertEquals(socket.receive(), Some("echo: one"))
    socket.send("two")
    assertEquals(socket.receive(), Some("echo: two"))
    socket.send("bye")
    assertEquals(socket.closed().code, 1000)
  }

  test("a @StreamBody route reads the body as it arrives, never waiting for the whole of it") {
    Streams.firstRead = CountDownLatch(1)
    val first = Array.fill[Byte](64 * 1024)('a')
    val rest  = Array.fill[Byte](64 * 1024)('b')
    // The rest is sent only once the handler has read from the first part: a server that held the
    // body whole before calling the handler would never read it, and the upload would fail here.
    val afterFirstRead = new InputStream:
      private lazy val held =
        if !Streams.firstRead.await(20, TimeUnit.SECONDS) then
          throw IOException("the handler read nothing before the body was whole")
        ByteArrayInputStream(rest)
      def read(): Int                                        = held.read()
      override def read(b: Array[Byte], o: Int, n: Int): Int = held.read(b, o, n)
    val body = HttpRequest.BodyPublishers.fromPublisher(
      HttpRequest.BodyPublishers.ofInputStream(() =>
        SequenceInputStream(ByteArrayInputStream(first), afterFirstRead)
      ),
      (first.length + rest.length).toLong
    )
    val request = HttpRequest
      .newBuilder(kit.http().resolve("/streams/upload/big.bin"))
      .header("Content-Type", "application/octet-stream")
      .POST(body)
      .build()
    val response = kit.httpClient().send(request, HttpResponse.BodyHandlers.ofString())
    assertEquals((response.statusCode, response.body), (200, (first.length + rest.length).toString))
    val kept = kit.storage().defaultBucket().getBytes("big.bin").get
    assertEquals(kept.length, first.length + rest.length)
    assertEquals(kept.last, 'b'.toByte)
  }

  test("a stream route declared wrongly stops the start, saying what is wrong") {
    val e = intercept[ServiceProblems](
      Ankka.service("broken").withEndpoint(classOf[BrokenStreams]).start()
    )
    val messages = e.problems.asScala.map(_.toString).mkString("\n")
    assert(messages.contains("is an @Sse route; it returns a Flow.Publisher<SseEvent>"), messages)
    assert(messages.contains("is an @Socket route; it takes one WebSocket"), messages)
    assert(
      messages.contains("takes a StreamedBody, which only a @StreamBody route is handed"),
      messages
    )
  }
