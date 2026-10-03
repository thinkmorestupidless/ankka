package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.controlplane.api.ServiceDescriptor
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `ankka init --language web` held to `features/web-hosting/template.feature`'s first two
 * scenarios: the project the real command renders passes its own checks, and runs on this machine
 * behind `ankka local web` beside a service. Needs `node` and `npm`; named in
 * `-Dankka.template.tests`, it fails rather than skips without them.
 */
class WebTemplateSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 10.minutes

  private def missingTools: Seq[String] = Seq("node", "npm").filterNot(TemplateSwitch.onPath)

  override def munitIgnore: Boolean = TemplateSwitch.skip("web", missingTools)

  private val Name                = "shop-web"
  private var workspace: Path     = null
  private def project: Path       = workspace.resolve(Name)
  private val loopback            = InetAddress.getLoopbackAddress
  private var built               = false
  private var thread: Thread      = null
  private var backend: HttpServer = null

  override def beforeAll(): Unit =
    if !munitIgnore then
      TemplateSwitch.requireTools("web", missingTools)
      workspace = Files.createTempDirectory("ankka-web-template")
      val out = ByteArrayOutputStream()
      val err = ByteArrayOutputStream()
      val code = Main.run(
        Seq("init", Name, "--language", "web", "--dir", workspace.toString),
        PrintStream(out),
        PrintStream(err)
      )
      assertEquals(code, 0, s"ankka init failed:\n$out\n$err")

  override def afterAll(): Unit =
    if thread != null then
      thread.interrupt()
      thread.join(15_000)
    if backend != null then backend.stop(0)

  private def files: Vector[Path] =
    Files
      .walk(project)
      .iterator()
      .asScala
      .filter(f => Files.isRegularFile(f) && !f.toString.contains("node_modules"))
      .toVector

  /** Runs a command in the project, its output passed through, and returns the output too. */
  private def run(command: String*): (Int, String) =
    val process =
      new ProcessBuilder(command*).directory(project.toFile).redirectErrorStream(true).start()
    val text = new String(process.getInputStream.readAllBytes())
    print(text)
    (process.waitFor(), text)

  private def installAndBuild(): Unit =
    if !built then
      assertEquals(run("npm", "install", "--no-audit", "--no-fund")._1, 0, "npm install")
      assertEquals(run("npm", "run", "build")._1, 0, "npm run build")
      built = true

  private def freePort(): Int =
    val socket = new ServerSocket(0, 0, loopback)
    try socket.getLocalPort
    finally socket.close()

  test("a web-hosted service started from the template passes its tests") {
    // Every token rendered, and GitHub's own expressions left as they are.
    val leftovers = files.flatMap { f =>
      val text = Files.readString(f).replace("${{", "")
      Vector("{{name}}", "{{ankka_version}}", "{{").find(text.contains).map(t => s"$f: $t")
    }
    assertEquals(leftovers, Vector.empty)
    val descriptor =
      readFromString[ServiceDescriptor](Files.readString(project.resolve("service.json")))
    assertEquals(descriptor.problems, Vector.empty)
    assertEquals(descriptor.name, Name)
    assert(descriptor.service.isWebHosted, descriptor.service.toString)
    assert(descriptor.service.mounts.nonEmpty, "the descriptor has a mount")

    installAndBuild()
    assertEquals(run("npm", "run", "typecheck")._1, 0, "typecheck")
    val (code, output) = run("npm", "test")
    assertEquals(code, 0, "npm test")
    assert(output.contains("ℹ skipped 0"), "every test must run, none skip")
    assert(!output.contains("ℹ pass 0"), "a test run that passed nothing has checked nothing")
  }

  test("a web-hosted service started from the template runs beside a service on the same machine") {
    installAndBuild()
    // The backend: answers every request with what it was asked for.
    val asked = new ConcurrentLinkedQueue[String]()
    backend = HttpServer.create(new InetSocketAddress(loopback, 0), 0)
    backend.createContext(
      "/",
      exchange =>
        val path = exchange.getRequestURI.getRawPath
        asked.add(path)
        val body = s"backend saw $path".getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    backend.start()

    val port = freePort()
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    thread = Thread
      .ofPlatform()
      .start(() =>
        Main.run(
          Seq(
            "local",
            "web",
            "-f",
            project.resolve("service.json").toString,
            "--port",
            port.toString,
            "--service",
            s"backend=http://127.0.0.1:${backend.getAddress.getPort}",
            "--",
            "npm",
            "--prefix",
            project.toString,
            "start"
          ),
          PrintStream(out),
          PrintStream(err)
        ): Unit
      )
    val http = HttpClient.newHttpClient()
    def get(path: String) =
      http.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).build(),
        BodyHandlers.ofString()
      )
    // The proxy is up at once; the server is up when the page answers.
    val deadline = System.nanoTime() + 60.seconds.toNanos
    var page     = scala.util.Try(get("/")).toOption
    while !page.exists(_.statusCode == 200) && System.nanoTime() < deadline do
      Thread.sleep(250)
      page = scala.util.Try(get("/")).toOption
    assert(page.exists(_.statusCode == 200), s"the interface never answered:\n$out\n$err")

    // A browser is shown the interface.
    assert(page.get.body.contains("""<div id="root">"""), page.get.body)
    // Under the mount, the backend answers, with the mount's path removed.
    val mounted = get("/api/carts/c1")
    assertEquals((mounted.statusCode, mounted.body), (200, "backend saw /carts/c1"))
    // What the backend answered the server's own call at the calling address.
    val summary = get("/summary")
    assertEquals(summary.statusCode, 200, summary.body)
    assertEquals(summary.body, """{"status":200,"body":"backend saw /"}""")
    assertEquals(asked.asScala.toVector.sorted, Vector("/", "/carts/c1"))
  }
