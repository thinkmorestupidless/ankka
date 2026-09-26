package com.thinkmorestupidless.ankka.cli

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `action/action.yml`, the GitHub Action, checked two ways.
 *
 * Its version is a `0.0.0` placeholder the release workflow's `action` job rewrites with `sed`
 * before pushing `action/` to its own repository — the same arrangement as the Homebrew formula,
 * and with the same failure mode: a placeholder that has drifted is one the job rewrites into
 * nonsense, and nothing before the tag would notice. `HomebrewFormulaSuite` pins that shape for the
 * formula; the first cases here do it for the action.
 *
 * The last case runs the action's own install script against the zip this build produces, served
 * over `file://`. That is as close as this repository can get to proving the action works without a
 * GitHub runner: the script, the checksum check, the unzip and `bin/ankka version` are all the real
 * ones. What it cannot cover is GitHub's own contract — `$GITHUB_PATH` being read by later steps —
 * which is the manual tier in the feature's quickstart.
 */
class ActionSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private lazy val actionYaml =
    Files.readString(CliReferenceSuite.repoRoot.resolve("action/action.yml"))

  private def hasTools: Boolean =
    Seq("unzip", "sha256sum").forall(tool =>
      sys.env
        .getOrElse("PATH", "")
        .split(java.io.File.pathSeparator)
        .exists(dir => Files.isExecutable(Path.of(dir).resolve(tool)))
    )

  test("the version input is the placeholder the release job rewrites") {
    assert(actionYaml.contains("default: \"0.0.0\""), actionYaml)
    assertEquals(
      actionYaml.linesIterator.count(_.contains("0.0.0")),
      1,
      "0.0.0 appears once, on the version input's default, so `sed` cannot hit anything else"
    )
  }

  test("every documented input exists, with the right ones required") {
    Seq("version", "url", "token", "project", "ca").foreach { input =>
      assert(actionYaml.contains(s"  $input:"), s"missing input '$input'")
    }
    // url and token are the two a workflow cannot sensibly omit; the rest have defaults, because a
    // composite action's inputs are otherwise unset rather than empty.
    assertEquals(actionYaml.linesIterator.count(_.trim == "required: true"), 2, actionYaml)
  }

  test("the CLI comes from the release, and its checksum is checked") {
    assert(
      actionYaml.contains(
        "https://github.com/thinkmorestupidless/ankka/releases/download/v$ANKKA_VERSION"
      ),
      actionYaml
    )
    assert(actionYaml.contains("ankka-cli-$ANKKA_VERSION.zip"), actionYaml)
    assert(actionYaml.contains("$zip.sha256"), "the checksum published beside the zip")
    assert(actionYaml.contains("sha256sum --check"), actionYaml)
  }

  test("it installs no Java, and says what to add when there is none") {
    assert(actionYaml.contains("actions/setup-java@v4"), "the failure names the fix")
    assert(actionYaml.contains("Java 21 or later"), actionYaml)
    // Nothing here may fetch a runtime: that is the caller's step, deliberately.
    assert(!actionYaml.contains("uses: actions/setup-java") || actionYaml.contains("::error::"))
  }

  test("the token is masked before it is written, and nothing outlives the job") {
    val configure = actionYaml.substring(actionYaml.indexOf("Configure the CLI for this job"))
    val maskAt    = configure.indexOf("::add-mask::")
    val writeAt   = configure.indexOf("ANKKA_TOKEN=")
    assert(maskAt > 0 && writeAt > 0, configure)
    assert(maskAt < writeAt, "the mask must come before the token is written anywhere")
    // Everything lands in RUNNER_TEMP or GITHUB_ENV, both of which go with the runner.
    assert(actionYaml.contains("$RUNNER_TEMP/ankka-cli"), actionYaml)
    assert(actionYaml.contains("$RUNNER_TEMP/ankka-ca.crt"), actionYaml)
    assert(!actionYaml.contains("$GITHUB_WORKSPACE"), "nothing is written into the checkout")
  }

  test("it verifies the credential itself rather than leaving a later step to fail") {
    assert(actionYaml.contains("ankka whoami"), actionYaml)
  }

  /**
   * The install step, run for real.
   *
   * Extracted from the YAML by its step name so the script under test is the one that ships, not a
   * copy of it. Gated on `unzip` and `sha256sum` being present, as `TemplateSuite` is gated on
   * `sbt`.
   */
  test("the install step fetches, verifies and unpacks a real zip") {
    assume(hasTools, "needs unzip and sha256sum on PATH")

    // Found rather than predicted. On a dirty tree dynver appends a timestamp, so the version
    // compiled into `BuildInfo` and the one in a zip built minutes later do not match — and an
    // `assume` on a name that can never exist is a test that silently never runs.
    val universal = CliReferenceSuite.repoRoot.resolve("cli/target/universal")
    val zip = Option(universal.toFile.listFiles()).toVector.flatten
      .filter(f => f.getName.startsWith("ankka-cli-") && f.getName.endsWith(".zip"))
      .sortBy(-_.lastModified())
      .headOption
      .map(_.toPath)
    assume(
      zip.isDefined,
      s"needs a zip in $universal — run `sbt cli/Universal/packageBin` first"
    )
    val archive = zip.get
    val version = archive.getFileName.toString
      .stripPrefix("ankka-cli-")
      .stripSuffix(".zip")

    val work = Files.createTempDirectory("ankka-action")
    try
      // The checksum the release job writes: computed from inside the directory, so the line names
      // the bare file and `--check` can find it wherever it is run.
      val checksum = run(archive.getParent, "sha256sum", archive.getFileName.toString)
      Files.writeString(work.resolve(s"ankka-cli-$version.zip.sha256"), checksum): Unit
      Files.copy(archive, work.resolve(s"ankka-cli-$version.zip")): Unit

      val script  = installScript
      val runner  = Files.createTempDirectory("ankka-runner")
      val path    = Files.createTempFile("github-path", ".txt")
      val scriptF = work.resolve("install.sh")
      Files.writeString(scriptF, script): Unit

      val process = new ProcessBuilder("bash", scriptF.toString)
        .directory(work.toFile)
        .redirectErrorStream(true)
      process.environment().put("ANKKA_VERSION", version)
      process.environment().put("ANKKA_CLI_BASE_URL", s"file://${work.toAbsolutePath}")
      process.environment().put("RUNNER_TEMP", runner.toString)
      process.environment().put("GITHUB_PATH", path.toString)
      val started = process.start()
      val output  = new String(started.getInputStream.readAllBytes())
      assertEquals(started.waitFor(), 0, s"the install step failed:\n$output")

      // It appended a bin directory to GITHUB_PATH, and that directory runs.
      val appended = Files.readAllLines(path).asScala.filter(_.nonEmpty)
      assertEquals(appended.size, 1, appended.toString)
      val bin = Path.of(appended.head)
      assert(Files.isExecutable(bin.resolve("ankka")), s"$bin/ankka is not executable")
      // `ankka version` prints the version compiled into BuildInfo, which on a dirty tree is
      // not the zip's name — so this asserts it runs and says something, not that the two agree.
      val reported = run(bin, "./ankka", "version")
      assert(reported.trim.nonEmpty, "`ankka version` printed nothing")
    finally deleteRecursively(work)
  }

  /** The `run:` body of the install step, as it ships. */
  private def installScript: String =
    val lines = actionYaml.linesIterator.toVector
    val at    = lines.indexWhere(_.contains("name: Install the ankka CLI"))
    assert(at >= 0, "the install step's name changed; this suite extracts the script by it")
    val runAt = lines.indexWhere(_.trim == "run: |", at)
    assert(runAt > at, "the install step has no `run: |` block")
    val body = lines.drop(runAt + 1).takeWhile(l => l.isBlank || l.startsWith("        "))
    body.map(l => if l.isBlank then l else l.drop(8)).mkString("\n")

  private def run(in: Path, command: String*): String =
    val process =
      new ProcessBuilder(command*).directory(in.toFile).redirectErrorStream(true).start()
    val text = new String(process.getInputStream.readAllBytes())
    assertEquals(process.waitFor(), 0, s"${command.mkString(" ")} failed:\n$text")
    text

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(Files.deleteIfExists(_): Unit)
