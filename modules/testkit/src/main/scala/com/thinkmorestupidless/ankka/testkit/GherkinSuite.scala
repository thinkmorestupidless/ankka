package com.thinkmorestupidless.ankka.testkit

import io.cucumber.cucumberexpressions.{Expression, ExpressionFactory, ParameterTypeRegistry}
import io.cucumber.gherkin.GherkinParser
import io.cucumber.messages.types.{Pickle, PickleStep, PickleStepType}

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Runs Gherkin features as munit tests: one test per scenario, and per row of a `Scenario Outline`.
 *
 * Steps are defined with Cucumber Expressions, as Cucumber's own runners define them, and match
 * whatever keyword a step was written with — `Given`, `When`, `Then`, `And` and `But` are for the
 * reader:
 *
 * {{{
 * class CartFeatures extends GherkinSuite("features") with LogCapturing:
 *   Given("an empty cart") { () => cart = newCart() }
 *   When("the customer adds {int} {string}") { (quantity: Int, product: String) => add(product, quantity) }
 *   Then("the cart holds {int} items") { (n: Int) => assertEquals(total(), n) }
 * }}}
 *
 * A scenario fails, naming the step and its line, when a step matches no definition (with a
 * definition to paste), matches two, or takes a different number of values than the definition
 * expects. A directory with no scenarios fails the suite rather than passing it: a feature that ran
 * nothing has proved nothing. A scenario tagged `@ignore` is reported ignored, never passed.
 *
 * The suite instance is shared by its scenarios, which run one at a time; `scenarioId` is unique to
 * the running scenario, for ids no other scenario uses. A step's `DocString` or `DataTable`, when
 * it has one, is the definition's last value, as a `String` or a `Seq[Seq[String]]`.
 *
 * @param features
 *   the directory of `.feature` files, or one `.feature` file, relative to the working directory: a
 *   forked sbt test runs in its project's directory, so `"features"` is the project's own. One file
 *   is for features kept by what they describe rather than by the module that tests them, where a
 *   directory holds files no single suite can run.
 */
abstract class GherkinSuite(features: String) extends munit.FunSuite:

  import GherkinSuite.*

  private val registry    = ParameterTypeRegistry(Locale.ENGLISH)
  private val factory     = ExpressionFactory(registry)
  private val definitions = mutable.ArrayBuffer.empty[Definition]

  @volatile private var running: Option[String] = None

  /** Unique to the scenario running now: the feature, its line and the row, as one slug. */
  final protected def scenarioId: String =
    running.getOrElse(
      throw IllegalStateException("scenarioId is only defined while a scenario runs")
    )

  final protected def Given[F](expression: String)(body: F)(using StepBody[F]): Unit =
    define("Given", expression, body)

  final protected def When[F](expression: String)(body: F)(using StepBody[F]): Unit =
    define("When", expression, body)

  final protected def Then[F](expression: String)(body: F)(using StepBody[F]): Unit =
    define("Then", expression, body)

  private def define[F](keyword: String, source: String, body: F)(using step: StepBody[F]): Unit =
    definitions += Definition(
      keyword,
      source,
      factory.createExpression(source),
      step.arity,
      args => step.run(body, args)
    )

  // ── the features, registered as tests when the suite is built ────────────────

  private val directory = Paths.get(features)

  private val parsed: Parsed = parse(directory)

  parsed.errors.foreach { (where, message) =>
    test(s"$where does not parse")(fail(message))
  }

  if parsed.pickles.isEmpty && parsed.errors.isEmpty then
    test(s"$features holds scenarios") {
      fail(
        s"no scenarios under ${directory.toAbsolutePath}: a suite that ran none has checked nothing"
      )
    }

  parsed.pickles.foreach { case Located(pickle, uri, line, stepLines) =>
    val name    = s"${pickle.getName} ($uri:$line)"
    val ignored = pickle.getTags.asScala.exists(_.getName == "@ignore")
    val options = if ignored then name.ignore else munit.TestOptions(name)
    test(options) {
      running = Some(slug(s"$uri-$line"))
      try
        pickle.getSteps.asScala.foreach(step =>
          run(step, uri, stepLines.getOrElse(step.getAstNodeIds.get(0), line))
        )
      finally running = None
    }
  }

  private def run(step: PickleStep, uri: String, line: Int): Unit =
    val text  = step.getText
    val where = s"$uri:$line"
    val matches = definitions.toVector.flatMap { d =>
      d.expression
        .`match`(text)
        .toScala
        .map(args => d -> args.asScala.map(_.getValue: Any).toVector)
    }
    matches match
      case Vector() =>
        fail(s"""no step definition matches "$text" ($where); define it:
                |
                |  ${snippet(keyword(step), text)}""".stripMargin)
      case Vector((definition, values)) =>
        val argument = step.getArgument.toScala.flatMap { a =>
          a.getDocString.toScala
            .map(_.getContent: Any)
            .orElse(
              a.getDataTable.toScala.map(t =>
                t.getRows.asScala.toVector.map(_.getCells.asScala.toVector.map(_.getValue)): Any
              )
            )
        }
        val all = values ++ argument
        if all.size != definition.arity then
          fail(
            s""""$text" ($where) gives ${all.size} value(s) and ${definition.keyword}("${definition.source}") takes ${definition.arity}"""
          )
        definition.run(all)
      case several =>
        fail(
          s""""$text" ($where) matches ${several.size} step definitions; one must go:
             |${several
              .map((d, _) => s"""  ${d.keyword}("${d.source}")""")
              .mkString("\n")}""".stripMargin
        )

