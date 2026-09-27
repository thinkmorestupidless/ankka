package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/**
 * `ankka init --language typescript`: see `PolyglotTemplateSuite`. Needs `node` and `npm` on PATH.
 */
class TypeScriptTemplateSuite extends PolyglotTemplateSuite(Language.TypeScript, "node", "npm"):

  test("the project's own type check and tests pass against this commit's SDK") {
    val sdk         = repoRoot.resolve("sdks/typescript")
    val packageJson = project.resolve("package.json")
    val pinned      = Files.readString(packageJson)
    assert(pinned.contains(s"\"ankka\": \"$Version\""), pinned)
    // A `file:` dependency resolves through the SDK's package exports, which name `dist/`.
    if !Files.exists(sdk.resolve("dist/index.js")) then
      val steps =
        (if Files.exists(sdk.resolve("node_modules")) then Vector()
         else Vector(Vector("npm", "ci"))) ++
          Vector(Vector("npm", "run", "proto"), Vector("npm", "run", "build"))
      steps.foreach { step =>
        val built = new ProcessBuilder(step*).directory(sdk.toFile).inheritIO().start().waitFor()
        assertEquals(built, 0, s"the SDK could not be built: ${step.mkString(" ")}")
      }
    Files.writeString(
      packageJson,
      pinned.replace(s"\"ankka\": \"$Version\"", s"\"ankka\": \"file:$sdk\"")
    ): Unit

    assertEquals(run("npm", "install", "--no-audit", "--no-fund")._1, 0, "npm install")
    assertEquals(run("npm", "run", "typecheck")._1, 0, "typecheck")
    val (code, output) = run("npm", "test")
    assertEquals(code, 0, "npm test")
    if sidecarImagePresent then
      assert(
        output.contains("ℹ skipped 0"),
        "the sidecar image is here, so the integration test must run"
      )
  }
