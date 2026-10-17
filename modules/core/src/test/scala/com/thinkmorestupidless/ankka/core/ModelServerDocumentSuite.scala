package com.thinkmorestupidless.ankka.core

/**
 * The model server's status document as `core` reads and writes it. The fixture is the one the
 * operator's Jackson reading is held to (`ModelServerDocumentReaderSuite`), so the two cannot
 * drift.
 */
class ModelServerDocumentSuite extends munit.FunSuite:

  private val fixture =
    scala.io.Source.fromResource("ankka/models/document.json").mkString

  test("the fixture reads as the document it describes") {
    val document = ModelServerDocumentJson.read(fixture).fold(fail(_), identity)
    assertEquals(document.server, "https://models.models-system.svc:8443")
    assert(document.ready)
    assertEquals(document.served, Vector("qwen3:0.6b"))
    assertEquals(
      document.model("no-such-model:1b").flatMap(_.detail),
      Some("pull model manifest: file does not exist")
    )
    assertEquals(
      document.model("qwen3:0.6b").map(_.capabilities.toSet),
      Some(Set("completion", "tools", "thinking"))
    )
    assertEquals(document.calls, ModelCalls(answered = 1240, failed = 3, inFlight = 1, waiting = 0))
  }

  test("a document round-trips, and every field is written") {
    val document = ModelServerDocument(
      server = "https://models.example:8443",
      ready = false,
      models = Vector(ModelStatus("qwen3:0.6b", ModelState.Fetching))
    )
    val text = ModelServerDocumentJson.write(document)
    assertEquals(ModelServerDocumentJson.read(text), Right(document))
    // Defaults and empty lists are written, so the operator's Jackson needs no notion of them.
    assert(text.contains("\"models\""), text)
    assert(text.contains("\"capabilities\":[]"), text)
    assert(text.contains("\"waiting\":0"), text)
  }

  test("an absent detail is not written") {
    val text = ModelServerDocumentJson.write(ModelServerDocument("https://m", ready = true))
    assert(!text.contains("detail"), text)
  }

  test("a model in a state nobody knows is refused, naming it") {
    val text = """{"server":"s","ready":true,"models":[{"name":"m:1b","state":"pulling"}]}"""
    assertEquals(
      ModelServerDocumentJson.read(text),
      Left("model \"m:1b\" is in an unknown state \"pulling\"")
    )
  }

  test("something that is not a document is refused") {
    assert(ModelServerDocumentJson.read("not json").isLeft)
  }
