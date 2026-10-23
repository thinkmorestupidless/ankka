package com.thinkmorestupidless.ankka.javasdk.testkit.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * The documentation rows of `features/java-kotlin/embedded.feature`, read from the pages and the
 * rendered skills in this checkout: the divergences section, every paragraph that lists the
 * languages a service may be written in, and the limitations' bullets for the two SDKs.
 */
trait DocumentationSteps extends GherkinSuite:

  private lazy val root: Path = Paths.get("../..").toRealPath()

  private def page(relative: String): String = Files.readString(root.resolve(relative))

  private def markdown(under: String): Vector[Path] =
    Using.resource(Files.walk(root.resolve(under))) { paths =>
      paths.iterator.asScala.filter(_.getFileName.toString.endsWith(".md")).toVector
    }

  /** A list of the languages as alternatives, as a page writes one: four, or the first three. */
  private val languages =
    """Scala, Python(?:, TypeScript)? or (?:TypeScript|Rust)|Scala, Python, TypeScript,? (?:or|and) Rust""".r

  private var places: Vector[(Path, String)] = Vector.empty
  private var section: String                = ""
  private var bullet: Option[String]         = None

  Given("the published documentation") { () =>
    assert(Files.isDirectory(root.resolve("docs")), s"no docs/ under $root")
  }

  Given("the published documentation and the platform's skills") { () =>
    assert(Files.isDirectory(root.resolve("docs")), s"no docs/ under $root")
    assert(
      Files.isDirectory(root.resolve("marketplace/plugins/ankka/skills")),
      "no rendered skills"
    )
  }

  When("a reader reads where ankka differs from Akka about writing a service in Java") { () =>
    val divergences = page("docs/reference/akka-divergences.md")
    val heading     = "## The Java SDK adopts Akka's annotated handlers"
    val start       = divergences.indexOf(heading)
    assert(start >= 0, s"akka-divergences.md has no section '$heading'")
    val end = divergences.indexOf("\n## ", start + heading.length)
    section = divergences.substring(start, if end < 0 then divergences.length else end)
  }

  Then("the documentation says each difference and why") { () =>
    val differences = section.linesIterator.count(_.startsWith("- **"))
    assert(differences >= 3, s"the section names $differences differences:\n$section")
    assert(section.toLowerCase.contains("because"), s"the section gives no reason:\n$section")
  }

  When("a reader reads any place that names the languages a service may be written in") { () =>
    val files = markdown("docs") ++ markdown("marketplace/plugins/ankka/skills") :+
      root.resolve("README.md")
    places = files.flatMap { file =>
      Files.readString(file).split("\n\\s*\n").toVector.collect {
        case paragraph if languages.findFirstIn(paragraph).isDefined => file -> paragraph
      }
    }
    assert(places.nonEmpty, "no place names the languages: the pattern matches nothing")
  }

  Then("Java and Kotlin are named") { () =>
    // A paragraph about agents names the languages an agent may be written in, which are not yet
    // Java's or Kotlin's (docs/reference/limitations.md says so).
    val missing = places.collect {
      case (file, paragraph)
          if !(paragraph.contains("Java") && paragraph.contains("Kotlin")) &&
            !paragraph.toLowerCase.contains("agent") =>
        s"${root.relativize(file)}: ${paragraph.linesIterator.find(languages.findFirstIn(_).isDefined).getOrElse(paragraph).trim}"
    }
    assert(missing.isEmpty, missing.mkString("named without Java and Kotlin:\n  ", "\n  ", ""))
  }

  When("a reader reads the limitations about the SDK for {string}") { (language: String) =>
    bullet = page("docs/reference/limitations.md")
      .split("\n(?=- )")
      .find(_.startsWith(s"- **The $language SDK"))
  }

  Then("what the SDK for {string} does not offer is named") { (language: String) =>
    val text = bullet.getOrElse(fail(s"limitations.md has no bullet for the $language SDK"))
    assert(
      text.contains("cannot") || text.contains(" no "),
      s"the bullet names nothing missing: $text"
    )
  }
