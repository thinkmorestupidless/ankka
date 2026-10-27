package com.thinkmorestupidless.ankka.javasdk.testkit.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * The documentation rows of `features/java-kotlin/embedded.feature`, read from the pages and the
 * rendered skills in this checkout: every paragraph that lists the languages a service may be
 * written in, and the limitations' bullets for the two SDKs.
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
    val missing = places.collect {
      case (file, paragraph) if !(paragraph.contains("Java") && paragraph.contains("Kotlin")) =>
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
