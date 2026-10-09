package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{CloudInstallation, Installation}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{CloudConfig, DeployConfig}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/**
 * `GET /installation` (feature 044): the installation's version and cloud for any member, the
 * wrapping key's name for an owner of an organization alone, and nothing without a credential.
 */
class InstallationRouteSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val owner = identity.token("owner", Some("owner@example.test"), expiresIn = 2.hours)
  private lazy val stranger =
    identity.token("stranger", Some("s@example.test"), expiresIn = 2.hours)

  private val cloud = CloudConfig("gcp", "acct", "europe-west2", Some("keys/ankka"))

  private var testKit: AnkkaTestKit = null
  private var withCloud: String     = ""
  private var withoutCloud: String  = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    def server(c: Option[CloudConfig]) =
      HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(
          identity.acl(),
          DeployConfig.default,
          auth = Some(identity.config()),
          cloud = c
        )*
      )
    val a = server(Some(cloud))
    val b = server(None)
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), a, b))
    withCloud = s"http://127.0.0.1:${a.boundPort.getOrElse(fail("no port"))}"
    withoutCloud = s"http://127.0.0.1:${b.boundPort.getOrElse(fail("no port"))}"
    // An organization the owner owns; the stranger is a member of nothing.
    val created = send(withCloud, "POST", "/organizations/acme", Some(owner), """{"name":"Acme"}""")
    assert(created.statusCode / 100 == 2, created.body)

  override def afterAll(): Unit =
    if testKit != null then
      identity.stop()
      testKit.stop()

  private def send(
      base: String,
      method: String,
      path: String,
      token: Option[String],
      body: String = ""
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder(URI.create(base + path))
      .method(
        method,
        if body.isEmpty then HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(body)
      )
      .header("Content-Type", "application/json")
    token.foreach(t => builder.header("Authorization", s"Bearer $t"))
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  private def installation(base: String, token: String): Installation =
    val response = send(base, "GET", "/installation", Some(token))
    assertEquals(response.statusCode, 200, response.body)
    readFromString[Installation](response.body)

  test("without a credential the installation is not told") {
    assertEquals(send(withCloud, "GET", "/installation", None).statusCode, 401)
  }

  test("an installation with no cloud provider says its version and nothing of a cloud") {
    val seen = installation(withoutCloud, stranger)
    assert(seen.platformVersion.nonEmpty)
    assertEquals(seen.cloud, None)
  }

  test("a member who owns no organization sees the provider, account and location, and no key") {
    assertEquals(
      installation(withCloud, stranger).cloud,
      Some(CloudInstallation("gcp", "acct", "europe-west2", None))
    )
  }

  test("an owner of an organization is shown the wrapping key's name") {
    // Who owns what is read from the organizations listing, which follows the journal: wait for
    // the value that changes, then assert the whole answer.
    val deadline = System.nanoTime() + 30.seconds.toNanos
    while installation(withCloud, owner).cloud.flatMap(_.kmsKey).isEmpty &&
      System.nanoTime() < deadline
    do Thread.sleep(250)
    assertEquals(
      installation(withCloud, owner).cloud,
      Some(CloudInstallation("gcp", "acct", "europe-west2", Some("keys/ankka")))
    )
  }

  test("the settings: none is no cloud, a known provider is read whole, an unknown one refuses") {
    def read(provider: String) = CloudConfig.from(
      ConfigFactory
        .parseString(
          s"""ankka.controlplane.cloud { provider = "$provider", account = "a", location = "l", kms-key = "" }"""
        )
        .withFallback(ConfigFactory.load())
    )
    assertEquals(read("none"), None)
    assertEquals(read("gcp"), Some(CloudConfig("gcp", "a", "l", None)))
    val e = intercept[IllegalStateException](read("aws"))
    assert(e.getMessage.contains("gcp"), e.getMessage)
  }

  test("the shipped default is an installation with no cloud provider") {
    assertEquals(CloudConfig.from(ConfigFactory.load()), None)
  }
