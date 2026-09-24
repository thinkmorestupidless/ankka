package com.thinkmorestupidless.ankka.cli

import java.nio.file.{Files, Path, Paths}

/**
 * The CLI reference page is generated from the command tree itself.
 *
 * `docs/reference/cli.md` holds the help of every command between two generated-block comments.
 * This suite renders that block from `Main`'s own decline tree and fails when the page differs, so
 * a new command, option or description is a failing build until the page carries it. Run with
 * `-Dankka.docs.update=true` to rewrite the block instead:
 *
 * {{{
 * sbt -Dankka.docs.update=true 'cli/testOnly *CliReferenceSuite'
 * }}}
 */
class CliReferenceSuite extends munit.FunSuite:

  private val Page = "docs/reference/cli.md"
  private val Name = "cli"

  test("the CLI reference page lists every command, as the command tree describes it") {
    val root     = CliReferenceSuite.repoRoot
    val path     = root.resolve(Page)
    val current  = Files.readString(path)
    val expected = CliReferenceSuite.replaceBlock(current, Name, CliReferenceSuite.render())
    if expected != current then
      if sys.props.get("ankka.docs.update").contains("true") then
        Files.writeString(path, expected): Unit
      else
        fail(
          s"$Page is stale: a command, option or description changed. Run " +
            "sbt -Dankka.docs.update=true 'cli/testOnly *CliReferenceSuite' and commit the page."
        )
  }

  test("every command in the tree is reachable by its help") {
    val paths = CliReferenceSuite.commands()
    assert(paths.contains(Seq("services", "apply")), paths.mkString(", "))
    assert(paths.contains(Seq("local", "console")), paths.mkString(", "))
  }

object CliReferenceSuite:

  def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve("mkdocs.yml")))
      .getOrElse(throw IllegalStateException("could not find the repository root"))

  /** The help decline prints for one command path, exactly as a user sees it. */
  def help(path: Seq[String]): String =
    Main.command.parse(path :+ "--help", Map.empty) match
      case Left(help) => help.toString
      case Right(_) =>
        throw IllegalStateException(s"--help parsed as a command at ${path.mkString(" ")}")

  /**
   * Subcommand names from a help text's `Subcommands:` section, in the order the tree declares
   * them.
   */
  private def subcommands(text: String): Vector[String] =
    val lines = text.linesIterator.toVector
    val start = lines.indexWhere(_.trim == "Subcommands:")
    if start < 0 then Vector.empty
    else
      lines
        .drop(start + 1)
        .takeWhile(line => line.isEmpty || line.startsWith(" "))
        .collect { case line if line.startsWith("    ") && !line.startsWith("     ") => line.trim }
        .filter(_.nonEmpty)

  /** Every command path, depth first, the root first. */
  def commands(): Vector[Seq[String]] =
    def walk(path: Seq[String]): Vector[Seq[String]] =
      path +: subcommands(help(path)).flatMap(name => walk(path :+ name))
    walk(Seq.empty)

  def render(): String =
    val sections = commands().map { path =>
      val title = ("ankka" +: path).mkString(" ")
      s"### `$title`\n\n```text\n${help(path).stripTrailing()}\n```\n"
    }
    sections.mkString("\n")

  /** The page with the named generated block's contents replaced. */
  def replaceBlock(page: String, name: String, content: String): String =
    val open  = s"<!-- generated:start $name -->\n"
    val close = s"<!-- generated:end $name -->"
    val from  = page.indexOf(open)
    val to    = page.indexOf(close)
    if from < 0 || to < from then
      throw IllegalStateException(s"no generated block named '$name' in the page")
    page.substring(0, from + open.length) + content + page.substring(to)
