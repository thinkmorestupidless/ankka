package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.cli.console.LocalSource
import munit.FunSuite

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * Finding the services running on this machine, and — the part that actually matters — noticing the
 * ones that are not.
 *
 * A registry entry is written by a service at startup and removed on a clean shutdown. Development
 * does not do clean shutdowns: `kill -9`, a crashed JVM and a closed laptop lid all leave the file
 * behind. So a stale entry is the ordinary case, the writer cannot be relied on, and the reader has
 * to check. A dead row that errors when clicked is worse than no row.
 */
final class DiscoverySuite extends FunSuite:

  private var directory: Path           = null
  private var servers: List[HttpServer] = Nil

  override def beforeEach(context: BeforeEach): Unit =
    directory = Files.createTempDirectory("ankka-discovery")

  override def afterEach(context: AfterEach): Unit =
    servers.foreach(_.stop(0))
    servers = Nil

  /** A stand-in for a running service: answers the one route discovery probes. */
  private def runningService(name: String): Int =
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/observability/service",
      exchange =>
        val body = s"""{"name":"$name","instances":[],"components":[],"routes":[]}"""
          .getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        val out = exchange.getResponseBody
        try out.write(body)
        finally out.close()
    )
    server.start()
    servers = server :: servers
    server.getAddress.getPort

  private def writeEntry(name: String, port: Int, pid: Long = 4242L): Path =
    val file = directory.resolve(s"$pid.json")
    Files.writeString(
      file,
      s"""{"name":"$name","instanceId":"$pid","pid":$pid,""" +
        s""""observabilityAddress":"http://127.0.0.1:$port",""" +
        s""""startedAt":"2026-09-19T12:00:00Z"}"""
    )
    file

  test("a service that answers is listed") {
    val _      = writeEntry("orders", runningService("orders"))
    val listed = LocalSource(directory).services()

    assertEquals(listed.map(_.name), Vector("orders"))
  }

  test("several services are all listed, oldest first") {
    val _ = writeEntry("orders", runningService("orders"), pid = 1)
    val _ = writeEntry("carts", runningService("carts"), pid = 2)

    assertEquals(LocalSource(directory).services().map(_.name).sorted, Vector("carts", "orders"))
  }

  test("an entry nothing answers for is dropped, and its file removed") {
    // A port nobody is listening on: the shape a `kill -9` leaves behind.
    val stale = writeEntry("ghost", port = 1)

    assertEquals(LocalSource(directory).services(), Vector.empty)
    assert(!Files.exists(stale), "the reader cleans up, because the writer did not get to")
  }

  test("one dead service does not hide a live one") {
    val _     = writeEntry("orders", runningService("orders"), pid = 1)
    val stale = writeEntry("ghost", port = 1, pid = 2)

    assertEquals(LocalSource(directory).services().map(_.name), Vector("orders"))
    assert(!Files.exists(stale))
  }

  test("a malformed entry is treated as stale rather than crashing the listing") {
    Files.writeString(directory.resolve("999.json"), "{ this is not json"): Unit
    val _ = writeEntry("orders", runningService("orders"), pid = 1)

    assertEquals(LocalSource(directory).services().map(_.name), Vector("orders"))
    assert(!Files.exists(directory.resolve("999.json")))
  }

  test("a directory that does not exist is empty, not an error") {
    val missing = directory.resolve("never-created")
    assertEquals(LocalSource(missing).services(), Vector.empty)
  }

  test("files that are not entries are left alone") {
    Files.writeString(directory.resolve("notes.txt"), "not mine"): Unit
    assertEquals(LocalSource(directory).services(), Vector.empty)
    assert(Files.exists(directory.resolve("notes.txt")), "only .json entries are ours to remove")
  }

  test("the directory comes from a system property, so no test writes to \\$HOME") {
    val previous = sys.props.get("ankka.running.dir")
    try
      sys.props.put("ankka.running.dir", directory.toString)
      assertEquals(LocalSource.defaultDirectory, directory)
    finally
      previous.fold(sys.props.remove("ankka.running.dir"))(
        sys.props.put("ankka.running.dir", _)
      ): Unit
  }
