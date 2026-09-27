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
 * The last cases run the action's own install script: against a tarball of the native build this
 * machine produced, served over `file://`, and as a runner with no native build. That is as close
 * as this repository can get to proving the action works without a GitHub runner: the platform
 * choice, the script, the checksum check, the unpacking and `ankka version` are all the real ones.
 * What it cannot cover is GitHub's own contract — `$GITHUB_PATH` being read by later steps — which
 * is the manual tier in the feature's quickstart.
 */
class ActionSuite extends munit.FunSuite:

  override val munitTimeout = 5.minutes

  private lazy val actionYaml =
    Files.readString(CliReferenceSuite.repoRoot.resolve("action/action.yml"))

  private def hasTools: Boolean =
    Seq("curl", "tar", "shasum").forall(tool =>
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
    assert(actionYaml.contains("ankka-cli-$ANKKA_VERSION-$platform.tar.gz"), actionYaml)
    assert(actionYaml.contains("$archive.sha256"), "the checksum published beside the tarball")
    assert(actionYaml.contains("shasum -a 256 --check"), actionYaml)
  }

  test("it installs the native build for each platform the release carries, and needs no Java") {
    Seq(
      "Linux/X64)   platform=linux-x64",
      "Linux/ARM64) platform=linux-arm64",
      "macOS/X64)   platform=macos-x64",
      "macOS/ARM64) platform=macos-arm64"
    ).foreach(line => assert(actionYaml.contains(line), s"missing: $line"))
    assert(!actionYaml.toLowerCase.contains("java"), "the native build needs no JVM")
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
   * copy of it. Gated on `curl`, `tar` and `shasum` being present, and on a native build existing,
   * as `TemplateSuite` is gated on `sbt`.
   */
  test("the install step fetches, verifies and unpacks this machine's native build") {
    assume(hasTools, "needs curl, tar and shasum on PATH")
    val binary = CliReferenceSuite.repoRoot.resolve("cli/target/graalvm-native-image/ankka")
    assume(
      Files.isExecutable(binary),
      s"needs $binary — run `sbt cli/GraalVMNativeImage/packageBin` first"
    )
    val (runnerOs, runnerArch, platform) = thisPlatform
    // Any version will do: the tarball is named for it here and served from a local directory.
    val version = "9.9.9"
    val archive = s"ankka-cli-$version-$platform.tar.gz"

    val work = Files.createTempDirectory("ankka-action")
    try
      val staging = Files.createDirectory(work.resolve("staging"))
      Files.copy(binary, staging.resolve("ankka")): Unit
      run(staging, "tar", "-czf", work.resolve(archive).toString, "ankka"): Unit
      // The checksum the release job writes: computed from inside the directory, so the line names
      // the bare file and `--check` can find it wherever it is run.
      Files.writeString(
        work.resolve(s"$archive.sha256"),
        run(work, "shasum", "-a", "256", archive)
      ): Unit

      val (status, output, path) = install(work, version, runnerOs, runnerArch)
      assertEquals(status, 0, s"the install step failed:\n$output")

      // It appended a directory to GITHUB_PATH, and the ankka in it runs.
      val appended = Files.readAllLines(path).asScala.filter(_.nonEmpty)
      assertEquals(appended.size, 1, appended.toString)
      val bin = Path.of(appended.head)
      assert(Files.isExecutable(bin.resolve("ankka")), s"$bin/ankka is not executable")
      // `ankka version` prints the version compiled into BuildInfo, not the tarball's name — so this
      // asserts it runs and says something, not that the two agree.
      val reported = run(bin, "./ankka", "version")
      assert(reported.trim.nonEmpty, "`ankka version` printed nothing")
    finally deleteRecursively(work)
  }

  test("a runner with no native build is refused before anything is downloaded, naming it") {
    val work = Files.createTempDirectory("ankka-action")
    try
      val (status, output, _) = install(work, "9.9.9", "Windows", "X64")
      assertNotEquals(status, 0, output)
      assert(output.contains("no build for Windows/X64"), output)
      assert(!output.contains("could not be fetched"), "it tried a download first:\n" + output)
    finally deleteRecursively(work)
  }

  /** Runs the install step as a runner would, from `work`, serving the release from `work` too. */
  private def install(
      work: Path,
      version: String,
      runnerOs: String,
      runnerArch: String
  ): (Int, String, Path) =
    val runner  = Files.createTempDirectory("ankka-runner")
    val path    = Files.createTempFile("github-path", ".txt")
    val scriptF = work.resolve("install.sh")
    Files.writeString(scriptF, installScript): Unit
    val process = new ProcessBuilder("bash", scriptF.toString)
      .directory(work.toFile)
      .redirectErrorStream(true)
    process.environment().put("ANKKA_VERSION", version)
    process.environment().put("ANKKA_CLI_BASE_URL", s"file://${work.toAbsolutePath}")
    process.environment().put("RUNNER_OS", runnerOs)
    process.environment().put("RUNNER_ARCH", runnerArch)
    process.environment().put("RUNNER_TEMP", runner.toString)
    process.environment().put("GITHUB_PATH", path.toString)
    val started = process.start()
    val output  = new String(started.getInputStream.readAllBytes())
    (started.waitFor(), output, path)

  /** This machine as GitHub would name it, and the release's name for its native build. */
  private def thisPlatform: (String, String, String) =
    val os = sys.props("os.name").toLowerCase match
      case n if n.contains("mac")   => "macOS"
      case n if n.contains("linux") => "Linux"
      case n                        => fail(s"no native build for $n")
    val arch = sys.props("os.arch") match
      case "aarch64" | "arm64" => "ARM64"
      case "amd64" | "x86_64"  => "X64"
      case other               => fail(s"no native build for $other")
    val platform =
      (if os == "macOS" then "macos" else "linux") + (if arch == "ARM64" then "-arm64" else "-x64")
    (os, arch, platform)

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
