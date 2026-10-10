package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}

/**
 * `features/documentation/view-streams.feature`: the statements the published documentation must
 * make about a query answered as a stream and a watched query, held to the pages. One test per
 * scenario, named after it.
 */
class ViewStreamsDocumentationSuite extends munit.FunSuite:

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

  /** The text of one section of a page, from its heading to the next of the same level. */
  private def section(path: String, heading: String): String =
    val text  = Files.readString(docs.resolve(path))
    val start = text.indexOf(s"\n$heading\n")
    assert(start >= 0, s"$path has no section $heading")
    val level = heading.takeWhile(_ == '#')
    val end   = text.indexOf(s"\n$level ", start + heading.length + 2)
    text.substring(start, if end < 0 then text.length else end)

  test("the documentation of views describes a query answered as a stream and a watched query") {
    says(
      "build/views.md",
      "## Streaming a query",
      "gives each row as the database yields it, in the statement's order, with no limit unless the caller gives one",
      "## Watching a query",
      "A query is watched only when its view declares it watchable",
      "`WatchEvent.CaughtUp`",
      "`WatchEvent.Removed(key)`"
    )
    // In each language that has them.
    for heading <- Seq("## Streaming a query", "## Watching a query") do
      val text = section("build/views.md", heading)
      for language <- Seq("Scala", "Python", "TypeScript") do
        assert(text.contains(s"/// tab | $language"), s"$heading has no $language tab")
  }

  test("the documentation says a watch is live and not a record") {
    says(
      "build/views.md",
      "### A watch is live, not a record",
      "### How a watch ends",
      "rebuilt",
      "instance stopping",
      "A watcher that still wants the rows watches again",
      "A reader that must see every change — to count them, to act on each — reads the source itself with a [consumer](consumers.md)"
    )
  }

  test("the documentation says what view streams do not do") {
    says(
      "reference/limitations.md",
      "**A module reads a view whole.**",
      "**A watch of a view guarantees no delivery.**",
      "may give a watcher fewer versions of a row than were written"
    )
  }
