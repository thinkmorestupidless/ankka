package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * No page may show a descriptor the platform would refuse.
 *
 * Every `json` code block titled `service.json` in `docs/` is read with the same codec and checked
 * with the same rules `ankka services apply` applies, on both ends. A deliberately invalid example
 * is shown without that title.
 */
class DocumentationDescriptorsSuite extends munit.FunSuite:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve("mkdocs.yml")))
      .getOrElse(fail("could not find the repository root"))

  private val Open = """^```json\s+title="[^"]*service\.json".*$""".r

  /** (page, line, descriptor text) for every titled descriptor block. */
  private def blocks: Vector[(String, Int, String)] =
    val docs = repoRoot.resolve("docs")
    Files
      .walk(docs)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".md") && !docs.relativize(p).toString.startsWith("design"))
      .toVector
      .sorted
      .flatMap { page =>
        val lines = Files.readAllLines(page).asScala.toVector
        lines.zipWithIndex.collect {
          case (line, index) if Open.matches(line) =>
            val body = lines.drop(index + 1).takeWhile(_.trim != "```").mkString("\n")
            (docs.relativize(page).toString, index + 1, body)
        }
      }

  test("the documentation shows descriptors") {
    assert(blocks.nonEmpty, "no ```json title=\"service.json\" block anywhere in docs/")
  }

  test("every descriptor in the documentation is one the platform accepts") {
    val refused = blocks.flatMap { (page, line, text) =>
      val problems =
        try readFromString[ServiceDescriptor](text).problems
        catch case error: Exception => Vector(s"does not decode: ${error.getMessage}")
      problems.map(problem => s"docs/$page:$line: $problem")
    }
    assert(refused.isEmpty, refused.mkString("\n", "\n", ""))
  }
