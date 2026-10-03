package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * `ankka local web` through `Main.run`, one test per scenario of
 * `features/web-hosting/local.feature` and one per rule of the command's contract. The process and
 * the services are stand-ins on loopback; a service is found as the local console finds one,
 * through a running directory this suite owns.
 */
class LocalWebSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 60.seconds

  private val loopback = InetAddress.getLoopbackAddress
  private val http     = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  private var running: Path             = null
  private var servers: List[HttpServer] = Nil
  private var commands: List[Thread]    = Nil
  private var previous: Option[String]  = None
  private var work: Path                = null

  override def beforeEach(context: BeforeEach): Unit =
    running = Files.createTempDirectory("ankka-local-web-running")
    work = Files.createTempDirectory("ankka-local-web")
    previous = sys.props.get("ankka.running.dir")
    sys.props("ankka.running.dir") = running.toString

  override def afterEach(context: AfterEach): Unit =
    commands.foreach { t =>
      t.interrupt()
      t.join(5_000)
    }
    commands = Nil
    servers.foreach(_.stop(0))
    servers = Nil
    // The directory first, then the property: nothing may be left where a developer's would be.
    Files.list(running).iterator().asScala.foreach(Files.deleteIfExists(_): Unit)
    previous match
      case Some(value) => sys.props("ankka.running.dir") = value
      case None        => sys.props -= "ankka.running.dir": Unit

  private def freePort(): Int =
    val socket = new ServerSocket(0, 0, loopback)
    try socket.getLocalPort
    finally socket.close()

  /** One request a stand-in was given: the path with its query, and the caller it was told. */
  private final case class Given(target: String, caller: Option[String])

  /** A stand-in on loopback answering every path with its own name, and recording what it got. */
  private final class StandIn(name: String, port: Int = 0):
    val received = new ConcurrentLinkedQueue[Given]()
    val server   = HttpServer.create(new InetSocketAddress(loopback, port), 0)
    server.createContext(
      "/",
      exchange =>
        val uri = exchange.getRequestURI
        received.add(
          Given(
            uri.getRawPath + Option(uri.getRawQuery).map("?" + _).getOrElse(""),
            Option(exchange.getRequestHeaders.getFirst("X-Ankka-Caller"))
          )
        )
        val body = s"$name answered".getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    // What the local console asks a running service for, to learn where it answers HTTP.
    server.createContext(
      "/observability/service",
      exchange =>
        val body = s"""{"name":"$name","address":"http://127.0.0.1:${server.getAddress.getPort}"}"""
          .getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    server.start()
    servers = server :: servers
    def address: String         = s"http://127.0.0.1:${server.getAddress.getPort}"
    def requests: Vector[Given] = received.asScala.toVector
    def stop(): Unit            = server.stop(0)

  /** A service running on this machine, as the local console would find it. */
  private def runningService(name: String): StandIn =
    val service = StandIn(name)
    Files.writeString(
      running.resolve(s"$name.json"),
      s"""{"name":"$name","instanceId":"$name","pid":1,""" +
        s""""observabilityAddress":"${service.address}","startedAt":"2026-10-03T12:00:00Z"}"""
    ): Unit
    service

  private def descriptor(json: String): Path =
    val file = work.resolve("service.json")
    Files.writeString(file, json)
    file

  private def webDescriptor(processPort: Int, mounts: String = ""): Path =
    descriptor(
      s"""{"name":"web","service":{"image":"web:1","hosting":"web","processPort":$processPort""" +
        (if mounts.isEmpty then "" else s""","mounts":[$mounts]""") + "}}"
    )

  /** The command, running, and what it has printed so far. */
  private final class Running(val out: ByteArrayOutputStream, val err: ByteArrayOutputStream):
    @volatile var code: Option[Int] = None
    def printed: String             = out.toString(StandardCharsets.UTF_8)
    def complained: String          = err.toString(StandardCharsets.UTF_8)
    def servicesUrl: String =
      waitFor("the calling address to be printed")(
        printed.linesIterator.collectFirst {
          case l if l.startsWith("ANKKA_SERVICES_URL=") => l.drop(19)
        }
      )

  private def start(args: String*): Running =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val run = Running(out, err)
    val thread = Thread
      .ofPlatform()
      .start(() =>
        run.code = Some(
          Main.run(
            Seq("local", "web") ++ args,
            PrintStream(out, true, StandardCharsets.UTF_8),
            PrintStream(err, true, StandardCharsets.UTF_8)
          )
        )
      )
    commands = thread :: commands
    run

  /** Runs the command to its end, for the cases that end by themselves. */
  private def runToEnd(args: String*): Running =
    val run = start(args*)
    waitFor(s"the command to end; it printed ${run.printed} ${run.complained}")(run.code)
    run

  private def waitFor[A](what: String, within: FiniteDuration = 20.seconds)(
      check: => Option[A]
  ): A =
    val deadline = System.nanoTime() + within.toNanos
    var found    = check
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
      found = check
    found.getOrElse(fail(s"$what did not happen within $within"))

  private def get(url: String): HttpResponse[String] =
    http.send(HttpRequest.newBuilder(URI.create(url)).build(), BodyHandlers.ofString())

  private def marked(response: HttpResponse[?]): Boolean =
    response.headers.firstValue("X-Ankka-Answered-By").toScala.contains("proxy")

  /** The proxy running with "web"'s descriptor, and the developer's process at its port. */
  private def proxyWithProcess(mounts: String = ""): (Running, StandIn, Int) =
    val processPort = freePort()
    val process     = StandIn("the process", processPort)
    val port        = freePort()
    val run = start("-f", webDescriptor(processPort, mounts).toString, "--port", port.toString)
    run.servicesUrl: Unit
    (run, process, port)

  // ── features/web-hosting/local.feature ────────────────────────────────────

  test("the process calls a service running on the same machine by name") {
    val cart        = runningService("cart")
    val (run, _, _) = proxyWithProcess()
    val response    = get(s"${run.servicesUrl}/cart/carts/c1")
    assertEquals(response.statusCode, 200, response.body)
    assertEquals(response.body, "cart answered")
    assertEquals(cart.requests.map(_.target), Vector("/carts/c1"))
  }

  test("mounts answer at the same paths on a developer's machine") {
    val cart               = runningService("cart")
    val (_, process, port) = proxyWithProcess("""{"path":"/api/cart","service":"cart"}""")
    val response           = get(s"http://127.0.0.1:$port/api/cart/carts/c1")
    assertEquals(response.statusCode, 200, response.body)
    assertEquals(cart.requests.map(_.target), Vector("/carts/c1"))
    assertEquals(process.requests, Vector.empty)
  }

  test("a request outside every mount reaches the developer's process") {
    val cart               = runningService("cart")
    val (_, process, port) = proxyWithProcess("""{"path":"/api/cart","service":"cart"}""")
    assertEquals(get(s"http://127.0.0.1:$port/about").statusCode, 200)
    assertEquals(process.requests.map(_.target), Vector("/about"))
    assertEquals(cart.requests, Vector.empty)
  }

  test("a service that is not running is named in the answer") {
    val (run, _, _) = proxyWithProcess()
    val response    = get(s"${run.servicesUrl}/cart/carts/c1")
    assertEquals(response.statusCode, 503)
    assert(marked(response), "the answer was not the proxy's")
    assertEquals(response.body, """{"error":"no service 'cart'"}""")
  }

  test("on a developer's machine every request came from that machine") {
    val (_, process, port) = proxyWithProcess()
    assertEquals(get(s"http://127.0.0.1:$port/").statusCode, 200)
    assertEquals(process.requests.map(_.caller), Vector(Some("local")))
  }

  // ── the command's contract ────────────────────────────────────────────────

  test("--service says where a service is, before the running directory") {
    val registered  = runningService("cart")
    val named       = StandIn("the named cart")
    val processPort = freePort()
    StandIn("the process", processPort): Unit
    val run = start(
      "-f",
      webDescriptor(processPort).toString,
      "--port",
      freePort().toString,
      "--service",
      s"cart=${named.address}"
    )
    assertEquals(get(s"${run.servicesUrl}/cart/x").body, "the named cart answered")
    assertEquals(registered.requests, Vector.empty)
  }

  test("a descriptor that is not for web hosting is refused, naming its hosting") {
    val file = descriptor("""{"name":"cart","service":{"image":"cart:1"}}""")
    val run  = runToEnd("-f", file.toString, "--port", freePort().toString)
    assertEquals(run.code, Some(2))
    assert(
      run.complained.contains(
        "ankka local web is for a service with web hosting; this one is \"embedded\""
      ),
      run.complained
    )
  }

  test("a descriptor with two problems prints both, as misuse") {
    val file = descriptor(
      """{"name":"web","service":{"image":"web:1","hosting":"web","protocol":"1.0","processPort":7630}}"""
    )
    val run = runToEnd("-f", file.toString)
    assertEquals(run.code, Some(2))
    assert(run.complained.contains("protocol is meaningful only"), run.complained)
    assert(run.complained.contains("processPort 7630 is used by the platform"), run.complained)
  }

  test("a descriptor that is not there names the path") {
    val run = runToEnd("-f", work.resolve("missing.json").toString)
    assertEquals(run.code, Some(1))
    assert(run.complained.contains("missing.json"), run.complained)
  }

  test(
    "a command runs on a free port with the calling address, and its exit code is the command's"
  ) {
    val processPort = freePort()
    val seen        = work.resolve("seen.txt")
    val run = runToEnd(
      "-f",
      webDescriptor(processPort).toString,
      "--port",
      freePort().toString,
      "--",
      "sh",
      "-c",
      s"""echo "$$PORT $$ANKKA_SERVICES_URL" > $seen; exit 3"""
    )
    assertEquals(run.code, Some(3))
    val Array(port, url) = Files.readString(seen).trim.split(" ", 2): @unchecked
    assertNotEquals(port.toInt, processPort, "the descriptor's port is for a cluster")
    assert(url.startsWith("http://127.0.0.1:"), url)
    assertEquals(run.printed.linesIterator.find(_.startsWith("PORT=")), Some(s"PORT=$port"))
  }

  test("without a command, --port equal to the process's port is refused") {
    val processPort = freePort()
    val file        = webDescriptor(processPort)
    val run         = runToEnd("-f", file.toString, "--port", processPort.toString)
    assertEquals(run.code, Some(2))
    assert(
      run.complained.contains(
        s"--port $processPort is the process's own port; choose another, or state processPort in $file"
      ),
      run.complained
    )
  }

  test("a port in use is named") {
    val taken = StandIn("in the way")
    val run = runToEnd(
      "-f",
      webDescriptor(freePort()).toString,
      "--port",
      taken.server.getAddress.getPort.toString
    )
    assertEquals(run.code, Some(1))
    assert(
      run.complained.contains(s"port ${taken.server.getAddress.getPort} is in use"),
      run.complained
    )
  }

  test("a variable taken from a secret is named and left unset") {
    val processPort = freePort()
    val seen        = work.resolve("env.txt")
    val file = descriptor(
      s"""{"name":"web","service":{"image":"web:1","hosting":"web","processPort":$processPort,""" +
        """"env":[{"name":"GREETING","value":"hi"},""" +
        """{"name":"SESSION_KEY","secretKeyRef":{"name":"web-secrets","key":"session"}}]}}"""
    )
    val run = runToEnd(
      "-f",
      file.toString,
      "--port",
      freePort().toString,
      "--",
      "sh",
      "-c",
      s"""echo "$${GREETING:-unset} $${SESSION_KEY:-unset}" > $seen"""
    )
    assertEquals(run.code, Some(0))
    assertEquals(Files.readString(seen).trim, "hi unset")
    assert(
      run.complained.contains("SESSION_KEY is taken from the secret 'web-secrets'"),
      run.complained
    )
  }
