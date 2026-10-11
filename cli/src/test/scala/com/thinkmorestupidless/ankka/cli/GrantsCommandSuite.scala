package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * `ankka projects grants` (feature 040): what each command sends, what it prints, and what it
 * refuses before sending anything — against a stand-in control plane that records each request.
 */
final class GrantsCommandSuite extends FunSuite:

  private final case class Seen(method: String, path: String, body: String)

  private val seen                     = mutable.ArrayBuffer.empty[Seen]
  private var server: HttpServer       = null
  private var config: Path             = null
  private var previous: Option[String] = None

  private val deposits =
    GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")
  private val pending = GrantDetail(
    "p1",
    Grantee.Machine("affiliates", "network"),
    GrantTarget.topic("affiliates.attribution", "consume"),
    GrantState.Pending,
    "pending",
    GrantAct(Some("Ada"))
  )
  private val accepted =
    GrantDetail(
      "a1",
      Grantee.Service("payments", "merchant"),
      deposits,
      GrantState.Accepted,
      "in effect",
      GrantAct(Some("Ada"))
    )

  override def beforeAll(): Unit =
    config = Files.createTempFile("ankka-cli-grants", ".json")
    Files.writeString(config, "{}")
    previous = sys.props.get("ankka.config")
    sys.props.put("ankka.config", config.toString): Unit
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val path = exchange.getRequestURI.getRawPath
        seen.synchronized(seen += Seen(exchange.getRequestMethod, path, body))
        val answer: Option[String] = (exchange.getRequestMethod, path) match
          case ("GET", "/projects/spinvibe/grants") =>
            Some(writeToString(Vector(pending, accepted)))
          case ("GET", "/projects/payments/grants/received") =>
            Some(
              writeToString(
                Vector(
                  ReceivedGrantDetail(
                    "a1",
                    "spinvibe",
                    "eitheror",
                    accepted.grantee,
                    GrantTarget.topic("casino.players", "consume"),
                    GrantState.Accepted,
                    Vector(GrantChangeRecord(GrantChange.Made, Some("Ada"))),
                    Some(TopicSettings(3, compacted = true, Some("7 days")))
                  )
                )
              )
            )
          case ("GET", "/organizations/affiliates/grants") =>
            Some(
              writeToString(
                Vector(
                  ReceivedGrantDetail(
                    "p1",
                    "spinvibe",
                    "eitheror",
                    pending.grantee,
                    pending.target,
                    GrantState.Pending,
                    Vector(GrantChangeRecord(GrantChange.Offered, Some("Ada")))
                  )
                )
              )
            )
          case ("POST", "/projects/spinvibe/grants") => Some(writeToString(pending))
          case _                                     => None
        answer match
          case Some(json) =>
            val bytes = json.getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
          case None => exchange.sendResponseHeaders(204, -1)
        exchange.close()
    )
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)
    Files.deleteIfExists(config): Unit
    previous match
      case Some(p) => sys.props.put("ankka.config", p): Unit
      case None    => sys.props.remove("ankka.config"): Unit

  override def beforeEach(context: BeforeEach): Unit = seen.synchronized(seen.clear())

  private def run(project: String, args: String*): (Int, String, String) =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    val code = Main.run(
      Vector("projects", "grants") ++ args ++ Vector("--url", url, "--token", "t", "-p", project),
      PrintStream(out),
      PrintStream(err)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def posted = seen.toVector.filter(_.method == "POST")

  test(
    "make sends one grant: the grantee as typed and each kind of target as the contract shows it"
  ) {
    for (words, json) <- Vector(
        Vector("route", "wallet", "post", "/v1/wallets/{player}/{currency}/deposits") ->
          """"target":{"kind":"route","service":"wallet","method":"POST","path":"/v1/wallets/{player}/{currency}/deposits"}""",
        Vector("method", "wallet", "WalletService/Deposit") ->
          """"target":{"kind":"method","service":"wallet","method":"WalletService/Deposit"}""",
        Vector("topic", "casino.players", "consume", "--decrypt") ->
          """"target":{"kind":"topic","topic":"casino.players","right":"consume","decrypt":true}""",
        Vector("erasure") -> """"target":{"kind":"erasure"}"""
      )
    do
      seen.synchronized(seen.clear())
      val (code, _, err) = run("spinvibe", ("make" +: "service:payments/merchant" +: words)*)
      assertEquals(code, 0, err)
      val request = posted.loneElement
      assertEquals(request.path, "/projects/spinvibe/grants")
      assert(request.body.contains("\"grantee\":\"service:payments/merchant\""), request.body)
      assert(request.body.contains(json), request.body)
  }

  test("make tells a person when a grant waits for the grantee's organization") {
    val (_, out, _) = run(
      "spinvibe",
      "make",
      "machine:affiliates/network",
      "topic",
      "affiliates.attribution",
      "consume"
    )
    assert(out.contains("grant p1") && out.contains("pending until an owner"), out)
  }

  test("a grant the rules refuse is refused before anything is sent") {
    for args <- Vector(
        Vector("make", "everyone", "erasure"),
        Vector("make", "service:payments/merchant", "route", "wallet", "FETCH", "/x"),
        Vector(
          "make",
          "service:payments/merchant",
          "topic",
          "casino.players",
          "produce",
          "--decrypt"
        ),
        Vector("make", "service:payments/merchant", "everything")
      )
    do
      seen.synchronized(seen.clear())
      val (code, _, err) = run("spinvibe", args*)
      assertNotEquals(code, 0, args.toString)
      assert(err.nonEmpty, args.toString)
      assertEquals(seen.toVector, Vector.empty, args.toString)
  }

  test("withdraw ends a pending grant and revoke an accepted one; each refuses the other's") {
    assertEquals(run("spinvibe", "withdraw", "p1")._1, 0)
    assertEquals(run("spinvibe", "revoke", "a1")._1, 0)
    assertEquals(
      seen.toVector.filter(_.method == "DELETE").map(_.path),
      Vector("/projects/spinvibe/grants/p1", "/projects/spinvibe/grants/a1")
    )
    seen.synchronized(seen.clear())
    val (code, _, err) = run("spinvibe", "withdraw", "a1")
    assertNotEquals(code, 0)
    assert(err.contains("is accepted"), err)
    assertEquals(seen.toVector.filter(_.method == "DELETE"), Vector.empty)
  }

  test("list shows each grant's state and effect; received shows who granted what and the topic") {
    val (_, out, _) = run("spinvibe", "list")
    assert(out.contains("machine:affiliates/network") && out.contains("pending"), out)
    assert(out.contains("in effect") && out.contains("route wallet POST"), out)
    val (_, received, _) = run("payments", "received")
    assert(
      received.contains("spinvibe (eitheror)") && received.contains(
        "3 partitions, compacted, kept 7 days"
      ),
      received
    )
    assert(received.contains("made by Ada"), received)
  }

  private def organizations(args: String*): (Int, String, String) =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    val code = Main.run(
      Vector("organizations", "grants") ++ args ++ Vector("--url", url, "--token", "t"),
      PrintStream(out),
      PrintStream(err)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  test(
    "organizations grants list shows what the organization is offered; each answer calls its route"
  ) {
    val (code, out, err) = organizations("list", "affiliates")
    assertEquals(code, 0, err)
    assert(out.contains("machine:affiliates/network") && out.contains("pending"), out)
    assert(out.contains("spinvibe (eitheror)"), out)
    for verb <- Vector("accept", "decline", "relinquish") do
      val (answered, said, problem) = organizations(verb, "affiliates", "p1")
      assertEquals(answered, 0, problem)
      assert(said.contains("p1"), said)
    assertEquals(
      posted.map(_.path),
      Vector(
        "/organizations/affiliates/grants/p1/accept",
        "/organizations/affiliates/grants/p1/decline",
        "/organizations/affiliates/grants/p1/relinquish"
      )
    )
  }

  extension [A](values: Vector[A])
    private def loneElement: A =
      assertEquals(values.size, 1, values.toString)
      values.head
