package com.thinkmorestupidless.ankka.cli.local

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * What runs a service from its source (feature 066, research R12): its language, found by the rules
 * of the template's `scripts/service-language.sh`, and the default command for it, with the
 * directory it runs in.
 */
object SourceCommands:

  enum Language:
    case Python, Web, TypeScript, Rust, Go, Java, Kotlin, Scala

  /** A command as words and the directory it runs in. */
  final case class RunCommand(words: Vector[String], directory: Path)

  private def holds(dir: Path, file: String): Boolean = Files.isRegularFile(dir.resolve(file))

  private def packageNames(dir: Path, word: String): Boolean =
    holds(dir, "package.json") &&
      Files.readString(dir.resolve("package.json"), UTF_8).contains(s"\"$word\"")

  /** The language of the service at `serviceDir`, or none the rules can tell. */
  def language(serviceDir: Path): Option[Language] =
    if holds(serviceDir, "pyproject.toml") then Some(Language.Python)
    else if packageNames(serviceDir, "vite") then Some(Language.Web)
    else if packageNames(serviceDir, "ankka") then Some(Language.TypeScript)
    else if holds(serviceDir, "Cargo.toml") then Some(Language.Rust)
    else if holds(serviceDir, "go.mod") then Some(Language.Go)
    else if holds(serviceDir, "pom.xml") then Some(Language.Java)
    else if holds(serviceDir, "build.gradle.kts") then Some(Language.Kotlin)
    else if Files.isDirectory(serviceDir.resolve("src/main/scala")) then Some(Language.Scala)
    else None

  private val ProjectName = """(?m)^name\s*=\s*"([^"]+)"""".r

  /** The language's default command for `service` of the project at `root`. */
  def default(root: Path, service: String, language: Language): Option[RunCommand] =
    val dir = root.resolve("services").resolve(service)
    language match
      case Language.Scala      => Some(RunCommand(Vector("sbt", s"$service/run"), root))
      case Language.TypeScript => Some(RunCommand(Vector("npm", "start"), dir))
      case Language.Web        => Some(RunCommand(Vector("npm", "run", "dev"), dir))
      case Language.Go         => Some(RunCommand(Vector("go", "run", "."), dir))
      case Language.Java       => Some(RunCommand(Vector("./mvnw", "-q", "exec:java"), dir))
      case Language.Kotlin     =>
        Some(RunCommand(Vector("./gradlew", "--no-daemon", "-q", "run"), dir))
      // The module is built; the runtime that loads it is a container the command starts.
      case Language.Rust   => Some(RunCommand(Vector("cargo", "module"), dir))
      case Language.Python =>
        ProjectName
          .findFirstMatchIn(Files.readString(dir.resolve("pyproject.toml"), UTF_8))
          .map(m =>
            RunCommand(
              Vector("uv", "run", "python", "-m", s"${m.group(1).replace('-', '_')}.main"),
              dir
            )
          )
