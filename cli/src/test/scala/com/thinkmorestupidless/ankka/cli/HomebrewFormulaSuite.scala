package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/**
 * The Homebrew formula in `homebrew/Formula/ankka.rb` is generated at release: the `cli` job of the
 * release workflow attaches the CLI's zip to the tag's GitHub release and rewrites two placeholders
 * — the version `0.0.0` and a checksum of sixty-four zeros — with `sed`, then pushes `homebrew/` to
 * the tap repository. A formula whose placeholders have drifted is one that job rewrites into
 * nonsense, and nothing before the tag would notice; this pins the shape the job relies on.
 */
class HomebrewFormulaSuite extends munit.FunSuite:

  private lazy val formula =
    Files.readString(CliReferenceSuite.repoRoot.resolve("homebrew/Formula/ankka.rb"))

  test("the formula downloads the zip the release job attaches, under the placeholder version") {
    assert(
      formula.contains(
        "url \"https://github.com/thinkmorestupidless/ankka/releases/download/v0.0.0/ankka-cli-0.0.0.zip\""
      ),
      formula
    )
    // `ankka-cli` is the module's artifact name, so `cli/Universal/packageBin` produces
    // `ankka-cli-<version>.zip`; the job replaces every 0.0.0 with the tag's version.
    assertEquals(
      formula.linesIterator.count(_.contains("0.0.0")),
      1,
      "0.0.0 appears on the url line only"
    )
  }

  test("the checksum is the placeholder the release job replaces") {
    assert(formula.contains("sha256 \"" + "0" * 64 + "\""), formula)
  }

  test("the formula runs the CLI on a JDK 21 and checks its version") {
    assert(formula.contains("depends_on \"openjdk@21\""), formula)
    assert(formula.contains("Language::Java.java_home_env(\"21\")"), formula)
    assert(formula.contains("ankka version"), formula)
  }
