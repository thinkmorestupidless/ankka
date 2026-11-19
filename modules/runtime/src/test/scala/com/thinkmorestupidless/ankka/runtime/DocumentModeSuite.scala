package com.thinkmorestupidless.ankka.runtime

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** `ankka.document`'s mode (feature 067, T014), through `DocumentMode.run`, which does not exit. */
class DocumentModeSuite extends munit.FunSuite:

  /** An extension that writes `answer` for `openapi`, and fails the suite if it is started. */
  private final class Writes(answer: Either[Vector[String], String]) extends RuntimeExtension:
    @volatile var asked: Map[String, String] = Map.empty
    def name                                 = "writes"
    def start(service: AnkkaService): Unit   = fail("started")
    override def document(
        kind: String,
        options: Map[String, String],
        registry: com.thinkmorestupidless.ankka.core.ComponentRegistry
    ) =
      asked = options
      Option.when(kind == "openapi")(answer)

  private final class Silent extends RuntimeExtension:
    def name                               = "silent"
    def start(service: AnkkaService): Unit = fail("started")

  private val directory = FunFixture[Path](
    _ => Files.createTempDirectory("document-mode"),
    dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  )

  private def asked(out: Path, more: (String, String)*): Map[String, String] =
    Map("ankka.document" -> "openapi", "ankka.document.out" -> out.toString) ++ more

  private def ankkaThreads: Set[String] =
    Thread.getAllStackTraces.keySet.asScala.map(_.getName).filter(_.startsWith("ankka-")).toSet

  directory.test("a document is written whole and no actor system is created") { dir =>
    val before             = ankkaThreads
    val out                = dir.resolve("openapi.json")
    val extension          = Writes(Right("{}\n"))
    val (status, problems) =
      DocumentMode.run(
        Ankka.service.withExtension(extension),
        asked(out, "ankka.document.strict" -> "true"),
        "cart"
      )
    assertEquals((status, problems), (0, Vector.empty))
    assertEquals(Files.readString(out), "{}\n")
    assertEquals(Files.list(dir).count(), 1L, "a temporary file was left behind")
    assertEquals(extension.asked, Map("strict" -> "true", "name" -> "cart"))
    assertEquals(ankkaThreads -- before, Set.empty[String])
  }

  directory.test("problems write no file and leave an existing file untouched") { dir =>
    val out = dir.resolve("openapi.json")
    Files.writeString(out, "kept")
    val (status, problems) =
      DocumentMode.run(
        Ankka.service.withExtension(Writes(Left(Vector("a", "b")))),
        asked(out),
        "cart"
      )
    assertEquals((status, problems), (1, Vector("a", "b")))
    assertEquals(Files.readString(out), "kept")
    assertEquals(Files.list(dir).count(), 1L)
  }

  directory.test("an unknown document kind") { dir =>
    val (status, problems) = DocumentMode.run(
      Ankka.service.withExtension(Writes(Right("{}"))),
      asked(dir.resolve("x.json"), "ankka.document" -> "asyncapi"),
      "cart"
    )
    assertEquals((status, problems), (1, Vector("unknown document kind: asyncapi")))
    assert(!Files.exists(dir.resolve("x.json")))
  }

  directory.test("no extension answering is a service with no HTTP endpoints") { dir =>
    val (status, problems) =
      DocumentMode.run(Ankka.service.withExtension(Silent()), asked(dir.resolve("x.json")), "cart")
    assertEquals((status, problems), (1, Vector("this service has no HTTP endpoints")))
  }

  test("the mode is asked for only by ankka.document, and reads only its own properties") {
    assertEquals(DocumentMode.requested(Map("ankka.service.name" -> "cart")), None)
    assertEquals(
      DocumentMode.requested(
        Map("ankka.document" -> "openapi", "ankka.document.tags" -> "a", "ankka.http.port" -> "1")
      ),
      Some(Map("ankka.document" -> "openapi", "ankka.document.tags" -> "a"))
    )
  }
