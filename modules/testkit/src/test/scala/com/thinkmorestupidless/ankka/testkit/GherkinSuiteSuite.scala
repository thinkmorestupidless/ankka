package com.thinkmorestupidless.ankka.testkit

import munit.MUnitRunner
import org.junit.runner.Description
import org.junit.runner.notification.{Failure, RunListener, RunNotifier}

import java.nio.file.Files
import scala.collection.mutable

// The suites GherkinSuiteSuite runs over features it writes. Abstract, so sbt does not run them
// itself: several fail on purpose. Each takes the directory of its features.

abstract class CartSteps(features: String) extends GherkinSuite(features):
  var items = 0
  val ids   = mutable.ListBuffer.empty[String]
  Given("an empty cart") { () =>
    items = 0
    ids += scenarioId
  }
  When("the customer adds {int} items")((n: Int) => items += n)
  Then("the cart holds {int} items")((n: Int) => assertEquals(items, n))

abstract class AmbiguousSteps(features: String) extends CartSteps(features):
  When("the customer adds {int} {word}")((n: Int, _: String) => items += n)

abstract class ArgumentSteps(features: String) extends GherkinSuite(features):
  var seen: Any = null
  Given("these items:") { (rows: Seq[Seq[String]]) => seen = rows }
  Given("this note:") { (text: String) => seen = text }
  Given("a note {string} with an extra value") { (text: String, other: String) =>
    seen = (text, other)
  }

