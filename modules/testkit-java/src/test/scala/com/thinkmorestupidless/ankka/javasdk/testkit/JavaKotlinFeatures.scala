package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.testkit.steps.EmbeddedSteps
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * `features/java-kotlin`: the Java rows run here, embedded; every other scenario names the suite or
 * the check that runs it, and a named suite that does not exist fails this one.
 */
class JavaKotlinFeatures
    extends GherkinSuite("../../features/java-kotlin")
    with EmbeddedSteps
    with LogCapturing:

  protected lazy val kotlinSuite = "KotlinFeaturesTest"

  private lazy val kotlin = Map(
    "a component declared in Kotlin's own form behaves as one declared through the Java SDK" -> kotlinSuite,
    "a handler written as a suspending function calls a component and is answered" -> kotlinSuite,
    "a value the Java SDK may give as nothing is nullable in Kotlin"               -> kotlinSuite,
    "a null given where the Java SDK takes a value is refused before the service is built" -> kotlinSuite,
    "a data class is a state or an event as a Java record is" -> kotlinSuite,
    "a data class's default is taken for a field that is absent, and a field with no default that is absent fails recovery" -> kotlinSuite
  )

  override protected def ranElsewhere: Map[String, String] = kotlin ++ Map(
    "a service written in Java or Kotlin is embedded, as a service written in Scala is" ->
      "JavaTemplateSuite and KotlinTemplateSuite",
    "a service started from the template builds with a JDK and no build tool installed" ->
      "JavaTemplateSuite and KotlinTemplateSuite",
    "every type a Java developer writes, reads or is given is a Java type" -> "SurfaceSuite",
    "a state declared in Java and the same state declared in Scala are written the same" ->
      "CrossLanguageBytesSuite",
    "the shopping cart written in Java or Kotlin passes the sample's own features" ->
      "ShoppingCartJavaFeatures and ShoppingCartKotlinFeatures"
  )

  override protected def ranOutside: Map[String, String] = Map(
    "a command to an entity in Java or Kotlin costs at most a tenth more than in Scala" ->
      "measured on one machine and recorded in specs/058-java-kotlin-sdk/research.md, Measurements",
    "the first service in Java or Kotlin needs a JDK and Docker and nothing else" ->
      "the docs check builds the first-service pages from the template's compiled sources",
    "a change to the template is proven on the template before it is merged" ->
      "ci.yml's template-java and template-kotlin jobs, required by branch protection",
    "the documentation says where ankka's Java differs from Akka's" -> "the docs check",
    "every place that names the languages a service may be written in names Java and Kotlin" ->
      "the docs check",
    "the limitations name what the Java and Kotlin SDKs do not offer" -> "the docs check"
  )

  /** Suites named above that a later story of spec 058 writes, each with its story. */
  private val notYet = Map(
    "JavaTemplateSuite"          -> "US2",
    "KotlinTemplateSuite"        -> "US4",
    "ShoppingCartJavaFeatures"   -> "US2",
    "ShoppingCartKotlinFeatures" -> "US4",
    "KotlinFeaturesTest"         -> "US4"
  )

  test("every suite this feature names exists") {
    val named = ranElsewhere.values.flatMap(_.split(" and ")).toSet
    val root  = Paths.get("../..").toRealPath()
    val sources = Using.resource(Files.walk(root)) { paths =>
      paths.iterator.asScala
        .filter(p =>
          isSource(p) && !p.toString.contains("/target/") && !p.toString.contains("/node_modules/")
        )
        .toVector
    }
    def declared(name: String) =
      sources.exists(p => Files.readString(p).contains(s"class $name"))
    val missing = named.filterNot(n => notYet.contains(n) || declared(n))
    assert(missing.isEmpty, s"no suite named ${missing.mkString(", ")}")
    val written = notYet.keySet.filter(declared)
    assert(written.isEmpty, s"${written.mkString(", ")} now exist: remove them from notYet")
  }

  private def isSource(p: Path): Boolean =
    val name = p.getFileName.toString
    name.endsWith(".scala") || name.endsWith(".kt") || name.endsWith(".java")
