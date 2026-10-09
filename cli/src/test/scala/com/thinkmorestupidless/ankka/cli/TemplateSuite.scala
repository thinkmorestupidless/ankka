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
 * `build.sbt` publishes the eight service libraries locally before this suite runs (`cli / Test /
 * test` depends on `publishLocal`), then this expands the template through `ankka init` — the real
 * command, against `file://` — into a temp directory, checks nothing in it points back at this
 * repository, and runs the expansion's `sbt test` and `sbt Docker/publishLocal` as subprocesses.
 * Slow (an external sbt start plus a test-kit Postgres) and gated like the k3s suites.
 */
class TemplateSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 15.minutes

  private def missingTools: Seq[String] = if Init.sbtOnPath() then Nil else Seq("sbt")

  override def munitIgnore: Boolean = TemplateSwitch.skip("scala", missingTools)

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
      TemplateSwitch.requireTools("scala", missingTools)
      workspace = Files.createTempDirectory("ankka-template")
      val request =
        Init.Request(Name, Some(s"file://${repoRoot.resolve("ankka.g8")}"), directory = workspace)
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

  test("a project made from a template states its service's name") {
    val conf = Files.readString(expansion.resolve("src/main/resources/application.conf"))
    assert(conf.linesIterator.contains(s"""ankka.service.name = "$Name""""), conf)
    assert(!conf.contains("$"), conf)
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

  test("the compose file runs the keyring of this version beside Postgres, its variables intact") {
    val compose = Files.readString(expansion.resolve("docker-compose.yml"))
    assert(
      compose.contains(
        s"$${ANKKA_KEYRING_IMAGE:-ghcr.io/thinkmorestupidless/ankka-keyring:$Version}"
      ),
      compose
    )
    assert(compose.contains("ANKKA_HTTP_PORT: \"9020\""), compose)
    assert(compose.contains("${ANKKA_KEYRING_SECRET_KEY:-"), compose)
    assert(!compose.contains("\\$"), "an escape survived expansion")
    val readme = Files.readString(expansion.resolve("README.md"))
    assert(readme.contains("ANKKA_KEYRING_URL=http://localhost:9020 sbt run"), readme)
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

  test("8. the expansion's .mcp.json starts ankka mcp, as `ankka mcp install` would write it") {
    val written = mcp.Json.parse(Files.readString(expansion.resolve(".mcp.json")))
    assertEquals(
      written.map(_("mcpServers").flatMap(_("ankka"))),
      Right(Some(mcp.McpInstall.ProjectLaunch.entry))
    )
  }

  /**
   * Adding a gRPC endpoint to a new service needs only what the documentation page states. The
   * build changes are taken from the page's own code blocks, not retyped here, so a page that stops
   * saying enough fails this case; the endpoint, its definition and its test follow the page's
   * shapes. Last, because the cases before it describe the expansion as `ankka init` left it.
   */
  test("9. a gRPC endpoint added as the documentation says builds and passes its test") {
    val page    = Files.readString(repoRoot.resolve("docs/build/grpc-endpoints.md"))
    val section = page.substring(page.indexOf("### Generating the code"))
    val blocks  = section.split("```scala\n").drop(1).map(_.takeWhile(_ != '`')).take(2)
    val Array(plugins, build) = blocks: @unchecked
    assert(plugins.contains("sbt-protoc"), plugins)
    val apiProject = build.substring(0, build.indexOf("lazy val service"))
    assert(apiProject.startsWith("lazy val api"), apiProject)

    Files.writeString(
      expansion.resolve("project/plugins.sbt"),
      "\n" + plugins,
      java.nio.file.StandardOpenOption.APPEND
    )
    val buildFile = expansion.resolve("build.sbt")
    val buildText = Files.readString(buildFile)
    val root      = "lazy val root = project\n  .in(file(\".\"))"
    val httpDep   = "\"com.thinkmorestupidless\" %% \"ankka-http\"    % ankkaVersion,"
    assert(buildText.contains(root) && buildText.contains(httpDep), buildText)
    Files.writeString(
      buildFile,
      buildText
        .replace(root, root + "\n  .dependsOn(api)")
        .replace(
          httpDep,
          httpDep + "\n      \"com.thinkmorestupidless\" %% \"ankka-grpc\" % ankkaVersion,"
        ) + "\n" + apiProject
    )

    val main = files.find(_.endsWith("Main.scala")).getOrElse(fail("no Main.scala"))
    val pkg = Files
      .readString(main)
      .linesIterator
      .collectFirst { case s"package $p" => p.trim }
      .getOrElse(fail("Main.scala declares no package"))
    val proto =
      expansion.resolve(s"api/src/main/protobuf/${pkg.replace('.', '/')}/v1/greeter.proto")
    Files.createDirectories(proto.getParent)
    Files.writeString(
      proto,
      s"""syntax = "proto3";
         |package $pkg.v1;
         |service Greeter { rpc Greet (GreetRequest) returns (GreetReply); }
         |message GreetRequest { string name = 1; }
         |message GreetReply   { string text = 1; }
         |""".stripMargin
    )
    Files.writeString(
      main.getParent.resolve("api/GreeterEndpoint.scala"),
      s"""package $pkg.api
         |
         |import $pkg.v1.greeter.{GreeterGrpc, GreetReply}
         |import com.thinkmorestupidless.ankka.grpc.GrpcEndpoint
         |import com.thinkmorestupidless.ankka.http.{Acl, Callers}
         |
         |final class GreeterEndpoint extends GrpcEndpoint(GreeterGrpc.SERVICE):
         |  val acl: Acl = Acl.allowCallers(Callers.anyInProject, Callers.internet)
         |  unary(GreeterGrpc.METHOD_GREET) { request => GreetReply(s"hello, $${request.name}") }
         |""".stripMargin
    )
    val mainText     = Files.readString(main)
    val httpRegister = ".withExtension(HttpServer.of("
    assert(mainText.contains(httpRegister), mainText)
    Files.writeString(
      main,
      mainText
        .replace(
          "import com.thinkmorestupidless.ankka.http.HttpServer",
          "import com.thinkmorestupidless.ankka.grpc.GrpcServer\n" +
            "import com.thinkmorestupidless.ankka.http.HttpServer\n" +
            s"import $pkg.api.GreeterEndpoint"
        )
        .replace(
          httpRegister,
          ".withExtension(GrpcServer.of(_ => GreeterEndpoint()))\n    " + httpRegister
        )
    )
    val tests = files.find(_.endsWith("ItemHttpSuite.scala")).getOrElse(fail("no ItemHttpSuite"))
    Files.writeString(
      tests.getParent.resolve("GreeterGrpcSuite.scala"),
      s"""package $pkg
         |
         |import $pkg.api.GreeterEndpoint
         |import $pkg.application.ItemEntity
         |import $pkg.v1.greeter.{GreeterGrpc, GreetRequest}
         |import com.thinkmorestupidless.ankka.grpc.{GrpcChannels, GrpcServer}
         |import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
         |import io.grpc.ManagedChannel
         |
         |import java.util.concurrent.TimeUnit
         |import scala.concurrent.duration.DurationInt
         |
         |class GreeterGrpcSuite extends munit.FunSuite:
         |  override val munitTimeout = 3.minutes
         |  private val grpc = GrpcServer.at("127.0.0.1", 0)(_ => GreeterEndpoint())
         |  private var testKit: AnkkaTestKit   = null
         |  private var channel: ManagedChannel = null
         |
         |  override def beforeAll(): Unit =
         |    testKit = AnkkaTestKit.start(Seq(ItemEntity.descriptor), Seq(grpc))
         |    channel = GrpcChannels.plaintext(grpc.boundPort.getOrElse(fail("the gRPC server did not bind")))
         |
         |  override def afterAll(): Unit =
         |    if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
         |    if testKit != null then testKit.stop()
         |
         |  test("a greeting names who asked") {
         |    assertEquals(GreeterGrpc.blockingStub(channel).greet(GreetRequest("ankka")).text, "hello, ankka")
         |  }
         |""".stripMargin
    )
    assertEquals(sbt("test"), 0)
  }
