package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{Protocol, ServiceDescriptor}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A Python or TypeScript template's proof: `ankka init --language` — the real command, through
 * `Main.run` — renders the template into a temp directory, and the project's own checks pass there.
 *
 * The project pins the SDK at this CLI's version, which for a build of this repository is a
 * snapshot no registry has. So the suite first asserts the pin is there, then points the dependency
 * at the SDK in this repository, the way `TemplateSuite` publishes the Scala artifacts locally: the
 * project is tested against the SDK of the same commit. That SDK is unreleased (0.0.0), so its
 * testkit starts `ankka-sidecar:latest`, the sidecar of the same commit — build it first with
 * `sbt sidecar/Docker/publishLocal`, as the SDK jobs in CI do. Nothing in the project skips, and
 * the suite checks nothing did.
 */
abstract class PolyglotTemplateSuite(language: Language, tools: String*) extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 10.minutes

  override def munitIgnore: Boolean =
    !TemplateSwitch.enabled(language.id) || !tools.forall(TemplateSwitch.onPath)

  protected val Name    = "probe-service"
  protected val Version = com.thinkmorestupidless.ankka.core.BuildInfo.version

  protected lazy val repoRoot: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  private var workspace: Path = null
  protected def project: Path = workspace.resolve(Name)

  override def beforeAll(): Unit =
    if !munitIgnore then
      workspace = Files.createTempDirectory(s"ankka-${language.id}-template")
      val out  = ByteArrayOutputStream()
      val err  = ByteArrayOutputStream()
      val args = Array("init", Name, "--language", language.id, "--dir", workspace.toString)
      val code = Main.run(args, PrintStream(out), PrintStream(err))
      assertEquals(code, 0, s"ankka init failed:\n$out\n$err")

  protected def files: Vector[Path] =
    Files.walk(project).iterator().asScala.filter(Files.isRegularFile(_)).toVector

  /** Runs a command in the project, its output passed through, and returns the output too. */
  protected def run(command: String*): (Int, String) =
    val process =
      new ProcessBuilder(command*).directory(project.toFile).redirectErrorStream(true).start()
    val text = new String(process.getInputStream.readAllBytes())
    print(text)
    (process.waitFor(), text)

  test("every token is rendered, and GitHub's own expressions are untouched") {
    val leftovers = files.flatMap { f =>
      val text = Files.readString(f).replace("${{", "")
      Vector("{{name}}", "{{module}}", "{{ankka_version}}", "{{protocol_version}}", "{{")
        .find(text.contains)
        .map(token => s"$f: $token")
    }
    assertEquals(leftovers, Vector.empty)
    val deploy = Files.readString(project.resolve(".github/workflows/deploy.yml"))
    assert(deploy.contains("${{ secrets.ANKKA_TOKEN }}"), deploy)
    assert(deploy.contains(s"ankka services deploy $Name"), deploy)
  }

  test("the project carries what a service needs beyond its code") {
    Seq(
      ".gitignore",
      ".github/workflows/ci.yml",
      "docker-compose.yml",
      "Dockerfile",
      "README.md",
      ".claude/skills/ankka/SKILL.md"
    ).foreach(path => assert(Files.exists(project.resolve(path)), s"missing $path"))
  }

  test("the project references no path of this repository") {
    // The agent skills are the documentation, which may tell a reader where the SDKs live in the
    // ankka repository; the project's own files must not depend on it.
    val skills = project.resolve(".claude")
    val leaks = files.flatMap { f =>
      val text = Files.readString(f)
      val paths =
        if f.startsWith(skills) then Vector() else Vector("sdks/python", "sdks/typescript")
      (repoRoot.toString +: paths).filter(text.contains).map(w => s"$f: $w")
    }
    assertEquals(leaks, Vector.empty)
  }

  test("the descriptor is valid, hosted as a process speaking this platform's protocol") {
    val descriptor =
      readFromString[ServiceDescriptor](Files.readString(project.resolve("service.json")))
    assertEquals(descriptor.problems, Vector.empty)
    assertEquals(descriptor.name, Name)
    assert(descriptor.service.isProcessHosted, descriptor.service.toString)
    assertEquals(descriptor.service.protocol, Some(Protocol.version.toString))
  }
