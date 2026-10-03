package com.thinkmorestupidless.ankka.cli

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}

/**
 * The local console's rules for reading a topology, which are JavaScript and so run under Node.
 *
 * `topology.js` decides what the page shows; `cli/src/test/js/topology.test.js` runs every fixture
 * in `console/package/fixtures/topology/` through it. This suite is how that test reaches the
 * build, and it holds the fixtures to `features/topology/reading.feature`: that feature's scenarios
 * are about what a console does with a topology, so no JVM suite can run them, and each must be a
 * fixture of its name instead. A scenario added there without one fails here.
 */
final class ConsoleTopologyJsSuite extends munit.FunSuite:

  private val root     = CliReferenceSuite.repoRoot
  private val fixtures = root.resolve("console/package/fixtures/topology")
  private val feature  = root.resolve("features/topology/reading.feature")

  private def onPath(tool: String): Boolean =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .exists(dir => Files.isExecutable(Path.of(dir).resolve(tool)))

  private def scenarios: Vector[String] =
    val Scenario = """^\s*Scenario(?: Outline)?:\s*(.+?)\s*$""".r
    Files.readAllLines(feature).asScala.toVector.collect { case Scenario(name) => name }

  /** The scenario each fixture says it is, read as text: this suite parses no JSON. */
  private def fixtureScenarios: Set[String] =
    val Named  = """(?m)^\s*"scenario":\s*"(.*)",?\s*$""".r
    val stream = Files.list(fixtures)
    try
      stream.iterator.asScala
        .filter(_.toString.endsWith(".json"))
        .flatMap(file => Named.findFirstMatchIn(Files.readString(file)).map(_.group(1)))
        .toSet
    finally stream.close()

  test("the feature holds scenarios, so the next case has something to hold fixtures to") {
    assert(scenarios.sizeIs >= 6, s"$feature: $scenarios")
  }

  test("every scenario of reading a topology is a fixture of its name") {
    val untested = scenarios.filterNot(fixtureScenarios)
    assert(
      untested.isEmpty,
      untested
        .map(name => s"no fixture in $fixtures has \"scenario\": \"$name\"")
        .mkString("\n")
    )
  }

  test("the console's rules give what every fixture expects") {
    // Under CI a missing `node` is a failure: a job that asked for this and ran nothing must not
    // read as green. On a developer's machine it is a skip.
    if !onPath("node") then
      if sys.env.contains("CI") then
        fail("node is not on PATH, so the console's rules were not run")
      else assume(false, "node is not on PATH")

    val output = StringBuilder()
    val log    = ProcessLogger(line => output.append(line).append('\n'): Unit)
    val exit =
      Process(Seq("node", "--test", "cli/src/test/js/topology.test.js"), root.toFile).!(log)
    assertEquals(exit, 0, output.toString)
    // Node exits 0 for a file that held no test; the count is what says the fixtures were run.
    val ran = """(?m)^. pass (\d+)$""".r.findFirstMatchIn(output.toString).map(_.group(1).toInt)
    assert(ran.exists(_ > 1), s"no fixture was run:\n$output")
  }
