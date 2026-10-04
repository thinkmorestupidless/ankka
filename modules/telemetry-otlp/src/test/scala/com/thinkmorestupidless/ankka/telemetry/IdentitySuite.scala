package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory

import java.nio.file.Files

/** Who an instance's telemetry says it is from. */
class IdentitySuite extends munit.FunSuite:

  private val endpoint = """ankka.telemetry.endpoint = "http://c:4318""""

  private def settings(extra: String = "") =
    TelemetrySettings
      .from(ConfigFactory.parseString(s"$endpoint\n$extra").withFallback(ConfigFactory.load()))
      .toOption
      .flatten
      .get

  test("on the platform the instance's certificate says which service and project it is") {
    val dir = TestPki
      .root("identity-suite")
      .issue(uris = Seq("ankka://shop/orders"))
      .writeTo(Files.createTempDirectory("identity"))
    val config = ConfigFactory
      .parseString(s"""ankka.tls.service-directory = "$dir"""")
      .withFallback(ConfigFactory.load())
    // What configuration says is not believed over the certificate.
    val identity = Identity.of(config, "ankka", settings("ankka.telemetry.service-name = other"))
    assertEquals((identity.service, identity.project), ("orders", Some("shop")))
  }

  test("without a certificate the configuration says, and failing that the actor system's name") {
    val config = ConfigFactory.load()
    val named = Identity.of(
      config,
      "ankka",
      settings("ankka.telemetry.service-name = orders\nankka.telemetry.project = shop")
    )
    assertEquals((named.service, named.project), ("orders", Some("shop")))
    val unnamed = Identity.of(config, "cart-system", settings())
    assertEquals((unnamed.service, unnamed.project), ("cart-system", None))
  }

  test("the resource carries the service, the project, the instance and the platform's version") {
    val resource = Identity("orders", Some("shop"), "orders-abc").resource.getAttributes
    assertEquals(resource.get(Identity.ServiceName), "orders")
    assertEquals(resource.get(Identity.ServiceNamespace), "shop")
    assertEquals(resource.get(Identity.Project), "shop")
    assertEquals(resource.get(Identity.InstanceId), "orders-abc")
    assertEquals(resource.get(Identity.RuntimeVersion), BuildInfo.version)
  }

  test("the instance is the host name: a pod's name, in a pod") {
    val config = ConfigFactory.load()
    assertEquals(
      Identity.of(config, "ankka", settings()).instance,
      java.net.InetAddress.getLocalHost.getHostName
    )
  }
