package nakka.controlplane

import nakka.cli.{Main, Settings}
import nakka.controlplane.api.ControlPlaneAcl
import nakka.controlplane.deploy.DeployConfig
import nakka.http.HttpServer
import nakka.runtime.ProjectionRuntime
import nakka.testkit.NakkaTestKit

import java.io.{ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The real CLI against a real control plane.
 *
 * `Main.run` is the same entry point the `nakka` binary calls, so this covers argument parsing,
 * settings precedence, the HTTP client, error mapping and exit codes together. It is the only test
 * that can catch the CLI and the server disagreeing about the wire format, which is the failure the
 * shared `controlplane-api` module exists to prevent.
 */
class CliEndToEndSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private val Token = "cli-test-token"

  private var testKit: NakkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null

  override def beforeAll(): Unit =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        ControlPlaneAcl.bearer(Token),
        DeployConfig.default.copy(baseDomain = Some("example.test"))
      )*
    )
    testKit = NakkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

    // A scratch config file, so `nakka config set` in a test cannot touch the developer's
    // own ~/.nakka/config.json.
    config = Files.createTempFile("nakka-cli-e2e", ".json")
    Files.delete(config)
    sys.props("nakka.config") = config.toString

  override def afterAll(): Unit =
    sys.props.remove("nakka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()

  /** Runs the CLI, returning its exit code and both streams. */
  private def cli(args: String*): (Int, String, String) = cli(None, args*)

  private def cli(stdin: Option[String], args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val outs = PrintStream(out, true, StandardCharsets.UTF_8)
    val errs = PrintStream(err, true, StandardCharsets.UTF_8)
    val code = stdin match
      case Some(text) => Console.withIn(StringReader(text))(Main.run(args, outs, errs))
      case None       => Main.run(args, outs, errs)
    outs.flush()
    errs.flush()
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  /** The connection flags every call needs before `config set` has run. */
  private def connected(args: String*): Seq[String] =
    args ++ Seq("--url", url, "--token", Token)

  private def eventually(description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[String]
  ): String =
    val deadline             = System.nanoTime() + within.toNanos
    var last: Option[String] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(200)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def descriptorFile(json: String): Path =
    val file = Files.createTempFile("nakka-descriptor", ".json")
    Files.writeString(file, json): Unit
    file

  test("an unknown subcommand is a usage error, exit code 2") {
    val (code, out, err) = cli("services", "levitate")
    assertEquals(code, 2, out)
    assert(err.contains("Usage"), err)
  }

  test("no subcommand at all prints usage") {
    assertEquals(cli()._1, 2)
  }

  test("an unreachable control plane names the fix") {
    val (code, _, err) =
      cli("organizations", "list", "--url", "http://127.0.0.1:1", "--token", Token)
    assertEquals(code, 1)
    assert(err.contains("no control plane at"), err)
    assert(err.contains("NAKKA_URL"), err)
  }

  test("a rejected token says the token was rejected") {
    val (code, _, err) = cli("organizations", "list", "--url", url, "--token", "wrong")
    assertEquals(code, 1)
    assert(err.contains("token was rejected"), err)
  }

  test("creating and listing an organization") {
    val (code, out, err) =
      cli(connected("organizations", "create", "acme", "--name", "Acme Corp")*)
    assertEquals(code, 0, err)
    assert(out.contains("organization 'acme' created"), out)

    val listing = eventually("acme reaches the organizations view") {
      val (listCode, listOut, _) = cli(connected("organizations", "list")*)
      Option.when(listCode == 0 && listOut.contains("acme"))(listOut)
    }
    assert(listing.contains("NAME"), listing)
    assert(listing.contains("Acme Corp"), listing)
  }

  test("creating an organization twice reports the server's own conflict message") {
    val (code, _, err) = cli(connected("organizations", "create", "acme", "--name", "Again")*)
    assertEquals(code, 1)
    assert(err.contains("already exists"), err)
  }

  test("creating a project requires an organization that exists") {
    val (code, _, err) = cli(
      connected("projects", "create", "nope", "--name", "Nope", "-O", "ghost")*
    )
    assertEquals(code, 1)
    assert(err.contains("no such organization 'ghost'"), err)
  }

  test("creating a project") {
    val (code, out, err) = cli(
      connected("projects", "create", "checkout", "--name", "Checkout", "-O", "acme")*
    )
    assertEquals(code, 0, err)
    assert(out.contains("project 'checkout' created"), out)
  }

  test("a service command without a project names the fix rather than 404ing") {
    val (code, _, err) = cli("services", "list", "--url", url, "--token", Token)
    assertEquals(code, 1)
    assert(err.contains("no project selected"), err)
    assert(err.contains("config set project"), err)
  }

  test("config set persists url, token and project so later calls need no flags") {
    assertEquals(cli("config", "set", "url", url)._1, 0)
    assertEquals(cli("config", "set", "token", Token)._1, 0)
    assertEquals(cli("config", "set", "project", "checkout")._1, 0)

    val saved = Settings.load()
    assertEquals(saved.url, url)
    assertEquals(saved.project, Some("checkout"))

    // No flags at all now.
    val (code, out, err) = cli("services", "list")
    assertEquals(code, 0, err)
    assertEquals(out.trim, "no results")
  }

  test("config get never prints the token") {
    val (code, out, _) = cli("config", "get")
    assertEquals(code, 0)
    assert(!out.contains(Token), out)
    assert(out.contains("(set)"), out)

    val (jsonCode, json, _) = cli("config", "get", "-o", "json")
    assertEquals(jsonCode, 0)
    assert(!json.contains(Token), json)
  }

  test("an invalid descriptor is caught before the request is sent") {
    val file = descriptorFile(
      """{"name":"broken","service":{"image":"","resources":{"instanceType":"huge"}}}"""
    )
    try
      val (code, _, err) = cli("services", "apply", "-f", file.toString)
      assertEquals(code, 1)
      assert(err.contains("invalid descriptor in"), err)
      assert(err.contains("image must not be empty"), err)
      assert(err.contains("unknown instanceType 'huge'"), err)

      // Client-side validation must not have created anything.
      val (getCode, _, getErr) = cli("services", "get", "broken")
      assertEquals(getCode, 1, getErr)
    finally Files.deleteIfExists(file): Unit
  }

  test("a descriptor file that does not exist is a clear error") {
    val (code, _, err) = cli("services", "apply", "-f", "/nonexistent/service.json")
    assertEquals(code, 1)
    assert(err.contains("no such descriptor file"), err)
  }

  test("a descriptor file that is not JSON is a clear error") {
    val file = descriptorFile("this is not json")
    try
      val (code, _, err) = cli("services", "apply", "-f", file.toString)
      assertEquals(code, 1)
      assert(err.contains("could not read"), err)
    finally Files.deleteIfExists(file): Unit
  }

  test("applying a descriptor prints the resulting status") {
    val file = descriptorFile("""{"name":"cart","service":{"image":"cart:1.0"}}""")
    try
      val (code, out, err) = cli("services", "apply", "-f", file.toString)
      assertEquals(code, 0, err)
      assert(out.contains("cart"), out)
      assert(out.contains("UpdateInProgress"), out)
      assert(out.contains("cart:1.0"), out)
      assert(out.contains("generation"), out)
    finally Files.deleteIfExists(file): Unit
  }

  test("a descriptor can be applied from stdin") {
    val (code, out, err) = cli(
      Some("""{"name":"basket","service":{"image":"basket:2.0"}}"""),
      "services",
      "apply",
      "-f",
      "-"
    )
    assertEquals(code, 0, err)
    assert(out.contains("basket:2.0"), out)
  }

  test("services list shows both services once the view catches up") {
    val listing = eventually("both services reach the view") {
      val (code, out, _) = cli("services", "list")
      Option.when(code == 0 && out.contains("cart") && out.contains("basket"))(out)
    }
    assert(listing.contains("INSTANCES"), listing)
    assert(listing.contains("0/0"), listing)
  }

  test("services list as json is parseable and carries a one-word lifecycle") {
    val (code, out, err) = cli("services", "list", "-o", "json")
    assertEquals(code, 0, err)
    assert(out.trim.startsWith("["), out)
    assert(out.contains("\"lifecycle\":\"UpdateInProgress\""), out)
  }

  test("services get shows one service as fields") {
    val (code, out, err) = cli("services", "get", "cart")
    assertEquals(code, 0, err)
    assert(out.contains("name"), out)
    assert(out.contains("project"), out)
    assert(out.contains("checkout"), out)
  }

  test("a service that does not exist reports the server's 404 message") {
    val (code, _, err) = cli("services", "get", "ghost")
    assertEquals(code, 1)
    assert(err.contains("no such service 'ghost'"), err)
  }

  test("pause, resume and restart round-trip through the CLI") {
    val (pauseCode, pauseOut, pauseErr) = cli("services", "pause", "cart")
    assertEquals(pauseCode, 0, pauseErr)
    assert(pauseOut.contains("Paused"), pauseOut)

    val (restartCode, _, restartErr) = cli("services", "restart", "cart")
    assertEquals(restartCode, 1)
    assert(restartErr.contains("paused; resume it first"), restartErr)

    assertEquals(cli("services", "resume", "cart")._1, 0)
    assertEquals(cli("services", "restart", "cart")._1, 0)
  }

  // --- Exposure (feature 005): contracts/expose-api.md, through the real CLI.

  test("expose prints the URL; get and list show it; apply leaves it; unexpose clears it") {
    val (code, out, err) = cli("services", "expose", "cart")
    assertEquals(code, 0, err)
    assertEquals(out.trim, "https://cart-checkout.example.test")

    val (_, got, _) = cli("services", "get", "cart")
    assert(got.contains("hostname    https://cart-checkout.example.test"), got)

    val _ = eventually("the listing shows the hostname") {
      val (_, list, _) = cli("services", "list")
      Option.when(list.contains("https://cart-checkout.example.test"))(list)
    }

    val (applied, appliedOut, _) =
      cli(
        Some("""{"name":"cart","service":{"image":"cart:9.0"}}"""),
        "services",
        "apply",
        "-f",
        "-"
      )
    assertEquals(applied, 0)
    assert(appliedOut.contains("https://cart-checkout.example.test"), appliedOut)

    val (unexposed, unexposedOut, _) = cli("services", "unexpose", "cart")
    assertEquals(unexposed, 0)
    assert(unexposedOut.contains("not exposed"), unexposedOut)
    assert(!unexposedOut.contains("https://"), unexposedOut)
  }

  test("a service that serves no HTTP cannot be exposed, and the CLI says why") {
    val quiet = """{"name":"quiet","service":{"image":"registry.k8s.io/pause:3.9","http":false}}"""
    assertEquals(cli(Some(quiet), "services", "apply", "-f", "-")._1, 0)
    val (code, _, err) = cli("services", "expose", "quiet")
    assertEquals(code, 1)
    assert(err.contains("serves no HTTP"), err)
    assertEquals(cli("services", "delete", "quiet")._1, 0)
  }

  test("a hostname another exposed service holds is refused, naming the holder") {
    for id <- Vector("c", "b-c") do
      assertEquals(cli("projects", "create", id, "--name", id, "-O", "acme")._1, 0)
    assertEquals(
      cli(
        Some("""{"name":"a-b","service":{"image":"x:1"}}"""),
        "services",
        "apply",
        "-f",
        "-",
        "-p",
        "c"
      )._1,
      0
    )
    assertEquals(
      cli(
        Some("""{"name":"a","service":{"image":"x:1"}}"""),
        "services",
        "apply",
        "-f",
        "-",
        "-p",
        "b-c"
      )._1,
      0
    )
    assertEquals(cli("services", "expose", "a-b", "-p", "c")._1, 0)
    val _ = eventually("the holder is in the view") {
      val (_, list, _) = cli("services", "list", "-p", "c")
      Option.when(list.contains("a-b-c.example.test"))(list)
    }

    val (code, _, err) = cli("services", "expose", "a", "-p", "b-c")
    assertEquals(code, 1)
    assert(err.contains("already exposed by service 'a-b' in project 'c'"), err)

    assertEquals(cli("services", "delete", "a-b", "-p", "c")._1, 0)
    assertEquals(cli("services", "delete", "a", "-p", "b-c")._1, 0)
    for id <- Vector("c", "b-c") do
      val _ = eventually(s"project $id is empty") {
        val (_, list, _) = cli("services", "list", "-p", id)
        Option.when(list.trim == "no results")(list)
      }
      assertEquals(cli("projects", "delete", id)._1, 0)
  }

  test("a project with services cannot be deleted, and says how many") {
    val (code, _, err) = cli("projects", "delete", "checkout")
    assertEquals(code, 1)
    assert(err.contains("still has 2 service"), err)
  }

  test("deleting the services then the project then the organization") {
    assertEquals(cli("services", "delete", "cart")._1, 0)
    assertEquals(cli("services", "delete", "basket")._1, 0)

    val _ = eventually("both services leave the view") {
      val (code, out, _) = cli("services", "list")
      Option.when(code == 0 && out.trim == "no results")(out)
    }

    val _ = eventually("checkout reports no services") {
      val (code, out, _) = cli("projects", "get", "checkout", "-o", "json")
      Option.when(code == 0 && out.contains("\"services\":0"))(out)
    }
    assertEquals(cli("projects", "delete", "checkout")._1, 0)

    val _ = eventually("acme reports no projects") {
      val (code, out, _) = cli("organizations", "get", "acme", "-o", "json")
      Option.when(code == 0 && out.contains("\"projects\":0"))(out)
    }
    val (code, out, err) = cli("organizations", "delete", "acme")
    assertEquals(code, 0, err)
    assert(out.contains("deleted"), out)
  }

  test("config unset clears the project again") {
    assertEquals(cli("config", "unset", "project")._1, 0)
    assertEquals(Settings.load().project, None)

    val (code, _, err) = cli("services", "list")
    assertEquals(code, 1)
    assert(err.contains("no project selected"), err)
  }
