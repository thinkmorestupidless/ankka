package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.ServiceDescriptor
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The feature's proof (006): a build outside this one resolves ankka and its own tests pass.
 *
 * `build.sbt` publishes the six artifacts locally before this suite runs (`cli / Test / test`
 * depends on `publishLocal`), then this expands the template through `ankka init` — the real
 * command, against `file://` — into a temp directory, checks nothing in it points back at this
 * repository, and runs the expansion's `sbt test` and `sbt Docker/publishLocal` as subprocesses.
 * Slow (an external sbt start plus a test-kit Postgres) and gated like the k3s suites.
 */
class TemplateSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean =
    sys.props.get("ankka.template.tests").contains("off") || !Init.sbtOnPath()

  private val Name    = "probe"
  private val Version = com.thinkmorestupidless.ankka.core.BuildInfo.version

  private lazy val repoRoot: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  private var workspace: Path = null
  private def expansion: Path = workspace.resolve(Name)

  override def beforeAll(): Unit =
    if !munitIgnore then
      workspace = Files.createTempDirectory("ankka-template")
      val request =
        Init.Request(Name, s"file://${repoRoot.resolve("ankka.g8")}", directory = workspace)
      assertEquals(Init.problems(request), Vector.empty)
      assertEquals(Init.run(request, Version), 0, "sbt new failed")

  private def files: Vector[Path] =
    Files.walk(expansion).iterator().asScala.filter(Files.isRegularFile(_)).toVector

  private def sbt(args: String*): Int = sbt(Map.empty, args*)

  private def sbt(env: Map[String, String], args: String*): Int =
    val builder = new ProcessBuilder((Vector("sbt", "-batch") ++ args)*)
      .directory(expansion.toFile)
      .inheritIO()
    env.foreach((key, value) => builder.environment().put(key, value): Unit)
    builder.start().waitFor()

  test("1. the expansion names the developer's project and nothing of the stub") {
    val leaks = files.flatMap { f =>
      val text = Files.readString(f)
      Vector("my-service", "MyService", "myservice").filter(text.contains).map(w => s"$f: $w")
    }
    assertEquals(leaks, Vector.empty)
    assert(Files.exists(expansion.resolve("src/main/scala/com/example/probe/Main.scala")))
  }

  test("2. the expansion references no path of this repository") {
    val leaks = files.flatMap { f =>
      val text = Files.readString(f)
      Vector("modules/runtime", repoRoot.toString, "samples/shopping-cart")
        .filter(text.contains)
        .map(w => s"$f: $w")
    }
    assertEquals(leaks, Vector.empty)
  }

  test("3. the descriptor is valid and declares this build's runtime version") {
    val descriptor =
      readFromString[ServiceDescriptor](Files.readString(expansion.resolve("service.json")))
    assertEquals(descriptor.problems, Vector.empty)
    assertEquals(descriptor.name, Name)
    assertEquals(descriptor.service.runtime, Some(Version))
    assertEquals(descriptor.service.image, s"$Name:latest")
    assert(Files.readString(expansion.resolve("build.sbt")).contains(s"\"$Version\""))
  }

  test("4. the expansion's own tests pass against the published artifacts") {
    assertEquals(sbt("test"), 0)
  }

  test("5. the expansion builds its image, tagged with its name") {
    assertEquals(sbt("Docker/publishLocal"), 0)
    val inspect = new ProcessBuilder("docker", "image", "inspect", s"$Name:latest")
      .redirectErrorStream(true)
      .start()
    assertEquals(inspect.waitFor(), 0, s"$Name:latest was not built")
  }

  /**
   * The workflows, through Giter8.
   *
   * Giant trap: Giter8 reads `$` as its own syntax, so every GitHub expression in the template is
   * written `\$` escaped and becomes `${{ … }}` on expansion. A missed escape silently deletes the
   * expression — the workflow still parses, and a secret simply arrives empty — so this asserts on
   * the *expanded* files rather than the template's own.
   */
  test("6. the workflows survive expansion with their GitHub expressions intact") {
    val ci     = expansion.resolve(".github/workflows/ci.yml")
    val deploy = expansion.resolve(".github/workflows/deploy.yml")
    assert(Files.exists(ci), s"$ci is missing")
    assert(Files.exists(deploy), s"$deploy is missing")

    Vector(ci, deploy).foreach { file =>
      val text = Files.readString(file)
      assert(!text.contains("\\${{"), s"$file kept a Giter8 escape: an expression will be empty")
      assert(!text.contains("\\$"), s"$file kept a Giter8 escape")
      // Every `${{` closes, so nothing was half-eaten.
      assertEquals(
        text.sliding(3).count(_ == "${{"),
        text.sliding(2).count(_ == "}}"),
        s"unbalanced expressions in $file"
      )
    }

    val deployText = Files.readString(deploy)
    assert(deployText.contains("secrets.ANKKA_TOKEN"), deployText)
    assert(deployText.contains("thinkmorestupidless/ankka-action@"), deployText)
    // The service's own name reached the deploy step: that is Giter8's `$name$`, the one `$` that
    // is deliberately not escaped.
    assert(deployText.contains(s"ankka services deploy $Name"), deployText)
    assert(!deployText.contains("name;format"), "a Giter8 directive survived expansion")

    val ciText = Files.readString(ci)
    assert(ciText.contains("sbt test"), ciText)
    assert(!ciText.contains("secrets."), "the ci workflow must need no secrets")
  }

  /**
   * The build's registry and version hooks, which the deploy workflow sets from the environment.
   */
  test("7. the build tags for a registry when the environment names one") {
    val version = "1.2.3"
    val repo    = "registry.example.test/acme"
    assertEquals(
      sbt(Map("DOCKER_REPOSITORY" -> repo, "SERVICE_VERSION" -> version), "Docker/publishLocal"),
      0
    )
    val inspect = new ProcessBuilder("docker", "image", "inspect", s"$repo/$Name:$version")
      .redirectErrorStream(true)
      .start()
    val output = new String(inspect.getInputStream.readAllBytes())
    assertEquals(inspect.waitFor(), 0, s"$repo/$Name:$version was not built:\n$output")
  }
