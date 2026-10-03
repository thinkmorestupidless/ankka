package com.thinkmorestupidless.ankka.proxy

import com.thinkmorestupidless.ankka.proxy.core.{
  Located,
  Admitted,
  Answers,
  ProxyEngine,
  ProxySettings,
  StandInProcess
}
import com.thinkmorestupidless.ankka.http.{Acl, Caller, Callers, HttpEndpoint, TlsServing}
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
import scala.jdk.CollectionConverters.*
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

  /** The services the scenario deployed beside "web", by project and name. */
  protected val callees = scala.collection.mutable.Map.empty[(String, String), ProxySteps.Callee]

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
    callees.clear()

  override def afterEach(context: munit.AfterEach): Unit =
    if engine != null then engine.stop()
    if process != null then process.stop()
    callees.values.foreach(_.stop())

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
        callingPort = 0,
        callers = callers,
        publicAuthority = if exposed then Some(hostnameOf(service)) else None,
        responseTimeout = responseTimeout,
        drainTimeout = 1.second
      )
      engine = ProxyEngine(
        settings,
        TlsTransport(serverTls, loopback),
        probeAddress = loopback,
        locator = (p, s) =>
          callees.get((p, s)).map(c => Located(java.net.URI.create(s"https://localhost:${c.port}")))
      )
      engine.start()
      process.servicesUrl = engine.callingUrl
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

  // ── calling other services ────────────────────────────────────────────────

  /** A service of the platform: its own certificate, its own access rule, a real HTTP server. */
  protected def deployCallee(
      name: String,
      inProject: String,
      acl: Acl,
      refuses: Boolean = false
  ): Unit =
    val directory = Files.createTempDirectory(s"callee-$name")
    authority
      .issue(uris = Seq(s"ankka://$inProject/$name"), dnsNames = Seq("localhost"))
      .writeTo(directory)
    callees((inProject, name)) = ProxySteps.Callee(name, inProject, directory, acl, refuses)

  /** The answer the process was given by its last call, and how the call was made. */
  protected var processCall: ProxySteps.Reply = null
  protected val processHeaders: Vector[(String, String)] =
    Vector("Accept" -> "application/json", "X-Request-Id" -> "r-1")

  protected def callFromProcess(target: String): ProxySteps.Reply =
    val builder = HttpRequest.newBuilder(java.net.URI.create(proxy.callingUrl + target))
    processHeaders.foreach((n, v) => builder.header(n, v))
    val response = HttpClient.newHttpClient().send(builder.build(), BodyHandlers.ofString())
    ProxySteps.Reply(response.statusCode, response.headers, response.body)

  Given("a service {string} deployed in the project {string}") { (name: String, inProject: String) =>
    deployCallee(name, inProject, Acl.AllowAll)
  }

  Given(
    "a service {string} deployed in the project {string} whose access rule admits only {string}"
  ) { (name: String, inProject: String, admitted: String) =>
    deployCallee(name, inProject, Acl.allowCallers(Callers.service(admitted)))
  }

  Given(
    "a service {string} deployed in the project {string} that answers every call with a refusal"
  ) { (name: String, inProject: String) =>
    deployCallee(name, inProject, Acl.AllowAll, refuses = true)
  }

  Given("{string} is not exposed") { (name: String) =>
    // A service other than "web" has no route on loopback whatever it is; for "web" the process is
    // told no public address.
    if name == service then exposed = false
  }

  Given("no service {string} in the project {string}") { (name: String, inProject: String) =>
    assert(!callees.contains((inProject, name)))
  }

  When("the process of {string} calls {string} at the calling address") {
    (name: String, callee: String) =>
      assertEquals(name, service)
      processCall = callFromProcess(s"/$callee/svc/whoami")
  }

  When("the process of {string} calls {string}, {string} and {string} at the calling address") {
    (name: String, a: String, b: String, c: String) =>
      assertEquals(name, service)
      for callee <- Vector(a, b, c) do
        val reply = callFromProcess(s"/$callee/svc/whoami")
        assertEquals(reply.status, 200, reply.body)
  }

  When("the process of {string} calls {string} in the project {string} at the calling address") {
    (name: String, callee: String, inProject: String) =>
      assertEquals(name, service)
      processCall = callFromProcess(s"/$callee.$inProject/svc/whoami")
  }

  When("the process of {string} sends a call to the calling address that names no service") {
    (name: String) =>
      assertEquals(name, service)
      processCall = callFromProcess("/")
  }

  When("a person on the internet asks {string} for what its process reads from {string}") {
    (name: String, callee: String) =>
      assertEquals(name, service)
      send(internet, s"/call?path=/$callee/svc/whoami")
  }

  When("the service {string} in the project {string} calls {string}") {
    (caller: String, inProject: String, callee: String) =>
      val target = callees.getOrElse((project, callee), fail(s"no service $callee"))
      val response = serviceClient(inProject, caller).send(
        HttpRequest
          .newBuilder(java.net.URI.create(s"https://localhost:${target.port}/svc/whoami"))
          .build(),
        BodyHandlers.ofString()
      )
      last = ProxySteps.Reply(response.statusCode, response.headers, response.body)
  }

  Then("{string} is told that the call came from the service {string} in the project {string}") {
    (callee: String, caller: String, inProject: String) =>
      val c = callees.values.find(_.name == callee).getOrElse(fail(s"no service $callee"))
      assertEquals(c.seen.map(_.caller), Vector(s"service:$inProject/$caller"))
  }

  Then("the process is given the answer of {string}") { (callee: String) =>
    assertEquals(processCall.status, 200, processCall.body)
    assertEquals(processCall.body, s"$callee saw service:$project/$service")
    assert(!answeredByProxy(processCall))
  }

  Then("each of those services is given the call made to it") { () =>
    for c <- callees.values do assertEquals(c.seen.map(_.path), Vector("/svc/whoami"), c.name)
  }

  Then("each is told that the call came from the service {string} in the project {string}") {
    (caller: String, inProject: String) =>
      for c <- callees.values do
        assertEquals(c.seen.map(_.caller), Vector(s"service:$inProject/$caller"), c.name)
  }

  Then("the person is shown what {string} answered") { (callee: String) =>
    assertEquals(last.status, 200, last.body)
    assertEquals(last.body, s"200 $callee saw service:$project/$service")
  }

  Then("a person on the internet who sends a request to {string} without {string} is refused") {
    (callee: String, name: String) =>
      assertEquals(name, service)
      val target = callees.getOrElse((project, callee), fail(s"no service $callee"))
      val response = internet.send(
        HttpRequest
          .newBuilder(java.net.URI.create(s"https://localhost:${target.port}/svc/whoami"))
          .build(),
        BodyHandlers.ofString()
      )
      assertEquals(response.statusCode, 403, response.body)
      assertEquals(target.seen.count(_.caller == "gateway"), 0)
  }

  Then("{string} is refused") { (caller: String) =>
    assertEquals(last.status, 403, s"$caller was not refused: ${last.body}")
  }

  Then("{string} is given the call as the process made it, with nothing added but who called") {
    (callee: String) =>
      val c = callees.values.find(_.name == callee).getOrElse(fail(s"no service $callee"))
      val seen = c.seen match
        case Vector(one) => one
        case other       => fail(s"one call expected: $other")
      assertEquals(seen.caller, s"service:$project/$service")
      for (n, v) <- processHeaders do
        assertEquals(
          seen.headers.collectFirst { case (k, x) if k.equalsIgnoreCase(n) => x },
          Some(v),
          n
        )
      val added = seen.headers.map(_._1.toLowerCase).filter(_.startsWith("x-")).toSet --
        processHeaders.map(_._1.toLowerCase).toSet
      assertEquals(added, Set.empty[String], "headers the process did not send")
  }

  Then("the process is given the refusal as {string} made it") { (callee: String) =>
    assertEquals(processCall.status, 403, processCall.body)
    assert(processCall.body.contains(s"refused by $callee"), processCall.body)
    assert(!answeredByProxy(processCall), "the refusal was the proxy's, not the service's")
  }

  Then("the process is told that there is no service {string}") { (name: String) =>
    assertEquals(processCall.status, 503, processCall.body)
    assert(answeredByProxy(processCall))
    assert(processCall.body.contains(s"no service '$name'"), processCall.body)
  }

  Then("no call is sent to any service") { () =>
    for c <- callees.values do assertEquals(c.seen, Vector.empty, c.name)
  }

  Then("the process is told that a call names a service") { () =>
    assertEquals(processCall.status, 400, processCall.body)
    assert(answeredByProxy(processCall))
    assert(processCall.body.contains("a call names a service"), processCall.body)
  }

