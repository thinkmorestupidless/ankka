package com.thinkmorestupidless.ankka.operator

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.core.{ModelCalls, ModelServerDocument, ModelState, ModelStatus}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/**
 * The operator's reading of the model server's status (feature 053), held to the fixture `core`'s
 * codec is held to, so the keeper's writer and the operator's reader cannot disagree about a field.
 */
class ModelServerDocumentReaderSuite extends munit.FunSuite:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.isRegularFile(p.resolve("build.sbt")) && Files.isDirectory(p.resolve("operator"))
      )
      .getOrElse(fail("could not find the repository root"))

  private lazy val fixture =
    Files.readString(repoRoot.resolve("modules/core/src/test/resources/ankka/models/document.json"))

  private val expected = ModelServerDocument(
    server = "https://models.models-system.svc:8443",
    ready = true,
    pass = Some("2026-10-17T15:04:05Z"),
    models = Vector(
      ModelStatus(
        "qwen3:0.6b",
        ModelState.Served,
        Some("7d4f3a1c9b2e8f60aa11223344556677889900aabbccddeeff00112233445566"),
        Vector("completion", "tools", "thinking"),
        since = Some("2026-10-17T15:00:00Z")
      ),
      ModelStatus("llama3.2:1b", ModelState.Fetching, since = Some("2026-10-17T15:04:00Z")),
      ModelStatus(
        "no-such-model:1b",
        ModelState.Failed,
        detail = Some("pull model manifest: file does not exist"),
        since = Some("2026-10-17T15:03:00Z")
      )
    ),
    calls = ModelCalls(1240, 3, 1, 0)
  )

  test("the shared fixture reads as the document core's codec reads") {
    assertEquals(ModelServerDocumentReader.parse(fixture), Right(expected))
  }

  test("a model in a state the operator does not know is refused, naming it") {
    val text = fixture.replace("\"fetching\"", "\"thinking-about-it\"")
    assertEquals(
      ModelServerDocumentReader.parse(text),
      Left("model \"llama3.2:1b\" is in an unknown state \"thinking-about-it\"")
    )
  }

  private def serving(status: Int, body: String, delayMillis: Long = 0)(
      test: ModelServerSettings => Unit
  ): Unit =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange =>
        if delayMillis > 0 then Thread.sleep(delayMillis)
        val bytes = body.getBytes(UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    try
      test(
        ModelServerSettings(
          "https://models.models-system.svc:8443",
          s"http://127.0.0.1:${server.getAddress.getPort}/ankka/models",
          "models-system"
        )
      )
    finally server.stop(0)

  test("the document is read from the status port") {
    serving(200, fixture)(settings =>
      assertEquals(ModelServerDocumentReader.read(settings), Right(expected))
    )
  }

  test("a status port that answers 500 is a reason, not a document") {
    serving(500, "oops")(settings =>
      assertEquals(
        ModelServerDocumentReader.read(settings),
        Left("the model server's status answered 500")
      )
    )
  }

  test("a status port that does not answer within two seconds is a reason") {
    serving(200, fixture, delayMillis = 3500) { settings =>
      val started = System.nanoTime()
      assert(ModelServerDocumentReader.read(settings).isLeft)
      assert((System.nanoTime() - started) / 1_000_000 < 3200, "the read waited past its timeout")
    }
  }

  test("a body that is not the document is a reason naming the problem") {
    serving(200, "<html>not json</html>")(settings =>
      assert(
        ModelServerDocumentReader
          .read(settings)
          .left
          .exists(_.startsWith("the model server's status is not readable")),
        ModelServerDocumentReader.read(settings).toString
      )
    )
  }

  test("nothing listening is a reason") {
    val settings =
      ModelServerSettings("https://x", "http://127.0.0.1:1/ankka/models", "models-system")
    assert(ModelServerDocumentReader.read(settings).isLeft)
  }
