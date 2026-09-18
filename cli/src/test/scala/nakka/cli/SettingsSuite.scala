package nakka.cli

import java.nio.file.{Files, Path}

/**
 * Settings precedence and the config file.
 *
 * Every test points `nakka.config` at a scratch file, so nothing here can read or write the
 * developer's own `~/.nakka/config.json`.
 */
class SettingsSuite extends munit.FunSuite:

  private val scratch = FunFixture[Path](
    setup = _ =>
      val file = Files.createTempFile("nakka-cli", ".json")
      Files.delete(file)
      sys.props("nakka.config") = file.toString
      file
    ,
    teardown = file =>
      sys.props.remove("nakka.config"): Unit
      Files.deleteIfExists(file): Unit
  )

  scratch.test("a missing config file reads as defaults, not a failure") { _ =>
    val loaded = Settings.load()
    assertEquals(loaded.url, Settings.DefaultUrl)
    assertEquals(loaded.token, None)
    assertEquals(loaded.project, None)
  }

  scratch.test("settings round-trip through the file") { _ =>
    val _ = Settings.save(
      Settings("http://cp:8080", Some("secret"), Some("checkout"), Some("~/.nakka/local-ca.crt"))
    )
    val loaded = Settings.load()
    assertEquals(loaded.url, "http://cp:8080")
    assertEquals(loaded.token, Some("secret"))
    assertEquals(loaded.project, Some("checkout"))
    // The path as the user gave it: their shell resolved it, or will.
    assertEquals(loaded.ca, Some("~/.nakka/local-ca.crt"))
  }

  scratch.test("a settings file from before there was a ca reads with none") { file =>
    Files.writeString(file, """{"url":"http://cp:8080"}""")
    assertEquals(Settings.load().ca, None)
  }

  scratch.test("save creates the parent directory") { file =>
    val nested = file.resolveSibling("nakka-cli-nested").resolve("deeper").resolve("config.json")
    sys.props("nakka.config") = nested.toString
    try
      val written = Settings.save(Settings(project = Some("p")))
      assert(Files.exists(written), s"$written was not created")
      assertEquals(Settings.load().project, Some("p"))
    finally
      Files.deleteIfExists(nested): Unit
      Files.deleteIfExists(nested.getParent): Unit
      Files.deleteIfExists(nested.getParent.getParent): Unit
  }

  scratch.test("a corrupt config file reads as defaults rather than blocking the CLI") { file =>
    Files.writeString(file, "{ this is not json"): Unit
    // A CLI that refuses to start because its own config is broken cannot be used to fix
    // anything — including the config.
    assertEquals(Settings.load(), Settings())
  }

  scratch.test("explicit flags beat the saved file") { _ =>
    val _ = Settings.save(Settings("http://saved", Some("saved-token"), Some("saved-project")))
    val resolved = Settings.resolve(
      urlFlag = Some("http://flag"),
      tokenFlag = Some("flag-token"),
      projectFlag = Some("flag-project")
    )
    assertEquals(resolved.url, "http://flag")
    assertEquals(resolved.token, Some("flag-token"))
    assertEquals(resolved.project, Some("flag-project"))
  }

  scratch.test("the saved file supplies whatever the flags leave out") { _ =>
    // The environment sits between the flags and the file, so a developer with NAKKA_URL
    // exported would legitimately see a different answer here.
    assume(
      Seq("NAKKA_URL", "NAKKA_TOKEN", "NAKKA_PROJECT").forall(!sys.env.contains(_)),
      "NAKKA_* is set in this environment"
    )
    val _ = Settings.save(Settings("http://saved", Some("saved-token"), Some("saved-project")))
    val resolved = Settings.resolve(None, None, None)
    assertEquals(resolved.url, "http://saved")
    assertEquals(resolved.token, Some("saved-token"))
    assertEquals(resolved.project, Some("saved-project"))
  }
