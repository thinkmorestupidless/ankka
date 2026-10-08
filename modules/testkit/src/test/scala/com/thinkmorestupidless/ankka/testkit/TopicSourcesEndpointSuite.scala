package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * A service run locally says what its topic sources are in the document the local console reads, as
 * `topicSources`: where each reads, under which group, from where and at which version.
 */
class TopicSourcesEndpointSuite extends munit.FunSuite with LogCapturing:

  private var testKit: AnkkaTestKit = null
  private var registryDir: Path     = null
  private val client                = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    registryDir = Files.createTempDirectory("ankka-topic-sources-suite")
    sys.props.put("ankka.running.dir", registryDir.toString)
    val broker = InMemoryBroker()
    testKit = AnkkaTestKit.start(
      Seq(StockLevels.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker))
    )

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    sys.props.remove("ankka.running.dir"): Unit

  /** Found the way the console finds it: by reading the registry this service wrote. */
  private def serviceDocument(): String =
    val entry = Files
      .list(registryDir)
      .filter(_.toString.endsWith(".json"))
      .findFirst()
      .orElseThrow(() => AssertionError("the service wrote no registry entry"))
    val json    = Files.readString(entry)
    val marker  = "\"observabilityAddress\":\""
    val from    = json.indexOf(marker) + marker.length
    val address = json.substring(from, json.indexOf('"', from))
    client
      .send(
        HttpRequest.newBuilder(URI.create(address + "/observability/service")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      .body

  test("the service document lists each topic source, where it reads and at which version") {
    val expected =
      "\"topicSources\":[{\"kind\":\"view\",\"component\":\"stock-levels\"," +
        "\"topic\":\"stock-events\",\"group\":\"ankka-view-stock-levels\"," +
        "\"start\":\"earliest\",\"version\":1,\"recordedVersion\":1,\"behind\":false," +
        "\"broker\":null,\"contract\":null,\"lag\":null,\"failing\":null}]"
    val deadline = System.nanoTime() + 20.seconds.toNanos
    var body     = serviceDocument()
    while !body.contains(expected) && System.nanoTime() < deadline do
      Thread.sleep(200)
      body = serviceDocument()
    assert(body.contains(expected), body)
  }