object GherkinSuite:

  private final case class Definition(
      keyword: String,
      source: String,
      expression: Expression,
      arity: Int,
      run: Seq[Any] => Unit
  )

  private final case class Located(
      pickle: Pickle,
      uri: String,
      line: Int,
      stepLines: Map[String, Int]
  )

  private final case class Parsed(pickles: Vector[Located], errors: Vector[(String, String)])

  /** How a step's body takes its values: by arity, each value converted to the type it declares. */
  trait StepBody[F]:
    def arity: Int
    def run(body: F, values: Seq[Any]): Unit

  object StepBody:
    given zero[R]: StepBody[() => R] with
      def arity                                      = 0
      def run(body: () => R, values: Seq[Any]): Unit = body(): Unit

    given one[A, R](using a: StepValue[A]): StepBody[A => R] with
      def arity                                     = 1
      def run(body: A => R, values: Seq[Any]): Unit = body(a(values(0))): Unit

    given two[A, B, R](using a: StepValue[A], b: StepValue[B]): StepBody[(A, B) => R] with
      def arity                                          = 2
      def run(body: (A, B) => R, values: Seq[Any]): Unit = body(a(values(0)), b(values(1))): Unit

    given three[A, B, C, R](using a: StepValue[A], b: StepValue[B], c: StepValue[C]): StepBody[
      (A, B, C) => R
    ] with
      def arity = 3
      def run(body: (A, B, C) => R, values: Seq[Any]): Unit =
        body(a(values(0)), b(values(1)), c(values(2))): Unit

    given four[A, B, C, D, R](using
        a: StepValue[A],
        b: StepValue[B],
        c: StepValue[C],
        d: StepValue[D]
    ): StepBody[(A, B, C, D) => R] with
      def arity = 4
      def run(body: (A, B, C, D) => R, values: Seq[Any]): Unit =
        body(a(values(0)), b(values(1)), c(values(2)), d(values(3))): Unit

    given five[A, B, C, D, E, R](using
        a: StepValue[A],
        b: StepValue[B],
        c: StepValue[C],
        d: StepValue[D],
        e: StepValue[E]
    ): StepBody[(A, B, C, D, E) => R] with
      def arity = 5
      def run(body: (A, B, C, D, E) => R, values: Seq[Any]): Unit =
        body(a(values(0)), b(values(1)), c(values(2)), d(values(3)), e(values(4))): Unit

  /** A step definition's parameter type, from what Cucumber Expressions matched. */
  trait StepValue[A]:
    def apply(value: Any): A

  object StepValue:
    private def number(value: Any): java.lang.Number = value match
      case n: java.lang.Number => n
      case s: String           => BigDecimal(s.trim).bigDecimal
      case other               => throw IllegalArgumentException(s"'$other' is not a number")

    given StepValue[String]     = v => String.valueOf(v)
    given StepValue[Int]        = v => number(v).intValue
    given StepValue[Long]       = v => number(v).longValue
    given StepValue[Double]     = v => number(v).doubleValue
    given StepValue[BigDecimal] = v => BigDecimal(String.valueOf(v))
    given StepValue[Boolean] = v =>
      String.valueOf(v).toLowerCase match
        case "true" | "yes" => true
        case "false" | "no" => false
        case other          => throw IllegalArgumentException(s"'$other' is not true or false")
    given StepValue[Seq[Seq[String]]] = {
      case rows: Seq[?] => rows.map { case row: Seq[?] => row.map(String.valueOf) }
      case other        => throw IllegalArgumentException(s"$other is not a table")
    }

  private def keyword(step: PickleStep): String = step.getType.toScala match
    case Some(PickleStepType.ACTION)  => "When"
    case Some(PickleStepType.OUTCOME) => "Then"
    case _                            => "Given"

  /** A definition to paste for an undefined step: quoted text as `{string}`, numbers as `{int}`. */
  private def snippet(keyword: String, text: String): String =
    var parameters = Vector.empty[String]
    val expression = "\"[^\"]*\"|-?\\d+(\\.\\d+)?".r.replaceAllIn(
      text.replace("\\", "\\\\").replace("{", "\\\\{").replace("(", "\\\\("),
      m =>
        val (token, kind) =
          if m.matched.startsWith("\"") then ("{string}", "String")
          else if m.matched.contains('.') then ("{double}", "Double")
          else ("{int}", "Int")
        parameters :+= s"p${parameters.size + 1}: $kind"
        java.util.regex.Matcher.quoteReplacement(token)
    )
    val params = if parameters.isEmpty then "()" else parameters.mkString("(", ", ", ")")
    s"""$keyword("$expression") { $params => ??? }"""

  private def slug(text: String): String =
    text.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

  private def isFeature(path: Path): Boolean =
    Files.isRegularFile(path) && path.toString.endsWith(".feature")

  private def parse(directory: Path): Parsed =
    // A directory, walked, or one feature file: walking a file yields the file. Anything else holds
    // no scenarios, which the suite reports as a failure rather than a pass.
    if !Files.isDirectory(directory) && !isFeature(directory) then
      return Parsed(Vector.empty, Vector.empty)
    val files =
      val stream = Files.walk(directory)
      try
        stream.iterator.asScala.filter(_.toString.endsWith(".feature")).toVector.sortBy(_.toString)
      finally stream.close()
    val parser  = GherkinParser.builder().includeSource(false).build()
    val pickles = Vector.newBuilder[Located]
    val errors  = Vector.newBuilder[(String, String)]
    for file <- files do
      // As walked from the directory given (`features/cart/add.feature`), which is how a spec and the
      // speckit-bdd checker name it.
      val uri       = file.toString.replace(java.io.File.separatorChar, '/')
      val envelopes = parser.parse(file).iterator.asScala.toVector
      // Each AST node's line, for the steps: a pickle step knows its node, not where it is.
      val lines = mutable.Map.empty[String, Int]
      envelopes.flatMap(_.getGherkinDocument.toScala).flatMap(_.getFeature.toScala).foreach {
        feature =>
          def steps(list: java.util.List[io.cucumber.messages.types.Step]): Unit =
            list.asScala.foreach(s => lines(s.getId) = s.getLocation.getLine.intValue)
          def rows(scenario: io.cucumber.messages.types.Scenario): Unit =
            scenario.getExamples.asScala
              .flatMap(_.getTableBody.asScala)
              .foreach(r => lines(r.getId) = r.getLocation.getLine.intValue)
          def children(
              list: Iterable[
                (
                    Option[io.cucumber.messages.types.Background],
                    Option[io.cucumber.messages.types.Scenario],
                    Option[io.cucumber.messages.types.Rule]
                )
              ]
          ): Unit =
            list.foreach { (background, scenario, rule) =>
              background.foreach(b => steps(b.getSteps))
              scenario.foreach { s =>
                lines(s.getId) = s.getLocation.getLine.intValue
                steps(s.getSteps)
                rows(s)
              }
              rule.foreach(r =>
                children(
                  r.getChildren.asScala
                    .map(c => (c.getBackground.toScala, c.getScenario.toScala, None))
                )
              )
            }
          children(
            feature.getChildren.asScala
              .map(c => (c.getBackground.toScala, c.getScenario.toScala, c.getRule.toScala))
          )
      }
      envelopes.flatMap(_.getParseError.toScala).foreach { error =>
        val line = error.getSource.getLocation.toScala.map(_.getLine.intValue).getOrElse(1)
        errors += (s"$uri:$line" -> error.getMessage)
      }
      envelopes.flatMap(_.getPickle.toScala).foreach { pickle =>
        // The last node is the Examples row for an outline, the scenario otherwise: where a reader goes.
        val ids  = pickle.getAstNodeIds.asScala
        val line = ids.reverseIterator.flatMap(lines.get).nextOption().getOrElse(1)
        pickles += Located(pickle, uri, line, lines.toMap)
      }
    Parsed(pickles.result(), errors.result())
