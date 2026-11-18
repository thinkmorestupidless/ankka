package com.thinkmorestupidless.ankka.cli.openapi

import com.thinkmorestupidless.ankka.cli.local.SourceCommands.RunCommand

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** `ankka openapi` against a scripted build (feature 067, T015). */
class OpenApiCommandSuite extends munit.FunSuite:

  private val document =
    """{"openapi":"3.1.0","info":{"title":"cart","version":"0.0.0"},"paths":{"/carts/{id}":""" +
      """{"get":{"responses":{}},"put":{"responses":{},"x-ankka-incomplete":["request body"]}}}}""" + "\n"

  /** A project with one service of `language`, made of the file that tells it. */
  private val project = FunFixture[Path](
    _ =>
      val root = Files.createTempDirectory("openapi-command")
      Files.createDirectories(root.resolve("services/cart/src/main/scala"))
      root
    ,
    root => Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  )

  /** A build that writes `document`, or fails, and remembers what it was given. */
  private final class Scripted(status: Int = 0, writes: Boolean = true)
      extends OpenApiCommand.Runner:
    @volatile var command: Option[RunCommand]      = None
    @volatile var environment: Map[String, String] = Map.empty
    def properties: Map[String, String]            =
      environment
        .getOrElse("JAVA_TOOL_OPTIONS", "")
        .split(' ')
        .collect { case p if p.startsWith("-D") => p.drop(2).split("=", 2) }
        .collect { case Array(k, v) => k -> v }
        .toMap
    def apply(c: RunCommand, env: Map[String, String], to: PrintStream): Int =
      command = Some(c)
      environment = env
      to.println("[info] compiling")
      if status != 0 then to.println("GET /carts: incomplete, lacks answer 200")
      else if writes then
        Files.writeString(Path.of(properties("ankka.document.out")), document, UTF_8): Unit
      status

  private final case class Ran(code: Int, out: String, err: String)

  private def run(
      options: OpenApiCommand.Options,
      runner: OpenApiCommand.Runner,
      env: Map[String, String] = Map.empty
  ): Ran =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code =
      OpenApiCommand.run(
        options,
        PrintStream(out, true, UTF_8),
        PrintStream(err, true, UTF_8),
        env,
        runner
      )
    Ran(code, out.toString(UTF_8), err.toString(UTF_8))

  project.test("each option reaches its property, appended to the JVM options already set") {
    root =>
      val build = Scripted()
      val ran   = run(
        OpenApiCommand.Options(
          root.resolve("services/cart"),
          strict = true,
          tags = Vector("player", "admin"),
          prefixes = Vector("/carts"),
          apiVersion = Some("2.1.0")
        ),
        build,
        Map("JAVA_TOOL_OPTIONS" -> "-Xmx1g")
      )
      assertEquals(ran.code, 0, ran.err)
      assert(build.environment("JAVA_TOOL_OPTIONS").startsWith("-Xmx1g -Dankka.document=openapi"))
      assertEquals(
        build.properties - "ankka.document.out",
        Map(
          "ankka.document"          -> "openapi",
          "ankka.service.name"      -> "cart",
          "ankka.document.strict"   -> "true",
          "ankka.document.tags"     -> "player,admin",
          "ankka.document.prefixes" -> "/carts",
          "ankka.document.version"  -> "2.1.0"
        )
      )
      assertEquals(
        build.command,
        Some(RunCommand(Vector("sbt", "cart/run"), root.toAbsolutePath.normalize))
      )
  }

  project.test("the default output path is openapi.json in the service's directory") { root =>
    val ran = run(OpenApiCommand.Options(root), Scripted())
    assertEquals(ran.code, 0, ran.err)
    assertEquals(Files.readString(root.resolve("services/cart/openapi.json")), document)
    assert(ran.err.contains("wrote services/cart/openapi.json: 2 routes, 1 incomplete"), ran.err)
    assert(ran.err.contains("[info] compiling"), "the build's output is passed through")
  }

  project.test("--out - prints the document alone; the build's output goes to the error stream") {
    root =>
      val ran = run(OpenApiCommand.Options(root, out = Some("-")), Scripted())
      assertEquals((ran.code, ran.out), (0, document))
      assert(!Files.exists(root.resolve("services/cart/openapi.json")))
  }

  project.test("a failing JVM leaves --out untouched and passes its problems through") { root =>
    val kept = root.resolve("kept.json")
    Files.writeString(kept, "kept")
    val ran = run(OpenApiCommand.Options(root, out = Some(kept.toString)), Scripted(status = 1))
    assertEquals(ran.code, 1)
    assertEquals(Files.readString(kept), "kept")
    assert(ran.err.contains("GET /carts: incomplete, lacks answer 200"), ran.err)
  }

  project.test("a build that ends well without writing a document is a failure") { root =>
    val ran = run(OpenApiCommand.Options(root), Scripted(writes = false))
    assertEquals(ran.code, 1)
    assert(ran.err.contains("ended without writing a document"), ran.err)
  }

  project.test("local.json's run is the command, in the service's own directory for Java") { root =>
    Files.delete(root.resolve("services/cart/src/main/scala"))
    Files.writeString(root.resolve("services/cart/pom.xml"), "<project/>")
    Files.writeString(root.resolve("local.json"), """{"run":{"cart":["./mvnw","exec:java"]}}""")
    val build = Scripted()
    assertEquals(run(OpenApiCommand.Options(root), build).code, 0)
    assertEquals(
      build.command,
      Some(
        RunCommand(
          Vector("./mvnw", "exec:java"),
          root.toAbsolutePath.normalize.resolve("services/cart")
        )
      )
    )
  }

  for (language, file, text) <- Seq(
      ("python", "pyproject.toml", "[project]\nname = \"cart\"\n"),
      ("typescript", "package.json", """{"dependencies":{"ankka":"1"}}"""),
      ("go", "go.mod", "module cart\n"),
      ("rust", "Cargo.toml", "[package]\nname = \"cart\"\n"),
      ("web", "package.json", """{"devDependencies":{"vite":"1"}}""")
    )
  do
    project.test(s"a $language service has no document yet, and nothing is run") { root =>
      Files.writeString(root.resolve(s"services/cart/$file"), text)
      val build = Scripted()
      val ran   = run(OpenApiCommand.Options(root.resolve("services/cart")), build)
      assertEquals(ran.code, 2)
      assertEquals(ran.err.trim, s"a $language service has no OpenAPI document yet")
      assertEquals(build.command, None)
    }

  project.test("a project of several services asks which") { root =>
    Files.createDirectories(root.resolve("services/billing/src/main/scala"))
    val ran = run(OpenApiCommand.Options(root), Scripted())
    assertEquals(ran.code, 2)
    assert(ran.err.contains("holds 2 services"), ran.err)
  }
