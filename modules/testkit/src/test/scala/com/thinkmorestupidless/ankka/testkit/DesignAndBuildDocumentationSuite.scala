package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `features/documentation/design-and-build.feature`: the statements the published documentation
 * must make about designing and building a change, held to the pages. One test per scenario, named
 * after it, and one more holding the page's skill names to the skills' own.
 */
class DesignAndBuildDocumentationSuite extends munit.FunSuite:

  private lazy val root: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir

  private val pagePath = "get-started/design-and-build.md"

  private def read(path: String): String = Files.readString(root.resolve(path))

  private def page(path: String): String = read(s"docs/$path").replaceAll("\\s+", " ")

  private def says(path: String, statements: String*): Unit =
    val text = page(path)
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  /** The entries of `mkdocs.yml`'s `Get started` section, in order. */
  private def getStarted: List[String] =
    val lines = read("mkdocs.yml").linesIterator.toList
    val start = lines.indexWhere(_.trim == "- Get started:")
    assert(start >= 0, "mkdocs.yml has no Get started section")
    val section = lines.drop(start + 1).takeWhile(_.startsWith("      - "))
    section.map(_.trim.stripPrefix("- ").split(": ").last.trim)

  test("the opening page points first to the page that presents the design and then the build") {
    val index = read("docs/index.md")
    val where = index.linesIterator.dropWhile(_ != "## Where to start").drop(1)
    val first = where.dropWhile(_.trim.isEmpty).next()
    assert(first.contains(s"]($pagePath)"), s"the first bullet of Where to start is: $first")
    val nav = getStarted
    assertEquals(nav.headOption, Some(pagePath), s"Get started opens with ${nav.headOption}")
    assert(
      nav.indexOf(pagePath) < nav.indexOf("get-started/coding-agents.md"),
      "the page does not come before the page on skills and the MCP server"
    )
    says(
      pagePath,
      "# Design, then build",
      "This is how you build with ankka",
      "](coding-agents.md)"
    )
  }

  test("the page says what the design and the build produce and where the developer is asked") {
    says(
      pagePath,
      "## What the design produces",
      "`spec.md`",
      "`plan.md`",
      "`tasks.md`",
      "## Where you are asked",
      "You are not asked about",
      "## Build it",
      "a pull request marked ready for review",
      "It never merges",
      "## Two models",
      "the deeper-thinking model",
      "a cheaper one",
      "`model:`"
    )
  }

  test(
    "the page says how design and build is installed and what the spec, the living features and the glossary are"
  ) {
    says(
      pagePath,
      "## Install",
      "made from the ankka template",
      "does not yet carry",
      "In any other project",
      "uv tool install specify-cli",
      "specify init --here --ai claude --ai-skills",
      "speckit-bdd",
      "/plugin install pr-review@ankka",
      "`specs/NNN-name/`",
      "living features",
      "`GLOSSARY.md`"
    )
  }

  test(
    "the page passes the documentation check and names the design and the build as a developer starts them"
  ) {
    val raw = read(s"docs/$pagePath")
    assert(
      raw.startsWith("---\ntitle: Design, then build\n"),
      "the page's frontmatter is not first"
    )
    val skills = Files
      .list(root.resolve(".claude/skills"))
      .iterator
      .asScala
      .map(_.resolve("SKILL.md"))
      .filter(Files.isRegularFile(_))
      .flatMap(f =>
        Files
          .readAllLines(f)
          .asScala
          .find(_.startsWith("name: "))
          .map(_.stripPrefix("name: ").trim.stripPrefix("\"").stripSuffix("\""))
      )
      .toSet
    // A command as a developer types it: a slash not inside a path or a web address.
    val named = "(?<![\\w./])/speckit-[a-z-]+".r.findAllIn(raw).map(_.stripPrefix("/")).toSet
    assert(
      named.contains("speckit-design") && named.contains("speckit-build"),
      s"the page names $named"
    )
    val unknown = named -- skills
    assert(
      unknown.isEmpty,
      s"the page names commands no skill is called: ${unknown.mkString(", ")}"
    )
  }
