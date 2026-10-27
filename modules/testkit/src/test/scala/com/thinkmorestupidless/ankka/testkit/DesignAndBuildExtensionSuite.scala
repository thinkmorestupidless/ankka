package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `features/design-and-build/installing.feature` and `features/design-and-build/models.feature`:
 * what the `design-build` spec-kit extension and its two skills must be, held to the files. One
 * test per scenario a file read can prove, named after it, and one for the rule that keeps the
 * command files and the skills from drifting: their bodies are the same text.
 *
 * What a coding agent does when it runs the skills is not a file; it is proven by a recorded run.
 */
class DesignAndBuildExtensionSuite extends munit.FunSuite:

  private lazy val root: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve(".specify")) do dir = dir.getParent
    dir

  private def read(path: String): String =
    val file = root.resolve(path)
    assert(Files.isRegularFile(file), s"$path does not exist")
    Files.readString(file)

  private val extensionDir  = ".specify/extensions/design-build"
  private val designSkill   = ".claude/skills/speckit-design/SKILL.md"
  private val buildSkill    = ".claude/skills/speckit-build/SKILL.md"
  private val designCommand = s"$extensionDir/commands/speckit.design.md"
  private val buildCommand  = s"$extensionDir/commands/speckit.build.md"

  /** The frontmatter's lines and the body after it. */
  private def split(text: String): (List[String], String) =
    val lines = text.linesIterator.toList
    assert(lines.headOption.contains("---"), "no frontmatter")
    val end = lines.indexWhere(_ == "---", 1)
    assert(end > 0, "the frontmatter is not closed")
    (lines.slice(1, end), lines.drop(end + 1).mkString("\n"))

  private def field(path: String, key: String): Option[String] =
    split(read(path))._1.collectFirst {
      case line if line.startsWith(s"$key:") =>
        line.stripPrefix(s"$key:").trim.stripPrefix("\"").stripSuffix("\"")
    }

  private def modelOf(path: String): String =
    field(path, "model").getOrElse(fail(s"$path has no model: line in its frontmatter"))

  /** The registered extensions, from the `installed:` list of `.specify/extensions.yml`. */
  private def installed: List[String] =
    read(".specify/extensions.yml").linesIterator
      .dropWhile(_ != "installed:")
      .drop(1)
      .takeWhile(_.startsWith("- "))
      .map(_.stripPrefix("- ").trim)
      .toList

  /** The `hooks:` block of `.specify/extensions.yml`, as text. */
  private def hooks: String =
    read(".specify/extensions.yml").linesIterator.dropWhile(_ != "hooks:").mkString("\n")

  // Claude Code's aliases, deepest-thinking first.
  private val depth = List("fable", "opus", "sonnet", "haiku")

  test("design and build copied into a repository and registered can be started there") {
    assert(installed.contains("design-build"), s"design-build is not installed: $installed")
    assertEquals(field(designSkill, "name"), Some("speckit-design"))
    assertEquals(field(buildSkill, "name"), Some("speckit-build"))
    for prerequisite <- Seq("bdd", "review") do
      assert(installed.contains(prerequisite), s"$prerequisite is not installed beside it")
  }

  test("the bdd hooks fire inside the design's specify and clarify stages") {
    val (_, body) = split(read(designSkill))
    for stage <- Seq("/speckit-specify", "/speckit-clarify") do
      assert(body.contains(stage), s"the design does not run $stage")
    assert(hooks.contains("after_specify:") && hooks.contains("command: speckit.bdd.features"))
    assert(hooks.contains("before_clarify:") && hooks.contains("command: speckit.bdd.check"))
  }

  test("the review hook fires inside the build after the last work item") {
    val (_, body) = split(read(buildSkill))
    assert(body.contains("/speckit-implement"), "the build does not run /speckit-implement")
    assert(hooks.contains("after_implement:") && hooks.contains("command: speckit.review"))
  }

  test("design and build changes nothing it runs") {
    val manifest = read(s"$extensionDir/extension.yml")
    assert(!manifest.linesIterator.exists(_.startsWith("hooks:")), "the extension registers hooks")
    assert(!hooks.contains("extension: design-build"), "extensions.yml carries a design-build hook")
    assert(
      manifest.contains("- bdd") && manifest.contains("- review"),
      "the manifest does not require bdd and review"
    )
  }

  test("a stage can still be run alone to repair it") {
    for stage <- Seq("specify", "clarify", "plan", "tasks", "analyze", "implement", "review") do
      assertEquals(
        field(s".claude/skills/speckit-$stage/SKILL.md", "name"),
        Some(s"speckit-$stage")
      )
  }

  test("the description of design and build says what it needs and what it is for") {
    val readme = read(s"$extensionDir/README.md")
    for needed <- Seq("`bdd`", "`review`", "`pr-review`") do
      assert(readme.contains(needed), s"the README does not say $needed is needed")
    for stage <- Seq("specify", "clarify", "plan", "tasks", "analyze", "implement", "review") do
      assert(readme.contains(s"/speckit-$stage"), s"the README does not name /speckit-$stage")
    assert(read(s"$extensionDir/extension.yml").contains("Requires the bdd and review extensions"))
  }

  test("the whole design runs on the design model, clarifications included") {
    modelOf(designSkill)
    val (_, body) = split(read(designSkill))
    assert(body.contains("AskUserQuestion"), "the design does not ask through AskUserQuestion")
    assert(body.contains("Never end the turn to ask"), "the design does not forbid ending the turn")
  }

  test("the whole build runs on the build model") {
    modelOf(buildSkill)
  }

  test("as shipped, the design model thinks deeper than the build model") {
    val design = modelOf(designSkill)
    val build  = modelOf(buildSkill)
    assert(depth.contains(design) && depth.contains(build), s"unknown alias: $design, $build")
    assert(
      depth.indexOf(design) < depth.indexOf(build),
      s"the design model ($design) does not think deeper than the build model ($build)"
    )
  }

  test("a model line edited in one definition changes that one's model and not the other's") {
    for skill <- Seq(designSkill, buildSkill) do
      val lines = split(read(skill))._1.filter(_.startsWith("model:"))
      assertEquals(lines.size, 1, s"$skill has ${lines.size} model lines")
  }

  test("the model lines are the only record of the models, and the reason is beside them") {
    val elsewhere =
      Seq(
        root.resolve(extensionDir),
        root.resolve(".claude/skills/speckit-design"),
        root.resolve(".claude/skills/speckit-build")
      )
        .flatMap(dir => Files.walk(dir).iterator.asScala.filter(Files.isRegularFile(_)))
        .filterNot(file => Set(designSkill, buildSkill).map(root.resolve).contains(file))
        .filter(file => Files.readString(file).linesIterator.exists(_.trim.startsWith("model:")))
    assert(
      elsewhere.isEmpty,
      s"a model is named outside the two skills: ${elsewhere.mkString(", ")}"
    )
    val readme = read(s"$extensionDir/README.md")
    assert(
      readme.contains(designSkill) && readme.contains(buildSkill),
      "the README does not say where the lines are"
    )
    assert(readme.contains("deeper-thinking"), "the README does not say why the halves differ")
  }

  test(
    "a coding agent started on another model runs the design on the design model and returns after"
  ) {
    // Claude Code applies a skill's `model:` for the turn it is invoked in, and reads it only from
    // the frontmatter; a line in the body would be prose.
    for skill <- Seq(designSkill, buildSkill) do
      assert(
        split(read(skill))._1.exists(_.startsWith("model:")),
        s"$skill's model is not in its frontmatter"
      )
  }

  test("each command file's body is its skill's body") {
    for (command, skill) <- Seq(designCommand -> designSkill, buildCommand -> buildSkill) do
      assertEquals(split(read(command))._2, split(read(skill))._2, s"$command and $skill differ")
  }
