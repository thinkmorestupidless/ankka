package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.core.{ModelServerDocument, ModelState}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testollama.FakeOllama

import java.nio.file.{Files, Path}
import java.time.{Clock, Duration, Instant, ZoneOffset}
import scala.concurrent.duration.*

/**
 * The keeper's loop against the stand-in Ollama and a declaration file on disk: what it fetches,
 * keeps loaded, removes and reports, pass by pass, with the clock in the test's hands.
 */
class KeeperSuite extends munit.FunSuite with LogCapturing:

  override def munitTimeout: scala.concurrent.duration.Duration = 60.seconds

  /** A clock a test moves. */
  final class TestClock(@volatile var now: Instant) extends Clock:
    def getZone: java.time.ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): Clock = this
    override def instant(): Instant                      = now
    def advance(by: Duration): Unit                      = now = now.plus(by)

  private final case class Rig(ollama: FakeOllama, file: Path, clock: TestClock, keeper: Keeper):
    def declare(models: String*): Unit =
      Files.writeString(
        file,
        models.map(m => s"\"$m\"").mkString("""{"models":[""", ",", "]}")
      ): Unit
    def doc: ModelServerDocument           = keeper.document
    def state(tag: String): Option[String] = doc.model(tag).map(_.state)

  private def rig(
      keepAlive: Option[Duration] = Some(Duration.ofMinutes(5)),
      interval: String = "30s",
      ollamaUrl: Option[String] = None
  )(models: String*): Rig =
    val ollama = FakeOllama.start(keepAlive)
    val dir    = Files.createTempDirectory("keeper")
    val file   = dir.resolve("models.json")
    val settings = KeeperSettings
      .fromEnvironment(
        Map(
          "ANKKA_KEEPER_ADDRESS"       -> "https://models.models-system.svc:8443",
          "ANKKA_KEEPER_OLLAMA"        -> ollamaUrl.getOrElse(ollama.url),
          "ANKKA_KEEPER_DECLARATION"   -> file.toString,
          "ANKKA_KEEPER_PASS_INTERVAL" -> interval
        ).get
      )
      .fold(p => throw IllegalStateException(p.mkString("; ")), identity)
    val clock = TestClock(Instant.parse("2026-10-17T12:00:00Z"))
    val r     = Rig(ollama, file, clock, Keeper(settings, OllamaClient(settings.ollama), clock))
    r.declare(models*)
    r

  private def withRig[A](r: Rig)(body: Rig => A): A =
    try body(r)
    finally
      r.keeper.stop()
      r.ollama.stop()

  test("a declared model is fetched, loaded and served after one pass, its digest recorded") {
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b")
      r.keeper.pass()
      val line = r.doc.model("qwen3:0.6b").getOrElse(fail(r.doc.toString))
      assertEquals(line.state, ModelState.Served)
      assertEquals(line.digest, Some(FakeOllama.digestOf("qwen3:0.6b")))
      assert(line.capabilities.contains("tools"), line.toString)
      assert(r.ollama.loadedTags.contains("qwen3:0.6b"))
      assertEquals(r.doc.served, Vector("qwen3:0.6b"))
      assert(r.doc.ready)
    }
  }

  test("a served model is fetched once and its digest recorded") {
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b")
      r.keeper.pass()
      r.keeper.pass()
      r.keeper.pass()
      assertEquals(r.ollama.requestsTo("POST", "/api/pull").size, 1)
      assertEquals(
        r.doc.model("qwen3:0.6b").flatMap(_.digest),
        Some(FakeOllama.digestOf("qwen3:0.6b"))
      )
    }
  }

  test(
    "a model added to the declaration is served after a pass, and the first is not fetched again"
  ) {
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b").offer("llama3.2:1b")
      r.keeper.pass()
      r.declare("qwen3:0.6b", "llama3.2:1b")
      r.keeper.pass()
      assertEquals(r.state("llama3.2:1b"), Some(ModelState.Served))
      assertEquals(r.state("qwen3:0.6b"), Some(ModelState.Served))
      val pulled = r.ollama.requestsTo("POST", "/api/pull").map(_.body)
      assertEquals(pulled.count(_.contains("qwen3:0.6b")), 1, pulled.toString)
    }
  }

  test(
    "a model removed from the declaration is removed from the model server and leaves the status"
  ) {
    withRig(rig()("qwen3:0.6b", "llama3.2:1b")) { r =>
      r.ollama.offer("qwen3:0.6b").offer("llama3.2:1b")
      r.keeper.pass()
      r.keeper.pass()
      assertEquals(r.doc.served.sorted, Vector("llama3.2:1b", "qwen3:0.6b"))
      r.declare("qwen3:0.6b")
      r.keeper.pass()
      assertEquals(r.state("llama3.2:1b"), None, r.doc.toString)
      assert(!r.ollama.heldTags.contains("llama3.2:1b"), r.ollama.heldTags.toString)
      assertEquals(r.ollama.requestsTo("DELETE", "/api/delete").size, 1)
      assertEquals(r.doc.served, Vector("qwen3:0.6b"))
    }
  }

  test("a fetch that fails is reported with Ollama's line, and tried again only after the wait") {
    withRig(rig()("no-such-model:1b")) { r =>
      r.ollama.failFetch("no-such-model:1b", "pull model manifest: file does not exist")
      r.keeper.pass()
      val line = r.doc.model("no-such-model:1b").getOrElse(fail(r.doc.toString))
      assertEquals(line.state, ModelState.Failed)
      assert(line.detail.exists(_.contains("file does not exist")), line.toString)
      r.clock.advance(Duration.ofSeconds(30))
      r.keeper.pass()
      assertEquals(r.ollama.requestsTo("POST", "/api/pull").size, 1, "tried again inside the wait")
      assertEquals(r.state("no-such-model:1b"), Some(ModelState.Failed))
      r.clock.advance(Duration.ofSeconds(31))
      r.keeper.pass()
      assertEquals(
        r.ollama.requestsTo("POST", "/api/pull").size,
        2,
        "not tried again after the wait"
      )
    }
  }

  test("one failed model leaves the others served") {
    withRig(rig()("qwen3:0.6b", "no-such-model:1b")) { r =>
      r.ollama
        .offer("qwen3:0.6b")
        .failFetch("no-such-model:1b", "pull model manifest: file does not exist")
      r.keeper.pass()
      r.keeper.pass()
      assertEquals(r.state("qwen3:0.6b"), Some(ModelState.Served))
      assertEquals(r.state("no-such-model:1b"), Some(ModelState.Failed))
      assert(r.doc.ready)
    }
  }

  test("a declaration that cannot be read keeps the last good one, and the document says why") {
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b")
      r.keeper.pass()
      Files.writeString(r.file, """{"models": ["qwen3:0.6b",""")
      r.keeper.pass()
      assert(!r.doc.ready, r.doc.toString)
      assert(r.doc.detail.nonEmpty, r.doc.toString)
      assertEquals(r.doc.served, Vector("qwen3:0.6b"))
      assert(r.ollama.heldTags.contains("qwen3:0.6b"), "the model was removed on a bad declaration")
      r.declare("qwen3:0.6b")
      r.keeper.pass()
      assert(r.doc.ready, r.doc.toString)
      assertEquals(r.doc.detail, None)
    }
  }

  test("a served model stays served while no call comes") {
    withRig(rig(keepAlive = Some(Duration.ofSeconds(2)))("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b")
      r.keeper.pass()
      Thread.sleep(2500)
      // The stand-in unloads after two seconds unless the load asked otherwise.
      assert(r.ollama.loadedTags.contains("qwen3:0.6b"), "the model was unloaded while idle")
      assertEquals(r.state("qwen3:0.6b"), Some(ModelState.Served))
    }
  }

  test("a model Ollama unloaded is loaded again at the next pass") {
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b")
      r.keeper.pass()
      r.ollama.unload("qwen3:0.6b")
      r.keeper.pass()
      assert(r.ollama.loadedTags.contains("qwen3:0.6b"))
      assertEquals(r.ollama.requestsTo("POST", "/api/pull").size, 1)
    }
  }

  test("a model already held when the server starts is loaded, not fetched") {
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.hold("qwen3:0.6b")
      r.keeper.pass()
      r.keeper.pass()
      assertEquals(r.state("qwen3:0.6b"), Some(ModelState.Served))
      assertEquals(r.ollama.requestsTo("POST", "/api/pull").size, 0)
    }
  }

  test("the probe is not ready before the first pass, nor while Ollama does not answer") {
    withRig(rig(ollamaUrl = Some("http://127.0.0.1:1"))("qwen3:0.6b")) { r =>
      assert(r.keeper.source.ready.isLeft)
      r.keeper.pass()
      assert(r.keeper.source.ready.isLeft, "ready with no Ollama")
      assert(!r.doc.ready)
    }
    withRig(rig()("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b")
      r.keeper.pass()
      assertEquals(r.keeper.source.ready, Right(()))
    }
  }

  test("the loop notices an edited declaration without waiting for the pass interval") {
    withRig(rig(interval = "1h")("qwen3:0.6b")) { r =>
      r.ollama.offer("qwen3:0.6b").offer("llama3.2:1b")
      r.keeper.start()
      val deadline = System.nanoTime() + 20.seconds.toNanos
      while r.state("qwen3:0.6b") != Some(ModelState.Served) && System.nanoTime() < deadline do
        Thread.sleep(100)
      assertEquals(r.state("qwen3:0.6b"), Some(ModelState.Served))
      r.declare("qwen3:0.6b", "llama3.2:1b")
      while r.state("llama3.2:1b") != Some(ModelState.Served) && System.nanoTime() < deadline do
        Thread.sleep(100)
      assertEquals(r.state("llama3.2:1b"), Some(ModelState.Served), r.doc.toString)
    }
  }
