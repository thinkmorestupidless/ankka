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

  // T023: --check.

  /** A build that writes `text` as its document. */
  private final class Writes(text: String) extends OpenApiCommand.Runner:
    def apply(c: RunCommand, env: Map[String, String], to: PrintStream): Int =
      val out = env("JAVA_TOOL_OPTIONS").split(' ').collectFirst {
        case p if p.startsWith("-Dankka.document.out=") => p.stripPrefix("-Dankka.document.out=")
      }
      Files.writeString(Path.of(out.getOrElse(fail("no out"))), text, UTF_8)
      0

  private val withSchema =
    """{"openapi":"3.1.0","paths":{"/carts/{id}":{"get":{"responses":{}}}},""" +
      """"components":{"schemas":{"Cart":{"type":"object"}}}}""" + "\n"

  project.test("--check of a kept document that is current exits 0 and says so") { root =>
    val kept = root.resolve("openapi.json")
    Files.writeString(kept, withSchema)
    val ran = run(OpenApiCommand.Options(root, check = Some(kept)), Writes(withSchema))
    assertEquals(ran.code, 0, ran.err)
    assert(ran.err.contains(s"$kept is current"), ran.err)
  }

  project.test("a kept document that no longer matches the code is found, and nothing is written") {
    root =>
      val kept = root.resolve("openapi.json")
      Files.writeString(kept, withSchema)
      Files.setLastModifiedTime(kept, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000L))
      val added = withSchema.replace(
        """"/carts/{id}":""",
        """"/carts/{id}/items":{"post":{"responses":{}}},"/carts/{id}":"""
      )
      val ran = run(OpenApiCommand.Options(root, check = Some(kept)), Writes(added))
      assertEquals(ran.code, 1)
      assert(ran.err.contains("route added: POST /carts/{id}/items"), ran.err)
      assert(!ran.err.contains("schema"), ran.err)
      assertEquals(Files.readString(kept), withSchema)
      assertEquals(Files.getLastModifiedTime(kept).toMillis, 1_000_000_000L)
      assert(!Files.exists(root.resolve("services/cart/openapi.json")), "--check wrote a document")
  }

  project.test("--check names a schema that changed") { root =>
    val kept = root.resolve("openapi.json")
    Files.writeString(kept, withSchema)
    val changed = withSchema.replace(""""Cart":{"type":"object"}""", """"Cart":{"type":"string"}""")
    val ran     = run(OpenApiCommand.Options(root, check = Some(kept)), Writes(changed))
    assertEquals(ran.code, 1)
    assert(ran.err.contains("schema changed: Cart"), ran.err)
    assert(!ran.err.contains("route"), ran.err)
  }

  project.test("--check of a kept document that does not exist fails, naming it") { root =>
    val kept = root.resolve("missing.json")
    val ran  = run(OpenApiCommand.Options(root, check = Some(kept)), Writes(withSchema))
    assertEquals(ran.code, 1)
    assert(ran.err.contains(s"$kept does not exist"), ran.err)
    assert(!Files.exists(kept))
  }

  // T028: --local.

  private def runLocal(
      name: String,
      answers: Map[String, Either[String, String]]
  ): (Int, String, String) =
    val out  = java.io.ByteArrayOutputStream()
    val err  = java.io.ByteArrayOutputStream()
    val code = OpenApiCommand.run(
      OpenApiCommand.Options(Path.of("."), local = Some(name)),
      PrintStream(out, true, UTF_8),
      PrintStream(err, true, UTF_8),
      runner = (_, _, _) => fail("--local runs no build"),
      local = answers.get
    )
    (code, out.toString(UTF_8), err.toString(UTF_8))

  test("--local prints the document a local service gives") {
    assertEquals(runLocal("cart", Map("cart" -> Right(withSchema))), (0, withSchema, ""))
  }

  test("--local of no such local service exits 1, naming it") {
    assertEquals(runLocal("cart", Map.empty), (1, "", "no local service named cart\n"))
  }

  test("--local of a service that has no document says what it answered") {
    val (code, _, err) =
      runLocal("cart", Map("cart" -> Left("""{"error":"this service has no OpenAPI document"}""")))
    assertEquals(code, 1)
    assert(err.contains("this service has no OpenAPI document"), err)
  }

  /** A service that is a build of its own, as `ankka init <name>` makes one. */
  private val standalone = FunFixture[Path](
    _ => Files.createTempDirectory("openapi-standalone"),
    root => Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  )

  for (language, build, command) <- Seq(
      ("scala", "build.sbt", Vector("sbt", "run")),
      ("java", "pom.xml", Vector("./mvnw", "-q", "exec:java")),
      ("kotlin", "build.gradle.kts", Vector("./gradlew", "--no-daemon", "-q", "run"))
    )
  do
    standalone.test(s"a $language service that is a build of its own runs its build there") { dir =>
      if language == "scala" then Files.createDirectories(dir.resolve("src/main/scala")): Unit
      Files.writeString(dir.resolve(build), "")
      Files.writeString(dir.resolve("service.json"), """{"name":"probe-service"}""")
      val build_ = Scripted()
      val ran    = run(OpenApiCommand.Options(dir), build_)
      assertEquals(ran.code, 0, ran.err)
      assertEquals(build_.command, Some(RunCommand(command, dir.toAbsolutePath.normalize)))
      assertEquals(build_.properties.get("ankka.service.name"), Some("probe-service"))
      assert(Files.exists(dir.resolve("openapi.json")), "no openapi.json beside the service")
    }

  standalone.test("a service with no build of its own is told to run the build that holds it") {
    dir =>
      Files.createDirectories(dir.resolve("src/main/scala"))
      val build = Scripted()
      val ran   = run(OpenApiCommand.Options(dir), build)
      assertEquals(ran.code, 2)
      assert(ran.err.contains("has no build of its own"), ran.err)
      assertEquals(build.command, None)
  }

  standalone.test("a python service of its own is told it has no document yet") { dir =>
    Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"cart\"\n")
    val ran = run(OpenApiCommand.Options(dir), Scripted())
    assertEquals(ran.code, 2)
    assertEquals(ran.err.trim, "a python service has no OpenAPI document yet")
  }
