package com.thinkmorestupidless.ankka.controlplane

import com.github.dockerjava.api.model.{ExposedPort, HostConfig, Ports}
import com.thinkmorestupidless.ankka.controlplane.api.ProofLookup
import com.thinkmorestupidless.ankka.controlplane.api.ProofLookup.Failure
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * The proof lookup against a real resolver: Let's Encrypt's `pebble-challtestsrv`, which serves DNS
 * from records a test sets over HTTP. The lookup is JNDI's, which asks over UDP first, and
 * testcontainers maps TCP only, so the suite binds one free host port for both.
 */
class ProofLookupSuite extends munit.FunSuite:

  private val Image = "ghcr.io/letsencrypt/pebble-challtestsrv:2.10.1"

  private var server: GenericContainer[?] = null
  private var dnsPort                     = 0
  private var management                  = ""
  private val http                        = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    dnsPort = {
      val s = new ServerSocket(0);
      try s.getLocalPort
      finally s.close()
    }
    val c = new GenericContainer(DockerImageName.parse(Image))
    // No challenge servers: only DNS and the management interface this suite sets records through.
    c.withCommand(
      "-dnsserver",
      ":8053",
      "-management",
      ":8055",
      "-defaultIPv4",
      "",
      "-defaultIPv6",
      "",
      "-http01",
      "",
      "-https01",
      "",
      "-tlsalpn01",
      "",
      "-doh",
      ""
    )
    c.withExposedPorts(8055)
    c.withCreateContainerCmdModifier { cmd =>
      val udp      = ExposedPort.udp(8053)
      val tcp      = ExposedPort.tcp(8053)
      val bindings = Option(cmd.getHostConfig).getOrElse(new HostConfig())
      val ports    = Option(bindings.getPortBindings).getOrElse(new Ports())
      ports.bind(udp, Ports.Binding.bindIpAndPort("127.0.0.1", dnsPort))
      ports.bind(tcp, Ports.Binding.bindIpAndPort("127.0.0.1", dnsPort))
      cmd.withExposedPorts(
        (Option(cmd.getExposedPorts).map(_.toList).getOrElse(Nil) ++ List(udp, tcp))*
      )
      cmd.withHostConfig(bindings.withPortBindings(ports))
      ()
    }
    c.waitingFor(Wait.forListeningPorts(8055))
    c.start()
    server = c
    management = s"http://${c.getHost}:${c.getMappedPort(8055)}"

  override def afterAll(): Unit = if server != null then server.stop()

  private def post(path: String, body: String): Unit =
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(management + path))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body())

  private def lookup = ProofLookup.jndi(Some(s"127.0.0.1:$dnsPort"))

  test("a proof record is read back as the project's value") {
    post("/set-txt", """{"host":"_ankka.app.example.com.","value":"ankka-project=checkout"}""")
    assertEquals(lookup.txt("_ankka.app.example.com"), Right(Vector("ankka-project=checkout")))
    assertEquals(lookup.proves("app.example.com", "checkout"), Right(true))
    assertEquals(lookup.proves("app.example.com", "billing"), Right(false))
  }

  test("a name with no record is NoRecord, not a failure to look it up") {
    assertEquals(lookup.txt("_ankka.nothing.example.com"), Left(Failure.NoRecord))
    assertEquals(lookup.proves("nothing.example.com", "checkout"), Left(Failure.NoRecord))
  }

  test("a resolver nothing answers at is Unreachable, within the lookup's six seconds") {
    val closed = {
      val s = new ServerSocket(0);
      try s.getLocalPort
      finally s.close()
    }
    val started = System.nanoTime()
    val result  = ProofLookup.jndi(Some(s"127.0.0.1:$closed")).txt("_ankka.app.example.com")
    val seconds = (System.nanoTime() - started) / 1e9
    assert(
      result.isLeft && result.left.exists(_.isInstanceOf[Failure.Unreachable]),
      result.toString
    )
    assert(seconds < 10, s"took ${seconds}s")
  }
