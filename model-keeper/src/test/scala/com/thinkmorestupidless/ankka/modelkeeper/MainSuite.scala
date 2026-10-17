package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testollama.FakeOllama
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.file.{Files, Path}
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/** The keeper's entry point: what it refuses to start with, and that it serves until stopped. */
class MainSuite extends munit.FunSuite with LogCapturing:

  override def munitTimeout: Duration = 60.seconds

  private val authority = TestPki.root("model-main")

  private def certificate(uri: String): Path =
    authority
      .issue(uris = Seq(uri), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("keeper-tls"))

  private def environment(declaration: Path, ollama: String): Map[String, String] = Map(
    "ANKKA_KEEPER_ADDRESS"     -> "https://models.models-system.svc:8443",
    "ANKKA_KEEPER_OLLAMA"      -> ollama,
    "ANKKA_KEEPER_DECLARATION" -> declaration.toString,
    "ANKKA_KEEPER_PORT"        -> "0",
    "ANKKA_KEEPER_STATUS_PORT" -> "0",
    "ANKKA_KEEPER_PROBE_PORT"  -> "0"
  )

  private def declaration(): Path =
    val file = Files.createTempDirectory("keeper-main").resolve("models.json")
    Files.writeString(file, """{"models":["qwen3:0.6b"]}""")

  private def withDirectory[A](dir: Path)(body: => A): A =
    val before = sys.props.get(Main.ServiceDirectoryProperty)
    sys.props(Main.ServiceDirectoryProperty) = dir.toString
    try body
    finally
      before.fold(sys.props.remove(Main.ServiceDirectoryProperty): Unit)(
        sys.props(Main.ServiceDirectoryProperty) = _
      )

  private def run(env: Map[String, String]): (Int, Vector[String]) =
    val reported = ListBuffer.empty[String]
    val code     = Main.run(Array.empty, env.get, m => reported.synchronized(reported += m): Unit)
    (code, reported.toVector)

  test("the keeper refuses to start without its settings, naming each") {
    val (code, reported) = withDirectory(certificate(Main.Identity))(run(Map.empty))
    assertEquals(code, 1)
    assert(reported.nonEmpty)
    assert(reported.forall(_.startsWith("refusing to start: ")), reported.toString)
    assert(reported.exists(_.contains("ANKKA_KEEPER_ADDRESS")), reported.toString)
  }

  test("the keeper refuses to start without a certificate") {
    val empty = Files.createTempDirectory("keeper-none")
    val (code, reported) =
      withDirectory(empty)(run(environment(declaration(), "http://127.0.0.1:1")))
    assertEquals(code, 1)
    assertEquals(
      reported,
      Vector(s"refusing to start: there is no certificate at $empty (tls.crt)")
    )
  }

  test("the keeper refuses to start with a certificate that is not the model server's") {
    val dir              = certificate("ankka://shop/cart")
    val (code, reported) = withDirectory(dir)(run(environment(declaration(), "http://127.0.0.1:1")))
    assertEquals(code, 1)
    assertEquals(
      reported,
      Vector(
        s"refusing to start: the certificate at $dir names ankka://shop/cart, and the model server's is " +
          Main.Identity
      )
    )
  }

  test("with everything set, the keeper serves until it is stopped") {
    val ollama = FakeOllama.start()
    ollama.offer("qwen3:0.6b")
    val started = new ArrayBlockingQueue[Main.Started](1)
    val result  = new ArrayBlockingQueue[Int](1)
    val dir     = certificate(Main.Identity)
    val env     = environment(declaration(), ollama.url)
    val before  = sys.props.get(Main.ServiceDirectoryProperty)
    sys.props(Main.ServiceDirectoryProperty) = dir.toString
    val thread = Thread.ofVirtual().start { () =>
      result.put(Main.run(Array.empty, env.get, _ => (), s => started.put(s)))
    }
    try
      val running =
        Option(started.poll(30, TimeUnit.SECONDS)).getOrElse(fail("the keeper did not start"))
      val probe  = running.door.ports.probe
      val client = HttpClient.newHttpClient()
      val live = client.send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$probe/live")).build(),
        BodyHandlers.ofString()
      )
      assertEquals(live.statusCode, 200)
      val deadline = System.nanoTime() + 20.seconds.toNanos
      def ready = client
        .send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$probe/ready")).build(),
          BodyHandlers.ofString()
        )
        .statusCode
      while ready != 200 && System.nanoTime() < deadline do Thread.sleep(100)
      assertEquals(ready, 200)
      running.stop()
      assertEquals(Option(result.poll(10, TimeUnit.SECONDS)), Some(0))
    finally
      thread.interrupt()
      ollama.stop()
      before.fold(sys.props.remove(Main.ServiceDirectoryProperty): Unit)(
        sys.props(Main.ServiceDirectoryProperty) = _
      )
  }
