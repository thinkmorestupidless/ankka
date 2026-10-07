package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * features/graph-deltas/documentation.feature, the scenarios feature 037 changed and added: the
 * graph story is told to the end here, and no page depends on ankka-flow.
 */
class GraphDocumentationSuite extends munit.FunSuite:

  private lazy val docs: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir.resolve("docs")

  private def page(path: String): String =
    Files.readString(docs.resolve(path)).replaceAll("\\s+", " ")

  private def says(path: String, statements: String*): Unit =
    val text = page(path)
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  test("the documentation says the topic must be declared compacted on the project") {
    says(
      "build/graph.md",
      "## The topic",
      "must be **compacted**",
      "declared once with `--compacted`",
      "ankka projects topics set cart-graph --partitions 3 --compacted -p checkout",
      "A topic already made is made compacted when its declaration says so"
    )
  }

  test("the documentation tells the graph story to the end without ankka-flow") {
    says("build/graph.md", "## From the topic to the store", "graph-sink.md")
    says(
      "deploy/graph-sink.md",
      "## Declare the topic compacted",
      "## Deploy the sink",
      "## What the store holds",
      "## Rebuild the store from the topic",
      "## The sink in a service of your own"
    )
    // No page depends on ankka-flow: the one mention left is the contributing page, which names
    // the documentation tool the two projects shared.
    val mentioning = Files
      .walk(docs)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".md"))
      .filter(p =>
        Files.readString(p).toLowerCase.contains("ankka-flow") || Files
          .readString(p)
          .contains("flow.ankka.cloud")
      )
      .map(p => docs.relativize(p).toString)
      .toVector
      .sorted
    assertEquals(mentioning, Vector("contributing/documentation.md"))
  }
