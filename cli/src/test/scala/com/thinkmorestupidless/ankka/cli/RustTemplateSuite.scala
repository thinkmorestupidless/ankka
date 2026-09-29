package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/**
 * `ankka init --language rust`: see `PolyglotTemplateSuite`. Needs `cargo` on PATH, with the
 * `wasm32-unknown-unknown` target, and Docker for the project's slow tests.
 */
class RustTemplateSuite extends PolyglotTemplateSuite(Language.Rust, "cargo"):

  test("the project's own lints, module build and tests pass against this commit's crate") {
    val crate     = repoRoot.resolve("sdks/rust/ankka")
    val cargoToml = project.resolve("Cargo.toml")
    val pinned    = Files.readString(cargoToml)
    assert(pinned.contains(s"version = \"$Version\""), pinned)
    // Both the dependency and the dev-dependency pin the version; both point at this commit's crate.
    Files.writeString(
      cargoToml,
      pinned.replace(s"version = \"$Version\"", s"path = \"$crate\"")
    ): Unit

    assertEquals(
      run("cargo", "clippy", "--all-targets", "--features", "slow", "--", "-D", "warnings")._1,
      0,
      "clippy"
    )
    assertEquals(run("cargo", "module")._1, 0, "cargo module (the release build for wasm32)")
    val module = project.resolve("target/wasm32-unknown-unknown/release/probe_service.wasm")
    assert(Files.isRegularFile(module), s"no module at $module")
    val (code, output) = run("cargo", "test", "--features", "slow")
    assertEquals(code, 0, "cargo test --features slow")
    val results = output.linesIterator.filter(_.startsWith("test result:")).toVector
    assert(results.nonEmpty, output)
    assert(results.forall(_.contains(" 0 ignored")), s"a test was ignored, not run: $results")
  }
