package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.mcp.Docs

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*

/**
 * The pages `ankka mcp` serves name the release of the command line that carries them: every form
 * `docs/release-patterns.txt` lists, in every page on the classpath, names the release the build
 * wrote to `ankka/docs/release.txt` — the CLI's version on a tag, the last release before it
 * otherwise.
 *
 * The patterns are read from the source tree, and matched here independently of the build's own
 * rewrite (`project/ReleasePatterns.scala`), so a build that stopped rewriting, or rewrote a form
 * differently, fails.
 */
class DocsBundleSuite extends munit.FunSuite:

  private def resource(path: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ankka/docs/$path")
    assert(stream != null, s"no ankka/docs/$path on the classpath")
    try String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  private lazy val release: String = resource("release.txt").trim

  /** The repository's docs/, from the test JVM's working directory, the module's or the root's. */
  private lazy val patternsFile: Path =
    Seq(Paths.get("docs/release-patterns.txt"), Paths.get("../docs/release-patterns.txt"))
      .find(Files.isRegularFile(_))
      .getOrElse(fail("docs/release-patterns.txt not found from the test's working directory"))

  private lazy val forms: Vector[Pattern] =
    Files
      .readAllLines(patternsFile)
      .asScala
      .toVector
      .filter(_.startsWith("release "))
      .map { line =>
        val Array(before, after) =
          line.stripPrefix("release ").split(Pattern.quote("{version}"), -1)
        Pattern.compile(
          Pattern.quote(before) + """(\d+\.\d+\.\d+)(?!\.?\d)(?![-+]\w)""" + Pattern.quote(after)
        )
      }

  test(
    "the bundled documentation's release is the CLI's release, or the last before a development build"
  ) {
    assert(release.matches("""\d+\.\d+\.\d+"""), s"release.txt says '$release'")
    Release.parse(com.thinkmorestupidless.ankka.core.BuildInfo.version).foreach { mine =>
      assertEquals(release, mine.render)
    }
  }

  test(
    "every form the patterns file lists names the bundled documentation's release, on every page"
  ) {
    assert(forms.nonEmpty)
    val wrong = for
      page <- Docs.pages
      text = Docs.read(page.path).getOrElse("")
      form <- forms
      matcher = form.matcher(text)
      found <- Iterator.continually(matcher).takeWhile(_.find()).map(_.group(1)).toVector
      if found != release
    yield s"${page.path}: names $found in `${form.pattern}`"
    assertEquals(wrong.toList, Nil)
  }

  test("the Go install line names the bundled documentation's release") {
    assert(
      Docs.read("get-started/install.md").exists(_.contains(s"ankka-go@v$release")),
      s"get-started/install.md does not say ankka-go@v$release"
    )
  }

  test("a release form naming an older release is bundled naming the CLI's release") {
    // A fixture `docs sync` never moves, bundled by the same function as the pages: it names 0.1.0 in
    // its source, so only the rewrite can make it name the bundle's release.
    val fixture = getClass.getClassLoader.getResourceAsStream("ankka/release-fixture/install.md")
    assert(fixture != null, "the fixture was not bundled")
    val text =
      try String(fixture.readAllBytes(), StandardCharsets.UTF_8)
      finally fixture.close()
    assert(text.contains(s"ankka-go@v$release"), text)
    assert(text.contains(s"ankka==$release"), text)
    assert(!text.contains("0.1.0"), text)
  }
