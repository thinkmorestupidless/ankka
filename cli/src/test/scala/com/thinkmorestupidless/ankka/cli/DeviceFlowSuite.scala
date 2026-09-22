package com.thinkmorestupidless.ankka.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** `ankka login`, `logout` and the silent refresh, against a scripted issuer (research R6). */
class DeviceFlowSuite extends munit.FunSuite:

  private var issuer: FakeIssuer = null
  private var dir: Path          = null

  // A private directory per test: the credentials file sits beside the config, so a shared temp
  // directory would let one test's login leak into the next — and the override must still be in
  // place while cleaning up, or `Credentials.path` would point at the developer's own home.
  override def beforeEach(context: BeforeEach): Unit =
    issuer = FakeIssuer()
    dir = Files.createTempDirectory("ankka-login")
    sys.props("ankka.config") = dir.resolve("config.json").toString

  override def afterEach(context: AfterEach): Unit =
    issuer.stop()
    Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(p => Files.deleteIfExists(p): Unit)
    sys.props.remove("ankka.config"): Unit

  private def cli(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val outs = PrintStream(out, true, StandardCharsets.UTF_8)
    val errs = PrintStream(err, true, StandardCharsets.UTF_8)
    val code = Main.run(args, outs, errs)
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def settings = Settings(url = issuer.url)
  private def flow     = DeviceFlow(settings, sleep = _ => ())

  test("discovery names the device, token and revocation endpoints") {
    val d = flow.discover(issuer.issuer)
    assertEquals(d.deviceAuthorizationEndpoint, s"${issuer.issuer}/device")
    assertEquals(d.tokenEndpoint, s"${issuer.issuer}/token")
    assertEquals(d.revocationEndpoint, Some(s"${issuer.issuer}/revoke"))
  }

  test("the device grant asks for offline access and polls through authorization_pending") {
    issuer.pendingPolls = 3
    val d      = flow.discover(issuer.issuer)
    val device = flow.start(d, "ankka-cli")
    assertEquals(device.userCode, "WDJB-MJHT")
    val tokens = flow.poll(d, "ankka-cli", device)
    assertEquals(tokens.accessToken, "access-1")
    assertEquals(tokens.refreshToken, Some("refresh-1"))
    assertEquals(issuer.polls.get(), 4)
  }

  test("slow_down lengthens the interval rather than failing") {
    issuer.slowDownFirst = true
    var slept = Vector.empty[Long]
    val f     = DeviceFlow(settings, sleep = ms => slept :+= ms)
    val d     = f.discover(issuer.issuer)
    val _     = f.poll(d, "ankka-cli", f.start(d, "ankka-cli"))
    assertEquals(slept, Vector(1000L, 6000L), "one second, then one plus five")
  }

  test("an expired code fails with the tabled message") {
    issuer.expired = true
    val d       = flow.discover(issuer.issuer)
    val failure = intercept[ApiError](flow.poll(d, "ankka-cli", flow.start(d, "ankka-cli")))
    assert(failure.detail.contains("code expired"), failure.detail)
  }

  test("ankka login runs whole: prints the code, saves the login, and reports who") {
    issuer.pendingPolls = 1
    val (code, out, err) = cli("login", "--no-browser", "--url", issuer.url)
    assertEquals(code, 0, err)
    assert(out.contains("WDJB-MJHT"), out)
    assert(out.contains(s"${issuer.issuer}/device/verify"), out)
    assert(out.contains(s"logged in to ${issuer.url} as alice@example.test"), out)
    val saved = Credentials.get(issuer.url).getOrElse(fail("no login saved"))
    assertEquals(saved.refreshToken, "refresh-1")
    assertEquals(saved.clientId, "ankka-cli")
    assert(!out.contains("access-1") && !out.contains("refresh-1"), "no credential is printed")
  }

  test("a saved login is presented, renewed silently when stale, and refused loudly when dead") {
    issuer.pendingPolls = 0
    assertEquals(cli("login", "--no-browser", "--url", issuer.url)._1, 0)
    assertEquals(cli("organizations", "list", "--url", issuer.url)._1, 0)
    assertEquals(issuer.refreshes.get(), 0, "a fresh access token needs no refresh")

    // Age the saved login: the next command must refresh, and go on with the new token.
    val stale = Credentials.get(issuer.url).get.copy(expiresAt = 0L)
    Credentials.put(issuer.url, stale): Unit
    issuer.accessToken = "access-2"
    assertEquals(cli("organizations", "list", "--url", issuer.url)._1, 0)
    assertEquals(issuer.refreshes.get(), 1)
    assertEquals(
      Credentials.get(issuer.url).get.refreshToken,
      "refresh-2",
      "the rotated refresh token is kept"
    )

    issuer.refuseRefresh = true
    Credentials.put(issuer.url, Credentials.get(issuer.url).get.copy(expiresAt = 0L)): Unit
    val (code, _, err) = cli("organizations", "list", "--url", issuer.url)
    assertEquals(code, 1)
    assert(err.contains("rejected the login") && err.contains("run 'ankka login'"), err)
  }

  test("logout revokes at the issuer and forgets the login; --all forgets every one") {
    assertEquals(cli("login", "--no-browser", "--url", issuer.url)._1, 0)
    val (code, out, _) = cli("logout", "--url", issuer.url)
    assertEquals(code, 0)
    assert(out.contains("logged out"), out)
    assertEquals(issuer.revocations.get(), 1)
    assertEquals(Credentials.get(issuer.url), None)
    val (again, _, err) = cli("organizations", "list", "--url", issuer.url)
    assertEquals(again, 1)
    assert(err.contains("not logged in") && err.contains("run 'ankka login'"), err)

    Credentials.put("http://one", Login("i", "c", "r", "a", 0L)): Unit
    Credentials.put("http://two", Login("i", "c", "r", "a", 0L)): Unit
    assertEquals(cli("logout", "--all")._1, 0)
    assertEquals(Credentials.load(), Map.empty)
  }

  test("an explicit token is presented as given: no login read, none written") {
    val (code, _, err) = cli("organizations", "list", "--url", issuer.url, "--token", "access-1")
    assertEquals(code, 0, err)
    assertEquals(issuer.listBearers.get(0), "Bearer access-1")
    assert(!Files.exists(Credentials.path), "no credentials file was created")
    val (rejected, _, err2) = cli("organizations", "list", "--url", issuer.url, "--token", "wrong")
    assertEquals(rejected, 1)
    assert(err2.contains("the token was rejected"), err2)
  }
