package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.cli.console.*
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetAddress, InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}

/**
 * That a streaming response is forwarded as it arrives, and not collected first.
 *
 * This is the whole point of the streaming path, and it is the one property a test of the *result*
 * cannot see: buffering and streaming produce identical output, differing only in when it appears.
 * So these tests measure timing — a chunk written before the source has finished must reach the
 * reader before the source has finished.
 */
final class ConsoleStreamSuite extends FunSuite:

  private val client = HttpClient.newHttpClient()

  /**
   * A source whose stream emits on demand, so a test can observe arrival rather than completion.
   */
  private final class SlowSource(release: CountDownLatch) extends Source:
    def services(): Vector[ServiceSummary]                                = Vector.empty
    def service(name: String): Option[String]                             = None
    def traces(name: String): Option[String]                              = None
    def trace(name: String, id: String): Option[String]                   = None
    def session(name: String, id: String): Option[String]                 = None
    def invoke(name: String, r: InvokeRequest): Option[InvokeResponse]    = None
    def query(n: String, c: String, i: String, m: String): Option[String] = None

    def invokeStream(name: String, request: InvokeRequest, onChunk: String => Unit): Boolean =
      if name != "agent" then false
      else
        onChunk("data: first\n")
        // Blocks until the test says so. A buffering proxy would hold "first" until this returns.
        val _ = release.await(10, TimeUnit.SECONDS)
        onChunk("data: second\n")
        true

  private def withConsole[A](source: Source)(body: ConsoleServer => A): A =
    val server = ConsoleServer.start(source, 0, PrintStream(ByteArrayOutputStream()))
    try body(server)
    finally server.stop()

  private def post(server: ConsoleServer, path: String) =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"${server.address}$path"))
        .POST(
          HttpRequest.BodyPublishers.ofString(
            """{"method":"GET","path":"/chat/s1","body":"","contentType":""}"""
          )
        )
        .build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )

  test("a chunk reaches the reader before the source has finished producing") {
    val release = CountDownLatch(1)
    withConsole(SlowSource(release)) { server =>
      val response = post(server, "/api/invoke-stream/agent")
      val reader   = scala.io.Source.fromInputStream(response.body())

      val lines = reader.getLines()
      // If anything in the chain buffered, this blocks until the latch is released below — and
      // the latch is not released until after this returns, so a buffering chain deadlocks and
      // the test times out rather than passing quietly.
      val first = lines.next()
      assertEquals(first, "data: first", "the first chunk arrived while the source was still open")

      release.countDown()
      assertEquals(lines.next(), "data: second")
      reader.close()
    }
  }

  test("a stream for a service the source does not have says so and ends") {
    val release = CountDownLatch(1)
    release.countDown()
    withConsole(SlowSource(release)) { server =>
      val response = post(server, "/api/invoke-stream/ghost")
      val body     = String(response.body().readAllBytes(), StandardCharsets.UTF_8)
      assert(body.contains("no such service"), body)
    }
  }

  test("the reader going away does not take the console with it") {
    val release = CountDownLatch(1)
    withConsole(SlowSource(release)) { server =>
      val response = post(server, "/api/invoke-stream/agent")
      // Close after the first chunk, as a browser tab does when it is shut mid-stream.
      response.body().close()
      release.countDown()
      Thread.sleep(200)

      // The console is still serving: a closed reader is not a fault.
      val after = client.send(
        HttpRequest.newBuilder(URI.create(s"${server.address}/api/services")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(after.statusCode(), 200)
    }
  }