/** Runs each inner suite through munit's runner and reports what passed, failed and was ignored. */
class GherkinSuiteSuite extends munit.FunSuite:

  private final case class Run(
      passed: Vector[String],
      failed: Map[String, String],
      ignored: Vector[String]
  )

  private def features(files: (String, String)*): String =
    val dir = Files.createTempDirectory("features")
    files.foreach { (name, text) =>
      val file = dir.resolve(name)
      Files.createDirectories(file.getParent)
      Files.writeString(file, text)
    }
    dir.toString

  private def run(cls: Class[? <: munit.Suite], suite: () => munit.Suite): Run =
    val failed   = mutable.LinkedHashMap.empty[String, String]
    val ignored  = mutable.ListBuffer.empty[String]
    val started  = mutable.ListBuffer.empty[String]
    val notifier = RunNotifier()
    notifier.addListener(new RunListener:
      override def testStarted(d: Description): Unit = started += d.getMethodName
      override def testFailure(f: Failure): Unit = failed(f.getDescription.getMethodName) =
        f.getMessage
      override def testIgnored(d: Description): Unit = ignored += d.getMethodName
      override def testAssumptionFailure(f: Failure): Unit =
        ignored += f.getDescription.getMethodName)
    MUnitRunner(cls, suite).run(notifier)
    Run(started.filterNot(failed.contains).toVector, failed.toMap, ignored.toVector)

  private val cart = """Feature: Cart
                       |  Scenario: adding items
                       |    Given an empty cart
                       |    When the customer adds 2 items
                       |    Then the cart holds 2 items
                       |
                       |  Scenario Outline: adding more
                       |    Given an empty cart
                       |    When the customer adds <n> items
                       |    Then the cart holds <total> items
                       |
                       |    Examples:
                       |      | n | total |
                       |      | 1 | 1     |
                       |      | 3 | 4     |
                       |""".stripMargin

  test("each scenario and each Examples row is a test, named with its file and line") {
    val dir   = features("cart/add.feature" -> cart)
    val suite = new CartSteps(dir) {}
    val run   = this.run(classOf[CartSteps], () => suite)
    assertEquals(run.passed.size, 2, run.toString)
    assert(run.passed.exists(_.startsWith("adding items (")), run.toString)
    assert(run.passed.forall(_.contains("cart/add.feature:")), run.toString)
    // The row whose total is wrong fails, at the row's own line.
    assertEquals(run.failed.size, 1, run.toString)
    val (name, message) = run.failed.head
    assert(name.endsWith("cart/add.feature:15)"), name)
    assert(message.contains("values are not the same"), message)
  }

  test("every scenario gets its own scenarioId") {
    val suite = new CartSteps(features("add.feature" -> cart)) {}
    run(classOf[CartSteps], () => suite): Unit
    assertEquals(suite.ids.size, 3)
    assertEquals(suite.ids.distinct.size, 3, suite.ids.toString)
  }

  test(
    "an undefined step fails its scenario, naming the step, its line and a definition to paste"
  ) {
    val text = cart.replace(
      "When the customer adds 2 items",
      "When the customer removes \"pen\" and 2 items"
    )
    val suite = new CartSteps(features("add.feature" -> text)) {}
    val run   = this.run(classOf[CartSteps], () => suite)
    val (_, message) =
      run.failed.find(_._1.startsWith("adding items")).getOrElse(fail(run.toString))
    assert(
      message.contains("""no step definition matches "the customer removes "pen" and 2 items""""),
      message
    )
    assert(message.contains("add.feature:4"), message)
    assert(
      message.contains(
        """When("the customer removes {string} and {int} items") { (p1: String, p2: Int) => ??? }"""
      ),
      message
    )
  }

  test("a step two definitions match fails, naming both") {
    val suite = new AmbiguousSteps(features("add.feature" -> cart)) {}
    val run   = this.run(classOf[AmbiguousSteps], () => suite)
    val (_, message) =
      run.failed.find(_._1.startsWith("adding items")).getOrElse(fail(run.toString))
    assert(message.contains("matches 2 step definitions"), message)
    assert(
      message.contains("""When("the customer adds {int} items")""") && message.contains("{word}"),
      message
    )
  }

  test("a DataTable and a DocString are the definition's last value") {
    val table =
      "Feature: f\n  Scenario: t\n    Given these items:\n      | p1 | 2 |\n      | p2 | 5 |\n"
    val suite = new ArgumentSteps(features("t.feature" -> table)) {}
    assertEquals(run(classOf[ArgumentSteps], () => suite).failed, Map.empty)
    assertEquals(suite.seen, Vector(Vector("p1", "2"), Vector("p2", "5")))

    val note =
      "Feature: f\n  Scenario: n\n    Given this note:\n      \"\"\"\n      hello\n      \"\"\"\n"
    val other = new ArgumentSteps(features("n.feature" -> note)) {}
    assertEquals(run(classOf[ArgumentSteps], () => other).failed, Map.empty)
    assertEquals(other.seen, "hello")
  }

  test("a step that gives more or fewer values than its definition takes fails, saying both") {
    val text  = "Feature: f\n  Scenario: n\n    Given a note \"hi\" with an extra value\n"
    val suite = new ArgumentSteps(features("n.feature" -> text)) {}
    val (_, message) =
      run(classOf[ArgumentSteps], () => suite).failed.headOption.getOrElse(fail("it passed"))
    assert(message.contains("gives 1 value(s)") && message.contains("takes 2"), message)
  }

  test("a directory with no scenarios fails rather than passing having run nothing") {
    val suite = new CartSteps(features()) {}
    val run   = this.run(classOf[CartSteps], () => suite)
    assertEquals(run.passed, Vector.empty)
    assert(run.failed.values.exists(_.contains("no scenarios under")), run.toString)
  }

  test("a feature that does not parse fails, at its line") {
    val broken = "Feature: f\n  Scenario: s\n    Given an empty cart\n    this is not a step\n"
    val suite  = new CartSteps(features("broken.feature" -> broken)) {}
    val run    = this.run(classOf[CartSteps], () => suite)
    assert(run.failed.keys.exists(_.endsWith("broken.feature:4 does not parse")), run.toString)
  }

  test("a scenario tagged @ignore is reported ignored, not passed") {
    val text  = cart.replace("  Scenario: adding items", "  @ignore\n  Scenario: adding items")
    val suite = new CartSteps(features("add.feature" -> text)) {}
    val run   = this.run(classOf[CartSteps], () => suite)
    assert(run.ignored.exists(_.startsWith("adding items")), run.toString)
    assert(!run.passed.exists(_.startsWith("adding items")), run.toString)
  }
