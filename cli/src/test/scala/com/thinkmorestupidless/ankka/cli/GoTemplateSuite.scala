package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/**
 * `ankka init --language go`: see `PolyglotTemplateSuite`. Needs `go` on PATH, and Docker for the
 * project's slow test, which this suite runs.
 */
class GoTemplateSuite extends PolyglotTemplateSuite(Language.Go, "go"):

  test("the project's own vet and tests, the slow one included, pass against this commit's SDK") {
    val sdk    = repoRoot.resolve("sdks/go")
    val goMod  = project.resolve("go.mod")
    val pinned = Files.readString(goMod)
    val pin    = s"require github.com/thinkmorestupidless/ankka-go v$Version"
    assert(pinned.contains(pin), pinned)
    // A development build's version carries a `+`, which Go refuses as a module version, so the
    // requirement is the SDK's own placeholder and the replace points it at this commit's SDK.
    Files.writeString(
      goMod,
      pinned.replace(pin, "require github.com/thinkmorestupidless/ankka-go v0.0.0") +
        s"\nreplace github.com/thinkmorestupidless/ankka-go => $sdk\n"
    ): Unit

    def go(args: String*): (Int, String) =
      val builder = new ProcessBuilder(("go" +: args)*)
        .directory(project.toFile)
        .redirectErrorStream(true)
      // The project is not in the SDK's workspace; ANKKA_SLOW runs the integration test.
      builder.environment().put("GOWORK", "off")
      builder.environment().put("ANKKA_SLOW", "1")
      val process = builder.start()
      val text    = new String(process.getInputStream.readAllBytes())
      print(text)
      (process.waitFor(), text)

    assertEquals(go("mod", "tidy")._1, 0, "go mod tidy")
    assertEquals(go("vet", "./...")._1, 0, "go vet")
    val (code, output) = go("test", "-count=1", "-v", "./...")
    assertEquals(code, 0, "go test")
    assert(!output.contains("--- SKIP"), "a test was skipped, not run")
    assert(output.contains("--- PASS: TestAnItemSurvivesARestartAndIsListed"), output)
    assert(output.linesIterator.exists(_.startsWith("ok")), output)
  }
