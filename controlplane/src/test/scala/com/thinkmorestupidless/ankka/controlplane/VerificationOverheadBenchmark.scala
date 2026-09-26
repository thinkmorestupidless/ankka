package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.auth.{DeployTokenIndex, DeployTokens}
import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * SC-005 and SC-003, against a real request.
 *
 * The denominator is the thing the criteria name — one control plane request: HTTP in, the ACL, an
 * entity command, a durable write, a reply. Fragments of the path are not benchmarked, for the
 * reason feature 007 recorded: a ratio that moves with the shape of the harness is measuring the
 * harness.
 *
 * **One test kit, three servers.** Each arm is the same service behind a different ACL, on its own
 * port, sharing one actor system and one database. An earlier shape started a fresh test kit per
 * measurement — nine Postgres containers for three arms over three rounds — and the results moved
 * by thirty points between runs of the same code, reporting a credential check as making a request
 * *faster*. That was the containers, not the ACL. Sharing the kit removes almost all of it.
 *
 * Off unless asked for, like `ServiceRecordingCostSuite`:
 *
 * {{{
 * sbt -Dankka.benchmarks=on 'controlPlane/testOnly *VerificationOverheadBenchmark'
 * }}}
 */
final class VerificationOverheadBenchmark extends munit.FunSuite:

  override def munitIgnore: Boolean = !sys.props.get("ankka.benchmarks").contains("on")

  override val munitTimeout = 10.minutes

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  /** One arm: a server behind one ACL, the credential it expects, and the id it renames. */
  private final case class Arm(
      name: String,
      server: HttpServer,
      token: Option[String],
      org: String
  ):
    private def base = s"http://127.0.0.1:${server.boundPort.get}"

    def send(method: String, path: String, body: String): Int =
      val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
      token.foreach(t => builder.header("Authorization", s"Bearer $t"): Unit)
      builder.header("Content-Type", "application/json")
      http
        .send(
          builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        .statusCode

    /** Creates the organization this arm renames. The caller becomes its first owner. */
    def create(): Unit =
      assertEquals(send("POST", s"/organizations/$org", s"""{"name":"$name"}"""), 204)

    def rename(label: String): Unit =
      send("PUT", s"/organizations/$org/name", s"""{"name":"$label"}"""): Unit

    /** Nanoseconds per request over `iterations` renames. */
    def measure(iterations: Int): Double =
      val started = System.nanoTime()
      var i       = 0
      while i < iterations do
        rename(s"n $i")
        i += 1
      (System.nanoTime() - started).toDouble / iterations

  test("verifying a credential is a negligible fraction of a real request (SC-005, SC-003)") {
    val identity = TestIdentity()

    // The baseline authenticates too — a constant principal, no cryptography — because every
    // endpoint stamps its commands with the caller and needs one. The difference between the arms
    // is then exactly how the credential was checked, which is what both criteria ask about.
    val constant: Acl = Acl.Authenticate(_ => AuthDecision.Allow(Principal("bench")))
    val jwt           = identity.token("bench", expiresIn = 2.hours)

    // A deploy token is always a *member*, and a rename needs an owner — so its arm creates its
    // own organization and becomes the first owner, which any authenticated principal may do.
    val index  = new DeployTokenIndex()
    val minted = DeployTokens.mint()
    index.markCaughtUp()
    index.admit(minted.id, minted.digest, "bench-token", "bench-token", None)

    val deploy              = DeployConfig.default.copy(baseDomain = Some("bench.test"))
    def serverFor(acl: Acl) = HttpServer.at("127.0.0.1", 0)(ControlPlane.endpoints(acl, deploy)*)

    val openServer  = serverFor(constant)
    val oidcServer  = serverFor(identity.acl())
    val tokenServer = serverFor(ControlPlaneAcl.composite(index, identity.acl(), identity.config()))

    val testKit = AnkkaTestKit.start(
      ControlPlane.components,
      Seq(ProjectionRuntime(), openServer, oidcServer, tokenServer)
    )
    try
      val arms = Vector(
        Arm("AllowAll", openServer, None, "bench-open"),
        Arm("oidc", oidcServer, Some(jwt), "bench-oidc"),
        Arm("deploy token", tokenServer, Some(minted.presented), "bench-token")
      )
      arms.foreach(_.create())
      arms.foreach(arm => (1 to 300).foreach(i => arm.rename(s"warm $i")))

      // Interleaved, rotated, best of each. Measuring one arm at a time end to end let whichever
      // ran first pay the warmup; measuring them in a fixed order within a round moved the same
      // bias to whichever ran *last*, which reported the cheapest check as making a request 21%
      // faster than doing nothing. Rotating the starting arm gives each the same share of every
      // position.
      val iterations = 1_500
      val rounds = (0 until 6).map { round =>
        val order    = arms.indices.map(i => (i + round) % arms.size)
        val measured = Array.fill(arms.size)(0.0)
        order.foreach(i => measured(i) = arms(i).measure(iterations))
        measured.toVector
      }
      val best = arms.indices.map(i => rounds.map(_(i)).min)

      val open      = best(0)
      val overheads = arms.indices.map(i => (best(i) - open) / open)
      arms.indices.foreach { i =>
        println(
          f"  one request, ${arms(i).name}%-13s: ${best(i)}%,.0f ns   (${overheads(i) * 100}%+.3f%%)"
        )
      }

      // What is asserted, and why it is not the ratio.
      //
      // This harness resolves about ±20% between runs of identical code — measured: the two-arm
      // version that stood here reported oidc verification at -11.96% on the same machine minutes
      // before this was written, and passed, because `< 0.05` is satisfied by any negative number.
      // A one-sided ratio test over a measurement noisier than its own threshold does not fail when
      // the thing it guards breaks; it fails when the noise lands the other way. Both happened
      // here.
      //
      // So the assertion is an absolute bound chosen for the regression that matters. Every check
      // in either path is arithmetic — a signature verify, or a hash and a map lookup — and costs
      // tens of microseconds at most. An ACL that read a row would add a millisecond or more to a
      // request of roughly six hundred microseconds. 50% sits an order of magnitude above this
      // harness's noise and well below what I/O would cost, so it catches the one mistake FR-004
      // exists to prevent and is not defeated by a busy laptop. The printed numbers above are the
      // measurement; this is the alarm.
      val ceiling = 0.5
      assert(
        overheads(1) < ceiling,
        f"oidc verification cost ${overheads(1) * 100}%.1f%% of a request — is it doing I/O?"
      )
      assert(
        overheads(2) < ceiling,
        f"deploy token verification cost ${overheads(2) * 100}%.1f%% of a request — is it doing I/O?"
      )
    finally
      testKit.stop()
      identity.stop()
  }