object ProxySteps:

  /** What a client was answered: the status, the headers, and the body as text. */
  final case class Reply(status: Int, headers: HttpHeaders, body: String)

  /** One call a callee was given: who called, the path, and the headers. */
  final case class Seen(caller: String, path: String, headers: Vector[(String, String)])

  /** A service of the platform, served over its own certificate by the runtime's HTTP server. */
  final class Callee(
      val name: String,
      val project: String,
      directory: java.nio.file.Path,
      acl: Acl,
      refuses: Boolean
  ):
    private val calls = new java.util.concurrent.ConcurrentLinkedQueue[Seen]()

    private final class Endpoint extends HttpEndpoint("/svc"):
      val acl: Acl = Callee.this.acl
      get("/whoami") { () =>
        val who = Caller.encode(caller)
        calls.add(Seen(who, request.path, request.headers))
        if refuses then
          throw com.thinkmorestupidless.ankka.core.CommandError(
            s"refused by ${Callee.this.name}",
            com.thinkmorestupidless.ankka.core.ErrorCode.Forbidden
          )
        s"${Callee.this.name} saw $who"
      }

    private val running    = TlsServing.start(s"callee-$name", directory, Vector(new Endpoint))
    val port: Int          = running.port
    def seen: Vector[Seen] = calls.asScala.toVector
    def stop(): Unit       = running.stop()
