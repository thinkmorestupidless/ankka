package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.SourceCommands
import com.thinkmorestupidless.ankka.cli.local.SourceCommands.{Language, RunCommand}

import java.nio.file.{Files, Path}

/** Each language's service directory, and the command that runs it from its source. */
class SourceCommandsSuite extends munit.FunSuite:

  private def service(files: (String, String)*): (Path, Path) =
    val root = Files.createTempDirectory("source")
    val dir  = Files.createDirectories(root.resolve("services/orders"))
    files.foreach { (path, text) =>
      val file = dir.resolve(path)
      Files.createDirectories(file.getParent)
      Files.writeString(file, text)
    }
    (root, dir)

  private val cases
      : Vector[(String, Seq[(String, String)], Language, Path => Path, Vector[String])] = Vector(
    (
      "python",
      Seq("pyproject.toml" -> "[project]\nname = \"shop-orders\"\n"),
      Language.Python,
      _.resolve("services/orders"),
      Vector("uv", "run", "python", "-m", "shop_orders.main")
    ),
    (
      "web",
      Seq("package.json" -> """{"devDependencies":{"vite":"6"}}"""),
      Language.Web,
      _.resolve("services/orders"),
      Vector("npm", "run", "dev")
    ),
    (
      "typescript",
      Seq("package.json" -> """{"dependencies":{"ankka":"0.16.3"}}"""),
      Language.TypeScript,
      _.resolve("services/orders"),
      Vector("npm", "start")
    ),
    (
      "rust",
      Seq("Cargo.toml" -> "[package]"),
      Language.Rust,
      _.resolve("services/orders"),
      Vector("cargo", "module")
    ),
    (
      "go",
      Seq("go.mod" -> "module shop"),
      Language.Go,
      _.resolve("services/orders"),
      Vector("go", "run", ".")
    ),
    (
      "java",
      Seq("pom.xml" -> "<project/>"),
      Language.Java,
      _.resolve("services/orders"),
      Vector("./mvnw", "-q", "exec:java")
    ),
    (
      "kotlin",
      Seq("build.gradle.kts" -> ""),
      Language.Kotlin,
      _.resolve("services/orders"),
      Vector("./gradlew", "--no-daemon", "-q", "run")
    ),
    (
      "scala",
      Seq("src/main/scala/Main.scala" -> ""),
      Language.Scala,
      identity,
      Vector("sbt", "orders/run")
    )
  )

  cases.foreach { (name, files, expected, directory, words) =>
    test(s"a $name service is told by its files and run by its language's command") {
      val (root, dir) = service(files*)
      assertEquals(SourceCommands.language(dir), Some(expected))
      assertEquals(
        SourceCommands.default(root, "orders", expected),
        Some(RunCommand(words, directory(root)))
      )
    }
  }

  test("a directory the rules cannot tell has no language") {
    val (_, dir) = service("README.md" -> "")
    assertEquals(SourceCommands.language(dir), None)
  }
