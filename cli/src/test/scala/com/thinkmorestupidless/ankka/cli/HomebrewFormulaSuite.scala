package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/**
 * The Homebrew formula in `homebrew/Formula/ankka.rb` is generated at release: the `homebrew` job
 * of the release workflow rewrites the version `0.0.0` and one checksum of sixty-four zeros per
 * platform — found by the platform named in the comment on its line — with `sed`, from the
 * `.sha256` each native build published beside its tarball, then pushes `homebrew/` to the tap
 * repository. A formula whose placeholders have drifted is one that job rewrites into nonsense, and
 * nothing before the tag would notice; this pins the shape the job relies on.
 */
class HomebrewFormulaSuite extends munit.FunSuite:

  private lazy val formula =
    Files.readString(CliReferenceSuite.repoRoot.resolve("homebrew/Formula/ankka.rb"))

  private val platforms = Seq("macos-arm64", "macos-x64", "linux-arm64", "linux-x64")

  test("the version is the one placeholder the release job replaces") {
    assert(formula.contains("  version \"0.0.0\""), formula)
    assertEquals(
      formula.linesIterator.count(_.contains("0.0.0")),
      1,
      "0.0.0 appears on the version line only; the urls interpolate it"
    )
  }

  test("each platform downloads the native build the release attaches, under the version") {
    platforms.foreach { platform =>
      val url =
        "url \"https://github.com/thinkmorestupidless/ankka/releases/download/v#{version}/" +
          s"ankka-cli-#{version}-$platform.tar.gz\""
      assertEquals(formula.linesIterator.count(_.trim == url), 1, s"no single url for $platform")
    }
  }

  test(
    "each checksum is a placeholder on a line naming its platform, as the job's sed matches it"
  ) {
    val placeholder = "sha256 \"" + "0" * 64 + "\""
    platforms.foreach { platform =>
      assertEquals(
        formula.linesIterator.count(_.trim == s"$placeholder # $platform"),
        1,
        s"no single checksum placeholder for $platform"
      )
    }
    assertEquals(formula.linesIterator.count(_.contains("0" * 64)), platforms.size, formula)
  }

  test("the url and checksum for a platform sit in the block that selects it") {
    val lines = formula.linesIterator.map(_.trim).toVector
    def block(os: String, arch: String): Vector[String] =
      val osAt   = lines.indexOf(s"on_$os do")
      val archAt = lines.indexWhere(_ == s"on_$arch do", osAt)
      lines.slice(archAt, lines.indexWhere(_ == "end", archAt))
    Seq(
      ("macos", "arm", "macos-arm64"),
      ("macos", "intel", "macos-x64"),
      ("linux", "arm", "linux-arm64"),
      ("linux", "intel", "linux-x64")
    ).foreach { case (os, arch, platform) =>
      val inside = block(os, arch)
      assert(inside.exists(_.endsWith(s"-$platform.tar.gz\"")), s"on_$os/on_$arch: $inside")
      assert(inside.exists(_.endsWith(s"# $platform")), s"on_$os/on_$arch: $inside")
    }
  }

  test("the formula installs the executable alone, needs no JDK, and checks its version") {
    assert(formula.contains("bin.install \"ankka\""), formula)
    assert(!formula.contains("openjdk"), "the native build needs no JVM")
    assert(formula.contains("ankka version"), formula)
  }
