package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/** `ankka init --language python`: see `PolyglotTemplateSuite`. Needs `uv` on PATH. */
class PythonTemplateSuite extends PolyglotTemplateSuite(Language.Python, "uv"):

  test("the package is named after the service, '-' as '_'") {
    assert(Files.exists(project.resolve("src/probe_service/main.py")))
    assert(Files.readString(project.resolve("Dockerfile")).contains("probe_service.main"))
  }

  test("the project's own type check and tests pass against this commit's SDK") {
    val sdk       = repoRoot.resolve("sdks/python")
    val pyproject = project.resolve("pyproject.toml")
    val pinned    = Files.readString(pyproject)
    assert(pinned.contains(s"\"ankka==$Version\""), pinned)
    assert(pinned.contains(s"\"ankka[testkit]==$Version\""), pinned)
    // The generated stubs are not in git; the SDK's own `scripts/proto.py` writes them.
    if !Files.exists(sdk.resolve("src/ankka/_proto")) then
      val stubs = new ProcessBuilder("uv", "run", "python", "scripts/proto.py")
        .directory(sdk.toFile)
        .inheritIO()
      assertEquals(stubs.start().waitFor(), 0, "the SDK's stubs could not be generated")
    Files.writeString(
      pyproject,
      pinned.replace(s"==$Version", "") +
        s"\n[tool.uv.sources]\nankka = { path = \"$sdk\", editable = true }\n"
    ): Unit

    assertEquals(run("uv", "sync")._1, 0, "uv sync")
    assertEquals(run("uv", "run", "mypy")._1, 0, "mypy")
    val (code, output) = run("uv", "run", "pytest", "-q", "-rs")
    assertEquals(code, 0, "pytest")
    assert(!output.contains("skipped"), "the integration test must run, not skip")
  }
