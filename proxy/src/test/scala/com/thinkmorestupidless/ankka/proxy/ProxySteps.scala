package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{
  Admitted,
  Answers,
  ProxyEngine,
  ProxySettings,
  StandInProcess
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.io.{BufferedReader, InputStreamReader}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpHeaders, HttpRequest}
import java.net.{InetAddress, URI}
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

/**
 * The harness behind the proxy's living features: a real `ProxyEngine` over `TlsTransport` on
 * loopback, with certificates a test authority issued, a stand-in process behind it, and clients
 * holding the gateway's certificate ("a person on the internet", "a browser") or a service's.
 *
 * Each scenario builds its own settings from its `Given` steps and starts a fresh proxy and a fresh
 * stand-in on its first `When`, so no scenario sees another's requests. The steps are written in
 * the glossary's words; what they check is what the stand-in recorded being given, which is what
 * the process would have seen.
 */
abstract class ProxySteps(feature: String) extends GherkinSuite(feature) with LogCapturing:

  override def munitTimeout: Duration = 2.minutes

  protected val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
  protected val authority: TestPki    = TestPki.root("proxy-features")

  /**
   * `<service>-<project>.example.test`: the hostname the platform would give an exposed service.
   */
  protected def hostnameOf(service: String, project: String = project): String =
    s"$service-$project.example.test"

  // ── the scenario's state ──────────────────────────────────────────────────

  protected var project: String                 = "shop"
  protected var service: String                 = "web"
  protected var exposed: Boolean                = false
  protected var callers: Vector[Admitted]       = Vector.empty
  protected var responseTimeout: FiniteDuration = 60.seconds
  protected var streamInterval: FiniteDuration  = 1.second
  protected var process: StandInProcess         = null
  protected var engine: ProxyEngine             = null
  protected var serverTls: RotatingTls          = null
  protected var settings: ProxySettings         = null
  protected var thatRequest: String             = "/"
  protected var last: ProxySteps.Reply          = null
  protected var partArrivals: Vector[Long]      = Vector.empty

  override def beforeEach(context: munit.BeforeEach): Unit =
    project = "shop"
    service = "web"
    exposed = false
    callers = Vector.empty
    responseTimeout = 60.seconds
    streamInterval = 1.second
    process = null
    engine = null
    serverTls = null
    settings = null
    thatRequest = "/"
    last = null
    partArrivals = Vector.empty

  override def afterEach(context: munit.AfterEach): Unit =
    if engine != null then engine.stop()
    if process != null then process.stop()

  /** The proxy, started on the first request of the scenario from the settings its Givens built. */
  protected def proxy: ProxyEngine =
    if engine == null then
      process = StandInProcess(streamInterval).start()
      val directory = Files.createTempDirectory("proxy-features-server")
      authority
        .issue(uris = Seq(s"ankka://$project/$service"), dnsNames = Seq("localhost"))
        .writeTo(directory)
      serverTls = RotatingTls(directory, 1.minute)
      settings = ProxySettings(
        project = project,
        service = service,
        port = 0,
        processPort = process.port,
        probePort = 0,
        callers = callers,
        publicAuthority = if exposed then Some(hostnameOf(service)) else None,
        responseTimeout = responseTimeout,
        drainTimeout = 1.second
      )
      engine = ProxyEngine(settings, TlsTransport(serverTls, loopback), probeAddress = loopback)
      engine.start()
    engine

  // ── clients ───────────────────────────────────────────────────────────────

  private val contexts = new ConcurrentHashMap[String, SSLContext]()

  /** A client presenting a certificate the authority issued for `uri`. */
  protected def presenting(uri: String): HttpClient =
    val context = contexts.computeIfAbsent(
      uri,
      _ =>
        val directory = authority.issue(uris = Seq(uri)).writeTo(Files.createTempDirectory("c"))
        RotatingTls(directory, 1.minute).sslContext
    )
    HttpClient.newBuilder().sslContext(context).version(HttpClient.Version.HTTP_1_1).build()

  protected def internet: HttpClient = presenting("ankka://gateway")

  protected def serviceClient(project: String, name: String): HttpClient =
    presenting(s"ankka://$project/$name")

  protected def request(target: String, headers: (String, String)*): HttpRequest =
    val builder =
      HttpRequest.newBuilder(URI.create(s"https://localhost:${proxy.ports.public}$target"))
    headers.foreach((name, value) => builder.header(name, value))
    builder.build()

  protected def send(client: HttpClient, target: String, headers: (String, String)*): Unit =
    val response = client.send(request(target, headers*), BodyHandlers.ofByteArray())
    last = ProxySteps.Reply(
      response.statusCode,
      response.headers,
      new String(response.body, "UTF-8")
    )

  /** The one request the process was given, which is what most scenarios send. */
  protected def theRequest: StandInProcess.Received = process.requests match
    case Vector(one) => one
    case other       => fail(s"the process was given ${other.size} requests, not one: $other")

  protected def answeredByProxy(reply: ProxySteps.Reply): Boolean =
    reply.headers.firstValue(Answers.MarkerName).toScala.contains(Answers.MarkerValue)

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a web-hosted service {string} deployed in the project {string}") {
    (name: String, projectId: String) =>
      service = name
      project = projectId
  }

  Given("{string} is exposed") { (name: String) =>
    assertEquals(name, service, "only the web-hosted service is exposed here")
    exposed = true
  }

  Given("the descriptor of {string} admits no service") { (name: String) =>
    assertEquals(name, service)
    callers = Vector.empty
  }

  Given("the descriptor of {string} admits the service {string}") { (name: String, other: String) =>
    assertEquals(name, service)
    callers = callers :+ Admitted.Service(project, other)
  }

  Given("the descriptor of {string} admits the service {string} in the project {string}") {
    (name: String, other: String, otherProject: String) =>
      assertEquals(name, service)
      callers = callers :+ Admitted.Service(otherProject, other)
  }

  Given("the descriptor of {string} admits every service in the project {string}") {
    (name: String, projectId: String) =>
      assertEquals(name, service)
      assertEquals(projectId, project, "every service means every service of its own project")
      callers = callers :+ Admitted.AnyInProject
  }

  Given("the process answers a request with a stream of {int} parts, one each second") {
    (parts: Int) =>
      assertEquals(parts, 3, "the stand-in streams three parts")
      streamInterval = 1.second
      thatRequest = "/stream"
  }

  Given("the process takes a request and never answers it") { () =>
    responseTimeout = 2.seconds
    thatRequest = "/stall"
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a person on the internet sends a request to {string}") { (name: String) =>
    assertEquals(name, service)
    send(internet, "/from-the-internet")
  }

  When("the service {string} in the project {string} sends a request to {string}") {
    (sender: String, senderProject: String, name: String) =>
      assertEquals(name, service)
      send(serviceClient(senderProject, sender), s"/from-$sender")
  }

  When("a person on the internet sends a request that says the service {string} sent it") {
    (claimed: String) =>
      send(internet, "/claiming", "X-Ankka-Caller" -> s"service $project/$claimed")
  }

  When("a person on the internet sends a request to the hostname of {string}") { (name: String) =>
    assertEquals(name, service)
    send(internet, "/at-the-hostname", "Host" -> hostnameOf(service))
  }

  When(
    "a person on the internet sends a request to the hostname of {string} that says it was sent to {string}"
  ) { (name: String, claimed: String) =>
    assertEquals(name, service)
    send(
      internet,
      "/at-the-hostname",
      "Host"              -> hostnameOf(service),
      "X-Forwarded-Host"  -> claimed,
      "X-Forwarded-Proto" -> "http",
      "X-Forwarded-Port"  -> "80",
      "Forwarded"         -> s"host=$claimed"
    )
  }

  When("a browser sends that request") { () =>
    val response = internet.send(request(thatRequest), BodyHandlers.ofInputStream())
    val reader   = new BufferedReader(new InputStreamReader(response.body, "UTF-8"))
    val parts    = Vector.newBuilder[String]
    val arrivals = Vector.newBuilder[Long]
    var line     = reader.readLine()
    while line != null do
      arrivals += System.nanoTime()
      parts += line
      line = reader.readLine()
    partArrivals = arrivals.result()
    last = ProxySteps.Reply(
      response.statusCode,
      response.headers,
      parts.result().mkString("", "\n", "\n")
    )
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the process is told that the request came from the internet") { () =>
    assertEquals(last.status, 200, last.body)
    assertEquals(theRequest.all("x-ankka-caller"), Vector("internet"))
  }

  Then(
    "the process is told that the request came from the service {string} in the project {string}"
  ) { (sender: String, senderProject: String) =>
    assertEquals(last.status, 200, last.body)
    assertEquals(theRequest.all("x-ankka-caller"), Vector(s"service $senderProject/$sender"))
  }

  Then("that service is refused") { () =>
    assertEquals(last.status, 403, last.body)
    assert(answeredByProxy(last), "the refusal was not the proxy's")
    assert(last.body.contains("not admitted"), last.body)
  }

  Then("the process is given no request") { () =>
    assertEquals(process.requests, Vector.empty)
  }

  Then("the process is not shown what the request said") { () =>
    val values = theRequest.headers.map(_._2)
    assert(!values.exists(_.contains("service ")), s"the claim reached the process: $values")
  }

  Then("the process is told the hostname of {string} as the address the request was sent to") {
    (name: String) =>
      assertEquals(name, service)
      assertEquals(last.status, 200, last.body)
      assertEquals(theRequest.all("x-forwarded-host"), Vector(hostnameOf(service)))
      assertEquals(theRequest.all("x-forwarded-proto"), Vector("https"))
      assertEquals(theRequest.all("x-forwarded-port"), Vector("443"))
      assertEquals(theRequest.all("host"), Vector(hostnameOf(service)))
      assertEquals(theRequest.all("forwarded"), Vector.empty)
  }

  Then("the browser is shown each part when the process makes it") { () =>
    assertEquals(last.status, 200)
    assertEquals(last.body, "part 1\npart 2\npart 3\n")
    val written = process.partsWrittenAt
    assertEquals(written.size, 3, "the process wrote three parts")
    partArrivals.zip(written).zipWithIndex.foreach { case ((arrived, wrote), index) =>
      val lag = (arrived - wrote).nanos.toMillis
      assert(lag >= 0 && lag < 500, s"part ${index + 1} arrived ${lag}ms after it was written")
    }
  }

  Then("the browser does not wait for the last part to be shown the first") { () =>
    assert(
      partArrivals.head < process.lastPartWrittenAt,
      "the first part arrived only after the last was written"
    )
  }

  Then("the proxy answers that {string} did not answer in time") { (name: String) =>
    assertEquals(name, service)
    assertEquals(last.status, 504, last.body)
    assert(answeredByProxy(last), "the answer was not the proxy's")
    assert(last.body.contains("did not answer within"), last.body)
  }

  Then("the instance is ready") { () =>
    val status = HttpClient
      .newHttpClient()
      .send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${proxy.ports.probe}/ready")).build(),
        BodyHandlers.discarding()
      )
      .statusCode
    assertEquals(status, 200, "the probe did not answer ready")
  }

object ProxySteps:

  /** What a client was answered: the status, the headers, and the body as text. */
  final case class Reply(status: Int, headers: HttpHeaders, body: String)
