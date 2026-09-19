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

  private def sbt(args: String*): Int =
    new ProcessBuilder((Vector("sbt", "-batch") ++ args)*)
      .directory(expansion.toFile)
      .inheritIO()
      .start()
      .waitFor()

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
