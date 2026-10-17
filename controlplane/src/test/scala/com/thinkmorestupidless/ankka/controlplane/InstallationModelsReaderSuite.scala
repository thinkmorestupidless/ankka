package com.thinkmorestupidless.ankka.controlplane

import com.sun.net.httpserver.{HttpsConfigurator, HttpsParameters, HttpsServer}
import com.thinkmorestupidless.ankka.controlplane.api.{ClusterModels, ModelLine}
import com.thinkmorestupidless.ankka.controlplane.deploy.InstallationModelsReader
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * The control plane's reading of the model server for `GET /installation` (feature 053): through
 * the door, over mutual TLS with its own certificate, requiring the model server's identity; and a
 * cluster that is not ready, saying why, whenever that read fails.
 */
class InstallationModelsReaderSuite extends munit.FunSuite with LogCapturing:

  private val authority = TestPki.root("installation-models")

  private def directory(uri: String): Path =
    authority
      .issue(uris = Seq(uri), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("models-tls"))

  private val controlPlane = directory("ankka://platform/controlplane")

  private val document =
    """{"server":"https://models.models-system.svc:8443","ready":true,"models":[
      |{"name":"qwen3:0.6b","state":"served","digest":"7d4f3a1c9b2e8f60","capabilities":["tools"]},
      |{"name":"no-such-model:1b","state":"failed","detail":"pull model manifest: file does not exist"}],
      |"calls":{"answered":3,"failed":0,"inFlight":0,"waiting":0}}""".stripMargin

  /** A door: mutual TLS, a client certificate required, the document on `/ankka/models`. */
  private def door(identity: String, status: Int = 200, body: String = document)(
      test: String => Unit
  ): Unit =
    val tls    = RotatingTls(directory(identity), 1.minute)
    val server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setHttpsConfigurator(
      new HttpsConfigurator(tls.sslContext):
        override def configure(params: HttpsParameters): Unit =
          val p = getSSLContext.getDefaultSSLParameters
          p.setNeedClientAuth(true)
          params.setSSLParameters(p)
    )
    server.createContext(
      "/ankka/models",
      exchange =>
        val bytes = body.getBytes(UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    try test(s"https://localhost:${server.getAddress.getPort}")
    finally server.stop(0)

  private def read(address: String, dir: Option[Path] = Some(controlPlane)): ClusterModels =
    InstallationModelsReader
      .Door(address, dir, 5.seconds)
      .read()
      .getOrElse(fail("nothing read"))
      .clusters
      .head

  test("the model server's models are read through its door with the control plane's certificate") {
    door("ankka://platform/models") { address =>
      val cluster = read(address)
      assertEquals(cluster.cluster, "home")
      assert(cluster.ready, cluster.toString)
      assertEquals(
        cluster.models,
        Vector(
          ModelLine("qwen3:0.6b", "served", Some("7d4f3a1c9b2e8f60")),
          ModelLine(
            "no-such-model:1b",
            "failed",
            detail = Some("pull model manifest: file does not exist")
          )
        )
      )
    }
  }

  test("a listener that is not the model server is not believed") {
    door("ankka://shop/cart") { address =>
      val cluster = read(address)
      assert(!cluster.ready)
      assertEquals(cluster.models, Vector.empty)
      assert(
        cluster.detail.exists(_.startsWith("the model server is not answering")),
        cluster.toString
      )
    }
  }

  test("a model server that answers an error is a cluster not ready, saying so") {
    door("ankka://platform/models", status = 503, body = "no") { address =>
      assertEquals(read(address).detail, Some("the model server answered 503"))
    }
  }

  test("a control plane with no certificate reads nothing and says why") {
    val cluster = read("https://localhost:1", dir = None)
    assert(!cluster.ready)
    assertEquals(
      cluster.detail,
      Some("the control plane has no service certificate to read the model server with")
    )
  }

  test("an installation without a model server reports none") {
    assertEquals(InstallationModelsReader.fromEnvironment(_ => None).read(), None)
  }
