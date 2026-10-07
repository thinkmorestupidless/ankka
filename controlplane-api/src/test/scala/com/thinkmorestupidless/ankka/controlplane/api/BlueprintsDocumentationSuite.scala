package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The one scenario of `features/documentation/blueprints.feature`, reading the published pages: the
 * guide describes every pattern, every blueprint it shows is included from code a test runs, and it
 * says what blueprints do not do.
 */
class BlueprintsDocumentationSuite extends munit.FunSuite:

  private val repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("docs")) && Files.exists(p.resolve("mkdocs.yml")))
      .getOrElse(fail("no repository root above the working directory"))

  private def read(path: Path): String   = Files.readString(path, UTF_8)
  private def page(path: String): String = read(repoRoot.resolve("docs").resolve(path))

  private def says(path: String, text: String): Unit =
    assert(page(path).contains(text), s"$path does not say: $text")

  private val Page = "build/blueprints.md"

  test("the blueprints guide documents every pattern with samples from tested code") {
    for pattern <- Seq(
        "ask step",
        "work step",
        "for-each step",
        "gather step",
        "judge step",
        "critique step",
        "call step"
      )
    do says(Page, pattern)

    // Every blueprint shown is included from a file a test compiles and runs: a sample's main code,
    // which its suite runs, or a test fixture.
    val includes = "<!-- include: (\\S+?) -->".r.findAllMatchIn(page(Page)).map(_.group(1)).toVector
    assert(includes.nonEmpty, "the guide includes nothing")
    val tested = Seq(
      "samples/multi-agent-planner/src/main/scala/planner/application/PlannerBlueprint.scala",
      "samples/research-digest/src/main/resources/blueprints/digest.json",
      "samples/research-digest/src/main/scala/digest/application/Blueprints.scala",
      "modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/blueprint/"
    )
    includes.foreach { include =>
      val file = include.takeWhile(_ != '#')
      assert(tested.exists(file.startsWith), s"$include is not from code a test runs")
      assert(Files.exists(repoRoot.resolve(file)), s"$file does not exist")
    }
    assert(includes.exists(_.contains("GuideSamples.scala#blueprint")))
    assert(includes.exists(_.contains("PlannerBlueprint.scala#blueprint")))
    assert(includes.exists(_.contains("blueprints/digest.json")))

    // What blueprints do not do, on the guide and on the limitations page.
    says(Page, "## What a blueprint does not do")
    says(
      "reference/limitations.md",
      "**Blueprints are Scala only, and say nothing a step cannot.**"
    )

    // Listed, and carried by a skill.
    val nav = read(repoRoot.resolve("mkdocs.yml"))
    assert(nav.contains(s"- Blueprints: $Page"), "the page is not in the navigation")
    val skills = Files
      .list(repoRoot.resolve("tools/docs/skill"))
      .iterator
      .asScala
      .map(_.resolve("SKILL.md"))
      .filter(Files.exists(_))
      .filter(skill => read(skill).contains(s"  - $Page\n"))
      .toVector
    assert(skills.nonEmpty, "no skill lists the page")
  }
