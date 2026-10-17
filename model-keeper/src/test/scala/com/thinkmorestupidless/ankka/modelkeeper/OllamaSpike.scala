package com.thinkmorestupidless.ankka.modelkeeper

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration
import java.util.concurrent.{Callable, Executors}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

private final class OllamaContainer(image: DockerImageName)
    extends GenericContainer[OllamaContainer](image)

/**
 * Spikes S1 and S3 of feature 053 against the real `ollama/ollama` image the component pins:
 *
 *   - S1: whether a load with `keep_alive: -1` keeps a model loaded through an idle period longer
 *     than Ollama's default five minutes, and what Ollama answers a request when its queue is full;
 *   - S3: whether `qwen3:0.6b` calls a declared tool through Chat Completions on a CPU, and how
 *     long one such call takes.
 *
 * Gated on `-Dankka.spikes=on`; reported ignored, never passed, without it. The model is fetched
 * from Ollama's registry (about 520 MB), so a run needs the network. The idle period is
 * `-Dankka.spike.idle` (default `6m`):
 *
 * sbt -Dankka.spikes=on 'modelKeeper/testOnly *OllamaSpike'
 */
class OllamaSpike extends munit.FunSuite:

  override def munitIgnore: Boolean   = !sys.props.get("ankka.spikes").contains("on")
  override def munitTimeout: Duration = 40.minutes

  private val Image = "ollama/ollama:0.40.6"
  private val Model = "qwen3:0.6b"
  private val idle  = sys.props.get("ankka.spike.idle").map(Duration(_)).getOrElse(6.minutes)

  private val client = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(5)).build()

  private def ollama(env: (String, String)*): OllamaContainer =
    val c = OllamaContainer(DockerImageName.parse(Image))
      .withExposedPorts(11434)
      .withEnv((Map("OLLAMA_HOST" -> "0.0.0.0:11434") ++ env).asJava)
      .withTmpFs(Map("/root/.ollama" -> "rw").asJava)
      .waitingFor(Wait.forHttp("/api/version").forPort(11434))
    c.start()
    c

  private def base(c: OllamaContainer) = s"http://${c.getHost}:${c.getMappedPort(11434)}"

  private def post(c: OllamaContainer, path: String, body: String, timeout: Duration = 10.minutes) =
    client.send(
      HttpRequest
        .newBuilder(URI.create(base(c) + path))
        .timeout(JDuration.ofMillis(timeout.toMillis))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(body))
        .build(),
      BodyHandlers.ofString()
    )

  private def get(c: OllamaContainer, path: String): HttpResponse[String] =
    client.send(HttpRequest.newBuilder(URI.create(base(c) + path)).build(), BodyHandlers.ofString())

  private def fetch(c: OllamaContainer): Unit =
    val pulled = post(c, "/api/pull", s"""{"model":"$Model"}""", 30.minutes)
    assertEquals(pulled.statusCode, 200, pulled.body.takeRight(500))
    assert(
      pulled.body.linesIterator.toVector.lastOption.exists(_.contains("success")),
      pulled.body.takeRight(500)
    )

  test("S1: a load with keep_alive -1 keeps the model loaded through an idle period") {
    val c = ollama()
    try
      fetch(c)
      val started = System.nanoTime()
      val loaded  = post(c, "/api/generate", s"""{"model":"$Model","keep_alive":-1}""")
      assertEquals(loaded.statusCode, 200, loaded.body)
      println(s"[S1] load took ${(System.nanoTime() - started) / 1_000_000} ms: ${loaded.body}")
      val before = get(c, "/api/ps").body
      println(s"[S1] /api/ps after the load: $before")
      assert(before.contains(Model), before)
      Thread.sleep(idle.toMillis)
      val after = get(c, "/api/ps").body
      println(s"[S1] /api/ps after $idle idle: $after")
      assert(after.contains(Model), s"unloaded after $idle idle: $after")
    finally c.stop()
  }

  test("S3: qwen3:0.6b calls a declared tool through Chat Completions on a CPU") {
    val c = ollama()
    try
      fetch(c)
      val body =
        s"""{"model":"$Model","max_tokens":200,"reasoning_effort":"low",
           |"messages":[{"role":"user","content":"look up order 42"}],
           |"tools":[{"type":"function","function":{"name":"lookup_order","description":"Looks up an order by its number",
           |"parameters":{"type":"object","properties":{"order":{"type":"integer"}},"required":["order"]}}}]}""".stripMargin
      val started  = System.nanoTime()
      val answered = post(c, "/v1/chat/completions", body, 2.minutes)
      val took     = (System.nanoTime() - started) / 1_000_000
      println(s"[S3] status ${answered.statusCode} in $took ms: ${answered.body}")
      assertEquals(answered.statusCode, 200, answered.body)
      assert(
        answered.body.contains("tool_calls") && answered.body.contains("lookup_order"),
        answered.body
      )
    finally c.stop()
  }

  test("S1: what Ollama answers when its queue is full") {
    val c    = ollama("OLLAMA_MAX_QUEUE" -> "2", "OLLAMA_NUM_PARALLEL" -> "1")
    val pool = Executors.newVirtualThreadPerTaskExecutor()
    try
      fetch(c)
      val long =
        s"""{"model":"$Model","max_tokens":400,"messages":[{"role":"user","content":"Write a long story about a duck."}]}"""
      val calls = (1 to 6).map(_ =>
        pool.submit((() => post(c, "/v1/chat/completions", long)): Callable[HttpResponse[String]])
      )
      val answers = calls.map(_.get())
      answers.foreach(a => println(s"[S1 queue] ${a.statusCode}: ${a.body.take(200)}"))
      assert(answers.exists(_.statusCode != 200), "no request was refused with the queue full")
    finally
      pool.shutdownNow(): Unit
      c.stop()
  }
