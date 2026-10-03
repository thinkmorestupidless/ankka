package com.thinkmorestupidless.ankka.controlplane

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Which variables are the platform's is said once, in `core`'s `PlatformVariables`.
 *
 * The control plane, the operator and the module host each read it, and the suites that test their
 * behaviour iterate it, so a variable added to the declaration is tested with no test changed. What
 * those suites cannot see is a second list: a copy pasted back into the operator would satisfy
 * every behavioural test while the two drifted again, which is how there came to be three. This
 * suite reads the sources instead.
 *
 * A collection of `name -> value` pairs is what a program *sets* — the operator's database
 * variables are one — not a statement of which variables are the platform's, and is allowed.
 */
final class PlatformDeclarationSuite extends munit.FunSuite:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.isRegularFile(p.resolve("build.sbt")) && Files.isDirectory(p.resolve("operator"))
      )
      .getOrElse(fail("could not find the repository root"))

  private def scalaFiles(under: Path): Vector[Path] =
    if !Files.isDirectory(under) then Vector.empty
    else
      val stream = Files.walk(under)
      try
        stream.iterator.asScala
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
          .filterNot { p =>
            // Relative to the root: a worktree may itself live under `.claude/`.
            val s = "/" + repoRoot.relativize(p).toString
            s.contains("/target/") || s.contains("/node_modules/") || s.contains("/.claude/")
          }
          .toVector
      finally stream.close()

  private val Literal = """\s*"([^"]*)"\s*""".r

  /** Each `Vector(…)` or `Set(…)` whose elements are all bare string literals naming a variable. */
  private[controlplane] def lists(source: String): Vector[String] =
    val uncommented = source.linesIterator.map(_.replaceAll("//.*$", "")).mkString("\n")
    val opening     = """\b(Vector|Set)\(""".r
    opening
      .findAllMatchIn(uncommented)
      .flatMap { m =>
        var depth = 1
        var i     = m.end
        while depth > 0 && i < uncommented.length do
          uncommented(i) match
            case '(' => depth += 1
            case ')' => depth -= 1
            case _   => ()
          i += 1
        val body     = uncommented.substring(m.end, i - 1)
        val elements = body.split(",").map(_.trim).filter(_.nonEmpty).toVector
        val literals = elements.collect { case Literal(v) => v }
        Option.when(
          elements.nonEmpty && literals.size == elements.size &&
            literals.exists(v => v.startsWith("ANKKA_") || v.startsWith("ANTHROPIC_"))
        )(s"${m.group(1)}($body)")
      }
      .toVector

  test("there is one declaration of the platform's variables") {
    val declarations =
      scalaFiles(repoRoot).filter(_.getFileName.toString == "PlatformVariables.scala")
    assertEquals(
      declarations.map(repoRoot.relativize(_).toString),
      Vector(
        "modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/PlatformVariables.scala"
      )
    )
  }

  test("the operator, the sidecar and the control plane's API hold no list of their own") {
    val readers =
      Vector("operator", "sidecar", "controlplane-api").map(m => repoRoot.resolve(s"$m/src/main"))
    val found = for
      file <- readers.flatMap(scalaFiles)
      list <- lists(Files.readString(file))
    yield s"${repoRoot.relativize(file)}: $list"
    assertEquals(found, Vector.empty, found.mkString("\n"))
  }

  test("the operator compiles the declaration rather than depending on core") {
    val build = Files.readString(repoRoot.resolve("build.sbt"))
    assert(
      build.contains("com/thinkmorestupidless/ankka/core/PlatformVariables.scala"),
      "the operator's unmanagedSources must name the declaration"
    )
  }

  test("the detector finds a list and passes a set of pairs") {
    assertEquals(
      lists("""val L = Vector("ANTHROPIC_", "ANKKA_MODEL_") // the model's"""),
      Vector("""Vector("ANTHROPIC_", "ANKKA_MODEL_")""")
    )
    assertEquals(lists("""val E = Vector("ANKKA_DB_SSL_MODE" -> "verify-full")"""), Vector.empty)
    assertEquals(lists("""val P = Set("GREETING")"""), Vector.empty)
  }
