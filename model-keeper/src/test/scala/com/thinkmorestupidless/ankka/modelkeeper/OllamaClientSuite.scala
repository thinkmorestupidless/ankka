package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.testollama.FakeOllama

import java.net.URI

/** The keeper's calls to Ollama, against the stand-in. */
class OllamaClientSuite extends munit.FunSuite:

  private val ollama = FakeOllama.start()
  private val client = OllamaClient(URI.create(ollama.url))

  override def afterAll(): Unit = ollama.stop()

  test("the version, and what is held and loaded") {
    ollama.hold("held:1b")
    assertEquals(client.version(), Right("0.40.6"))
    assertEquals(client.tags().map(_.get("held:1b")), Right(Some(FakeOllama.digestOf("held:1b"))))
    assertEquals(client.ps(), Right(Set.empty[String]))
  }

  test("a fetch that ends in success, then a load that keeps the model loaded") {
    ollama.offer("qwen3:0.6b", capabilities = Vector("completion", "tools", "thinking"))
    assertEquals(client.pull("qwen3:0.6b"), Right(()))
    assertEquals(client.load("qwen3:0.6b"), Right(()))
    assert(client.ps().exists(_.contains("qwen3:0.6b")))
    assertEquals(client.show("qwen3:0.6b"), Right(Vector("completion", "tools", "thinking")))
    val load = ollama.requestsTo("POST", "/api/generate").last
    assert(load.body.contains("\"keep_alive\":-1"), load.body)
  }

  test("a fetch that fails part way answers Ollama's line") {
    ollama.offer("broken:1b").failFetch("broken:1b", "max retries exceeded: unexpected EOF")
    assertEquals(client.pull("broken:1b"), Left("max retries exceeded: unexpected EOF"))
  }

  test("a fetch of a tag that does not exist answers Ollama's line") {
    assertEquals(client.pull("no-such-model:1b"), Left("pull model manifest: file does not exist"))
  }

  test("a removal, and a model that is not there") {
    ollama.hold("old:1b")
    assertEquals(client.delete("old:1b"), Right(()))
    assert(!ollama.heldTags.contains("old:1b"))
    assertEquals(client.show("old:1b"), Left("model 'old:1b' not found"))
  }

  test("Ollama not answering is a Left, not an exception") {
    val nobody = OllamaClient(URI.create("http://127.0.0.1:1"))
    assert(nobody.version().left.exists(_.contains("not answering")), nobody.version().toString)
    assert(nobody.pull("qwen3:0.6b").isLeft)
  }
