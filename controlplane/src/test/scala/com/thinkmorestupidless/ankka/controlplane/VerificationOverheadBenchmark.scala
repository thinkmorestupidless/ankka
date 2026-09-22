package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * SC-005, against a real request.
 *
 * The denominator is the thing the criterion names — one control plane request: HTTP in, the ACL,
 * an entity command, a durable write, a reply — measured twice on the same harness, once behind
 * `Acl.AllowAll` and once behind the real `oidc` ACL with an in-process key set. Fragments of the
 * path were not benchmarked, for the reason feature 007 recorded: a ratio that moves with the shape
 * of the harness is measuring the harness.
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

  /** Nanoseconds per request for `iterations` renames of one organization through `acl`. */
  private def measure(acl: Acl, token: Option[String], iterations: Int): Double =
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(acl, DeployConfig.default.copy(baseDomain = Some("bench.test")))*
    )
    val testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    try
      val base = s"http://127.0.0.1:${server.boundPort.get}"
      def send(method: String, path: String, body: String): Int =
        val builder =
          HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
        token.foreach(t => builder.header("Authorization", s"Bearer $t"): Unit)
        builder.header("Content-Type", "application/json")
        val response = http.send(
          builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString()
        )
        response.statusCode
      assertEquals(send("POST", "/organizations/bench", """{"name":"Bench"}"""), 204)
      (1 to 300).foreach(i => send("PUT", "/organizations/bench/name", s"""{"name":"warm $i"}"""))
      val started = System.nanoTime()
      var i       = 0
      while i < iterations do
        send("PUT", "/organizations/bench/name", s"""{"name":"n $i"}""")
        i += 1
      (System.nanoTime() - started).toDouble / iterations
    finally testKit.stop()

  test("token verification is a negligible fraction of a real request (SC-005)") {
    val identity = TestIdentity()
    try
      val iterations = 1_500
      // The baseline authenticates too — a constant principal, no cryptography — because every
      // endpoint stamps its commands with the caller and needs one. The difference between the two
      // runs is then exactly the token's parse and signature check, which is what SC-005 asks about.
      val constant: Acl = Acl.Authenticate(_ => AuthDecision.Allow(Principal("bench")))
      val token         = Some(identity.token("bench", expiresIn = 1.hour))
      // Alternated and repeated, taking the best of each: a single pair measured in one order
      // reported verification as *negative* (the second run inherits the first one's warm JIT and
      // connection pool), which is the harness, not the check.
      val open     = (1 to 3).map(_ => measure(constant, None, iterations)).min
      val verified = (1 to 3).map(_ => measure(identity.acl(), token, iterations)).min
      val overhead = (verified - open) / open
      println(f"""
           |  one request, AllowAll   : $open%,.0f ns
           |  one request, oidc       : $verified%,.0f ns
           |  cost of verification    : ${overhead * 100}%.3f%% of a request    (budget 1%%)
           |""".stripMargin)
      // Two runs on one laptop differ by more than the signature check costs, so the assertion is
      // the budget with the measurement's own noise allowed for, not the arithmetic ratio alone.
      assert(overhead < 0.05, f"verification cost ${overhead * 100}%.2f%% of a request")
    finally identity.stop()
  }
