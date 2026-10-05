package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * `features/web-hosting/descriptor.feature`, against the real apply route.
 *
 * Every descriptor is applied twice: through the CLI's `Main.run`, which checks it before sending
 * anything, and as a raw request, so the control plane's own refusal is seen too. A step that says
 * what the refusal names or says asserts that both refusals carry the same words, which is the
 * point of the rules living in one module both ends depend on.
 */
class DescriptorFeatures
    extends GherkinSuite("../features/web-hosting/descriptor.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)

  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  /** A scratch config file, so the CLI never reads or writes the developer's own. */
  private lazy val config = Files.createTempFile("ankka-descriptor-features", ".json")

  override def beforeAll(): Unit =
    Files.delete(config)
    sys.props("ankka.config") = config.toString
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default,
        auth = Some(identity.config()),
        clock = identity.clock
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send("POST", "/projects/shop", Some("""{"name":"Shop","organizationId":"acme"}"""))._1,
      204
    )

  override def afterAll(): Unit =
    sys.props.remove("ankka.config"): Unit
    Files.deleteIfExists(config): Unit
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(method: String, path: String, body: Option[String]): (Int, String) =
    val builder = JdkRequest
      .newBuilder(URI.create(url + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $Token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  // ── the scenario's descriptor ─────────────────────────────────────────────

  private var name: String            = ""
  private var web: Boolean            = true
  private var fields: Vector[String]  = Vector.empty
  private var mounts: Vector[String]  = Vector.empty
  private var callers: Vector[String] = Vector.empty
  private var env: Vector[String]     = Vector.empty

  /** What the refusal must name when a step says only "the variable it declared", "the port" … */
  private var variable: String       = ""
  private var port: Int              = 0
  private var secret: String         = ""
  private var offendingMount: String = ""

  /** The CLI's exit code and standard error, and the raw request's status and body. */
  private var cliResult: (Int, String) = (0, "")
  private var rawResult: (Int, String) = (0, "")

  override def beforeEach(context: BeforeEach): Unit =
    name = ""
    web = true
    fields = Vector.empty
    mounts = Vector.empty
    callers = Vector.empty
    env = Vector.empty
    variable = ""
    port = 0
    secret = ""
    offendingMount = ""

  private def json: String =
    val hosting = if web then Vector(""""hosting":"web"""") else Vector.empty
    val lists = Vector(
      Option.when(mounts.nonEmpty)(mounts.mkString(""""mounts":[""", ",", "]")),
      Option.when(callers.nonEmpty)(
        callers.map(c => s""""$c"""").mkString(""""callers":[""", ",", "]")
      ),
      Option.when(env.nonEmpty)(env.mkString(""""env":[""", ",", "]"))
    ).flatten
    val service = (s""""image":"$name:1.0"""" +: (hosting ++ fields ++ lists)).mkString(",")
    s"""{"name":"$name","service":{$service}}"""

  private def mount(service: String, path: String): String =
    s"""{"path":"$path","service":"$service"}"""

  private def fromSecret(secretName: String): Unit =
    variable = "TLS_KEY"
    secret = secretName
    env = env :+ s"""{"name":"$variable","secretKeyRef":{"name":"$secretName","key":"tls.key"}}"""

  // ── Given ─────────────────────────────────────────────────────────────────

  /** Each `<declares>` cell of the first outline, as the descriptor fragment it stands for. */
  private val declares: Vector[(String, () => Unit)] = Vector(
    "says the service serves no requests" -> (() => fields :+= """"http":false"""),
    "declares a protocol version"         -> (() => fields :+= """"protocol":"1.0""""),
    "declares a runtime version"          -> (() => fields :+= """"runtime":"0.9.0""""),
    "declares a database of its own" -> { () =>
      variable = "ANKKA_DB_HOST"
      env :+= s"""{"name":"$variable","value":"postgres"}"""
    },
    """sets the variable "PORT"""" -> (() => env :+= """{"name":"PORT","value":"3000"}"""),
    """states the port "70000" for its process""" -> { () =>
      port = 70000
      fields :+= """"processPort":70000"""
    },
    "states the port of the service for its process" -> { () =>
      port = 9000
      fields :+= """"processPort":9000"""
    },
    "gives the service a port the proxy listens on" -> { () =>
      port = 7630
      fields :+= """"port":7630"""
    },
    "states a port the proxy listens on for its process" -> { () =>
      port = 7627
      fields :+= """"processPort":7627"""
    }
  )

  declares.foreach { (phrase, declare) =>
    Given(s"a descriptor for the web-hosted service {string} that $phrase") { (service: String) =>
      name = service
      declare()
    }
  }

  Given(
    "a descriptor for the web-hosted service {string} that declares a protocol version and has {string} mounted at {string}"
  ) { (service: String, mounted: String, path: String) =>
    name = service
    fields :+= """"protocol":"1.0""""
    mounts :+= mount(mounted, path)
    offendingMount = path
  }

  Given(
    "a descriptor for the web-hosted service {string} with a variable taken from the secret that holds the certificate of {string}"
  ) { (service: String, holder: String) =>
    name = service
    fromSecret(s"$holder-service-tls")
  }

  Given(
    "a descriptor for the web-hosted service {string} with a variable taken from the secret that holds the certificate {string} passes mounts on with"
  ) { (service: String, holder: String) =>
    name = service
    fromSecret(s"$holder-mount-tls")
  }

  Given(
    "a descriptor for a service {string} that is not web-hosted with a variable taken from the secret that holds the certificate of the service {string}"
  ) { (service: String, holder: String) =>
    name = service
    web = false
    fromSecret(s"$holder-service-tls")
  }

  Given("a descriptor for the web-hosted service {string} with {string} mounted at {string}") {
    (service: String, mounted: String, path: String) =>
      name = service
      mounts :+= mount(mounted, path)
      offendingMount = path
  }

  Given(
    "a descriptor for the web-hosted service {string} with {string} mounted at {string} and {string} mounted at {string}"
  ) { (service: String, first: String, firstPath: String, second: String, secondPath: String) =>
    name = service
    mounts :+= mount(first, firstPath)
    mounts :+= mount(second, secondPath)
    // A duplicate is named by its path; one mount inside another by the inner one.
    offendingMount = if firstPath == secondPath then firstPath else secondPath
  }

  Given("a descriptor for the web-hosted service {string} that admits the service {string}") {
    (service: String, admitted: String) =>
      name = service
      callers :+= admitted
  }

  Given(
    "a descriptor for the web-hosted service {string} that admits the service {string} in the project {string}"
  ) { (service: String, admitted: String, project: String) =>
    name = service
    callers :+= s"$project/$admitted"
  }

  Given(
    "a descriptor for the web-hosted service {string} that admits the service {string} and the service {string}"
  ) { (service: String, first: String, second: String) =>
    name = service
    callers ++= Vector(first, second)
  }

  Given(
    "a descriptor for a service {string} that is not a web-hosted service, with {string} mounted at {string}"
  ) { (service: String, mounted: String, path: String) =>
    name = service
    web = false
    mounts :+= mount(mounted, path)
  }

  Given(
    "a descriptor for a service {string} that is not a web-hosted service, that admits the service {string}"
  ) { (service: String, admitted: String) =>
    name = service
    web = false
    callers :+= admitted
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member applies the descriptor") { () =>
    val file = Files.createTempFile("ankka-descriptor", ".json")
    try
      Files.writeString(file, json): Unit
      val out = ByteArrayOutputStream()
      val err = ByteArrayOutputStream()
      val code = Main.run(
        Seq("services", "apply", "-f", file.toString, "-p", "shop", "--url", url, "--token", Token),
        PrintStream(out, true, StandardCharsets.UTF_8),
        PrintStream(err, true, StandardCharsets.UTF_8)
      )
      cliResult = (code, err.toString(StandardCharsets.UTF_8))
    finally Files.deleteIfExists(file): Unit
    rawResult = send("PUT", s"/services/shop/$name", Some(json))
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the member is refused") { () =>
    assertEquals(cliResult._1, 1, s"the CLI accepted $json: ${cliResult._2}")
    assert(cliResult._2.contains("invalid descriptor"), cliResult._2)
    assertEquals(rawResult._1, 400, s"the control plane accepted $json: ${rawResult._2}")
  }

  /**
   * Both refusals carry `words`: the CLI's check and the control plane's are one rule. The control
   * plane's arrives as JSON, so its quotes are escaped.
   */
  private def refusalSays(words: String): Unit =
    val escaped = words.replace("\"", "\\\"")
    assert(cliResult._2.contains(words), s"the CLI's refusal lacks <$words>: ${cliResult._2}")
    assert(rawResult._2.contains(escaped), s"the control plane's lacks <$escaped>: ${rawResult._2}")

  Then("the refusal names what it said")(() => refusalSays("""remove "http": false"""))
  Then("the refusal names the protocol version")(() => refusalSays("protocol is meaningful only"))
  Then("the refusal names the runtime version")(() => refusalSays("runtime is meaningful only"))
  Then("the refusal names the variable it declared")(() => refusalSays(s"env var '$variable'"))
  Then("the refusal names the variable {string}")((v: String) => refusalSays(s"env var '$v'"))

  Then("the refusal names the port") { () =>
    refusalSays(s"$port ")
    assert(
      rawResult._2.contains(s"processPort $port") || rawResult._2.contains(s"service port $port"),
      rawResult._2
    )
  }

  Then("the refusal names the variable and the secret") { () =>
    refusalSays(s"env var '$variable': secret '$secret' is issued by the platform")
  }

  Then("the refusal names the mount")(() => refusalSays(s"mount '$offendingMount'"))

  Then("the refusal names the mount and says that a path starts with {string}") { (s: String) =>
    refusalSays(s"""mount '$offendingMount': a path starts with "$s"""")
  }

  Then("the refusal names the mount and says that a mount cannot be every path") { () =>
    refusalSays(s"mount '$offendingMount': a mount cannot be every path")
  }

  Then("the refusal names the mount and says that the path is mounted more than once") { () =>
    refusalSays(s"mount '$offendingMount' is declared more than once")
  }

  Then("the refusal names the mount and says that one mount is inside another") { () =>
    refusalSays(s"mount '$offendingMount' is inside mount")
  }

  Then("the refusal names the mount and says that {string} cannot name a service") {
    (service: String) =>
      refusalSays(s"mount '$offendingMount': '$service' is not a service name")
  }

  Then("the refusal names the mount and says that a web-hosted service cannot mount itself") { () =>
    refusalSays(s"mount '$offendingMount': a web-hosted service cannot mount itself")
  }

  Then("the refusal says that {string} cannot name a service") { (entry: String) =>
    refusalSays(s"caller '$entry' is not")
  }

  Then("the refusal says that {string} cannot name a project") { (project: String) =>
    refusalSays(s"caller '$project/")
  }

  Then("the refusal says that {string} is admitted more than once") { (entry: String) =>
    refusalSays(s"caller '$entry' is declared more than once")
  }

  Then("the refusal says that only a web-hosted service has mounts") { () =>
    refusalSays("mounts is meaningful only for web hosting")
  }

  Then("the refusal says that only a web-hosted service names the services it admits") { () =>
    refusalSays("callers is meaningful only for web hosting")
  }
