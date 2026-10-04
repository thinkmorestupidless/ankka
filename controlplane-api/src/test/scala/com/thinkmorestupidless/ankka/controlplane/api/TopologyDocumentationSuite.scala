package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.file.{Files, Path, Paths}

/**
 * `features/documentation/topology.feature`: its two scenarios, as cases named after them, against
 * the published pages. A reader who takes observed calls to be every call a service can make has
 * been misled, so the pages that describe the topology say what observed calls are.
 */
class TopologyDocumentationSuite extends munit.FunSuite:

  private val docs: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve("mkdocs.yml")))
      .map(_.resolve("docs"))
      .getOrElse(fail("could not find the repository's docs/"))

  private def page(path: String): String = Files.readString(docs.resolve(path))

  /** The body of one `## heading` section, up to the next `## `. */
  private def section(text: String, heading: String): Option[String] =
    val lines = text.linesIterator.toVector
    lines.indexWhere(_.trim == s"## $heading") match
      case -1 => None
      case start =>
        val rest = lines.drop(start + 1)
        Some(rest.takeWhile(!_.startsWith("## ")).mkString("\n"))

  /** Prose with its line breaks and emphasis taken out, so a sentence can be found whole. */
  private def prose(text: String): String =
    text.replaceAll("[*_`]", "").replaceAll("\\s+", " ").toLowerCase

  test("the documentation of the local console says that observed calls are not every call") {
    val topology = section(page("operate/local-console.md"), "Topology")
      .getOrElse(fail("docs/operate/local-console.md has no `## Topology` section"))
    val said = prose(topology)
    assert(said.contains("declared connection"), "the section describes declared connections")
    assert(said.contains("observed call"), "the section describes observed calls")
    assert(
      said.contains("calls made in the window") && said.contains("not every call"),
      "the section says observed calls are the calls made in the window, not every call: " + topology
    )
  }

  test("the documentation says that the console shows a deployed service's topology") {
    val console = page("operate/console.md")
    val topology = section(console, "A service's topology")
      .getOrElse(fail("docs/operate/console.md has no section on a deployed service's topology"))
    assert(prose(topology).contains("partial"), "it says what a partial topology is")
    assert(
      !prose(page("reference/limitations.md")).contains("shows the control plane's records only"),
      "limitations.md still says the console shows nothing inside a deployed service"
    )
    assert(
      !prose(console).contains("the console shows the control plane's records."),
      "console.md still says it shows the control plane's records alone"
    )
  }
