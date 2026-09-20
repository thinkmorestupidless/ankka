package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.{SessionMemoryEntity, SessionMessage}
import com.thinkmorestupidless.ankka.core.EntityId
import munit.FunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * What a running service actually serves to the console.
 *
 * `AnkkaTestKit` starts a real service, so this exercises the endpoint the way the console does:
 * over HTTP, against a process that is genuinely running, rather than by calling the handler
 * functions directly. The registry directory is redirected by system property — the reason that
 * override exists at all, since otherwise this suite would write into whoever is running it.
 */
final class ConsoleEndpointSuite extends FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private var registryDir: Path     = null
  private val client                = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    registryDir = Files.createTempDirectory("ankka-console-suite")
    sys.props.put("ankka.running.dir", registryDir.toString)
    testKit = AnkkaTestKit.start(ProfileEntity.descriptor, SessionMemoryEntity.descriptor)

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    sys.props.remove("ankka.running.dir"): Unit

  private def get(path: String): (Int, String) =
    val address = observabilityAddress
    val response = client.send(
      HttpRequest.newBuilder(URI.create(address + path)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (response.statusCode(), response.body)

  /** Found the way the console finds it: by reading the registry this service wrote. */
  private def observabilityAddress: String =
    val entry = Files
      .list(registryDir)
      .filter(_.toString.endsWith(".json"))
      .findFirst()
      .orElseThrow(() => AssertionError("the service wrote no registry entry"))
    val json   = Files.readString(entry)
    val marker = "\"observabilityAddress\":\""
    val from   = json.indexOf(marker) + marker.length
    json.substring(from, json.indexOf('"', from))

  test("a running service announces itself where the console looks") {
    val entries = Files.list(registryDir).filter(_.toString.endsWith(".json")).count()
    assertEquals(entries, 1L, "exactly one entry, for this process")
    assert(observabilityAddress.startsWith("http://127.0.0.1:"), "and it is loopback")
  }

  test("the service document names the service, its components and its routes") {
    val (status, body) = get("/observability/service")
    assertEquals(status, 200)
    assert(body.contains("\"components\""), body)
    assert(body.contains("profile"), "the registered entity is listed")
    assert(body.contains("\"instances\""), "instances is a list, even holding one")
    assert(body.contains("\"routes\""), "routes is present, even when empty")
  }

  test("a request produces a trace, and the window says it is a window") {
    val _ = testKit.componentClient
      .forKeyValueEntity(EntityId("console-1"))
      .call(ProfileEntity.register)
      .invoke(Profile("Ada", "ada@example.com", 1))

    val (status, body) = get("/observability/traces")
    assertEquals(status, 200)
    assert(body.contains("\"capacity\""), "the reader is told the window's size")
    assert(body.contains("\"oldestOverwritten\""), "and whether anything has been discarded")
    assert(body.contains("profile#register"), body)
  }

  test("a session nobody has spoken in is empty, because that is what an entity id means here") {
    val (status, body) = get("/observability/sessions/never-happened")

    // The spec asked for a 404, and the platform disagrees — correctly. An entity id is an
    // address, not a record: asking for one that has never been used returns `emptyState`, which
    // is the defined answer rather than a missing one. There is no "does not exist" to report.
    //
    // So the endpoint reports what the platform says, and the console renders it as "this session
    // holds no messages" — which is true — rather than fabricating a conversation. Returning 404
    // here would have meant the console asserting something the platform does not know.
    assertEquals(status, 200, body)
    assert(body.contains("\"messages\":[]"), s"an empty conversation, not an invented one: $body")
  }

  test("a session's memory and its token cost come from the entity, not the ring") {
    val sessionId = "console-session"
    val _ = testKit.componentClient
      .forEventSourcedEntity(EntityId(sessionId))
      .call(SessionMemoryEntity.addUserMessage)
      .invoke(
        SessionMessage.UserMessage(System.currentTimeMillis(), "what is the weather", "probe")
      )

    val (status, body) = get(s"/observability/sessions/$sessionId")
    assertEquals(status, 200, body)
    assert(body.contains("what is the weather"), "the stored conversation is returned")
    assert(body.contains("usage"), "with the tokens it has cost")
  }

  test("a query handler can be run against an entity, and returns its state") {
    val id = EntityId("query-me")
    val _ = testKit.componentClient
      .forKeyValueEntity(id)
      .call(ProfileEntity.register)
      .invoke(Profile("Ada", "ada@example.com", 1))

    val (status, body) = get(s"/observability/query/profile/$id/get")
    assertEquals(status, 200, body)
    assert(body.contains("Ada"), s"the entity's own state came back: $body")
  }

  test("a command is refused, because the console does not run commands") {
    val id = EntityId("query-me")

    // `rename` is declared with `command`, so its binding is not readOnly — and `query` accepts
    // only a ReadOnlyEffect, so that distinction is a compiler guarantee rather than a label.
    // The console refuses rather than inventing a second notion of what is safe to run.
    val (status, body) = get(s"/observability/query/profile/$id/rename")
    assertEquals(status, 405, body)
    assert(body.contains("command"), body)
  }

  test("an unknown handler is a 404, not a 405") {
    val (status, _) = get("/observability/query/profile/any/no-such-handler")
    assertEquals(status, 404, "absent and refused are different answers")
  }

  test("only queries are advertised, so the console never offers a command") {
    val (_, body) = get("/observability/service")
    assert(body.contains("\"queries\""), body)
    assert(body.contains("get"), "the query is listed")
    assert(!body.contains("\"rename\""), s"the command is not: $body")
  }

  test("the endpoint is loopback only, which is the whole of its access control") {
    val port = observabilityAddress.substring(observabilityAddress.lastIndexOf(':') + 1)
    val lan = java.net.NetworkInterface
      .getNetworkInterfaces()
      .asIterator()
      .asScala
      .flatMap(_.getInetAddresses.asIterator().asScala)
      .find(a => !a.isLoopbackAddress && a.isInstanceOf[java.net.Inet4Address])

    lan match
      case None => // no routable interface on this machine; nothing to prove against
      case Some(address) =>
        val refused =
          try
            val _ = client.send(
              HttpRequest
                .newBuilder(
                  URI.create(s"http://${address.getHostAddress}:$port/observability/service")
                )
                .timeout(java.time.Duration.ofSeconds(2))
                .GET()
                .build(),
              HttpResponse.BodyHandlers.ofString()
            )
            false
          catch case _: Throwable => true
        assert(refused, "the endpoint answered a non-loopback address; it must not")
  }
