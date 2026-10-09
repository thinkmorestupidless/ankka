package com.thinkmorestupidless.ankka.operator

import scala.concurrent.duration.DurationInt

/**
 * The operator's settings as read from system properties, which stand in for the environment the
 * manifests set: an environment variable cannot be set in-process, which is why the properties come
 * first.
 */
class SettingsSuite extends munit.FunSuite:

  private def withProperties[A](properties: (String, String)*)(body: => A): A =
    val previous = properties.map((key, _) => key -> sys.props.get(key))
    properties.foreach((key, value) => sys.props(key) = value)
    try body
    finally
      previous.foreach {
        case (key, Some(value)) => sys.props(key) = value
        case (key, None)        => sys.props -= key: Unit
      }

  test("the proxy image defaults to the locally built tag, as the sidecar's does") {
    assertEquals(Settings.fromEnvironment().proxyImage, "ankka-proxy:latest")
    assertEquals(Settings.default.proxyImage, "ankka-proxy:latest")
  }

  test("the proxy image is read from ankka.operator.proxy-image") {
    withProperties("ankka.operator.proxy-image" -> "ghcr.io/example/ankka-proxy:1.2.3") {
      assertEquals(Settings.fromEnvironment().proxyImage, "ghcr.io/example/ankka-proxy:1.2.3")
    }
  }

  test("the HTTPS port defaults to 443") {
    assertEquals(Settings.fromEnvironment().httpsPort, 443)
    assertEquals(Settings.default.httpsPort, 443)
  }

  test("the HTTPS port is read from ankka.operator.https-port") {
    withProperties("ankka.operator.https-port" -> "8443") {
      assertEquals(Settings.fromEnvironment().httpsPort, 8443)
    }
  }

  test("an HTTPS port that is not a number falls back to the default, as every number here does") {
    withProperties("ankka.operator.https-port" -> "eight") {
      assertEquals(Settings.fromEnvironment().httpsPort, 443)
    }
  }

  test("the sidecar image is read as it was") {
    withProperties("ankka.operator.sidecar-image" -> "ankka-sidecar:9.9.9") {
      assertEquals(Settings.fromEnvironment().sidecarImage, "ankka-sidecar:9.9.9")
    }
  }

  private val broker = Seq(
    "ankka.operator.broker-bootstrap" -> "ankka-kafka-bootstrap.ankka-broker.svc:9093",
    "ankka.operator.broker-namespace" -> "ankka-broker",
    "ankka.operator.broker-cluster"   -> "ankka"
  )

  test("an installation with no broker settings has no broker") {
    assertEquals(Settings.fromEnvironment().broker, None)
    assertEquals(Settings.default.broker, None)
  }

  test("all three broker settings are the installation's broker") {
    withProperties(broker*) {
      assertEquals(
        Settings.fromEnvironment().broker,
        Some(BrokerSettings("ankka-kafka-bootstrap.ankka-broker.svc:9093", "ankka-broker", "ankka"))
      )
    }
  }

  test("some broker settings and not others refuse to start, naming what is missing") {
    withProperties(broker.take(1)*) {
      val refused = intercept[IllegalStateException](Settings.fromEnvironment())
      assert(refused.getMessage.contains("ANKKA_BROKER_NAMESPACE"), refused.getMessage)
      assert(refused.getMessage.contains("ANKKA_BROKER_CLUSTER"), refused.getMessage)
      assert(!refused.getMessage.contains("ANKKA_BROKER_BOOTSTRAP"), refused.getMessage)
    }
  }
  // The object store (feature 034). With no administration URL the installation has none; with one,
  // the other four must be there too, or the operator would provision buckets it cannot describe.

  private val store = Vector(
    "ankka.operator.object-store.admin-url" -> "http://garage.garage-system.svc.cluster.local:3903",
    "ankka.operator.object-store.admin-token" -> "a-token",
    "ankka.operator.object-store.endpoint" -> "http://garage.garage-system.svc.cluster.local:3900",
    "ankka.operator.object-store.region"   -> "garage",
    "ankka.operator.object-store.service"  -> "garage-system/garage:3900"
  )

  test("with no administration URL the installation has no object store") {
    assertEquals(Settings.fromEnvironment().objectStore, None)
    assertEquals(Settings.default.objectStore, None)
  }

  test("the object store is read whole from its five settings") {
    withProperties(store*) {
      assertEquals(
        Settings.fromEnvironment().objectStore,
        Some(
          ObjectStoreSettings(
            adminUrl = "http://garage.garage-system.svc.cluster.local:3903",
            adminToken = "a-token",
            endpoint = "http://garage.garage-system.svc.cluster.local:3900",
            region = "garage",
            service = ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
          )
        )
      )
    }
  }

  test("an object store missing a setting fails, naming the variable") {
    withProperties(store.filterNot(_._1.endsWith("admin-token"))*) {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_OBJECT_STORE_ADMIN_TOKEN"), e.getMessage)
    }
  }

  test("the store's service is namespace/name:port, and anything else fails naming the variable") {
    for bad <- Vector("garage:3900", "garage-system/garage", "garage-system/garage:port") do
      withProperties(
        (store.filterNot(_._1.endsWith("service")) :+
          ("ankka.operator.object-store.service" -> bad))*
      ) {
        val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
        assert(e.getMessage.contains("ANKKA_OBJECT_STORE_SERVICE"), e.getMessage)
        assert(e.getMessage.contains(bad), e.getMessage)
      }
  }

  // The installation's cloud (feature 044).

  private val gcp = Seq(
    "ankka.operator.cloud-provider" -> "gcp",
    "ankka.operator.cloud-account"  -> "my-account",
    "ankka.operator.cloud-location" -> "europe-west2"
  )

  test("an installation that names no cloud provider has no cloud") {
    assertEquals(Settings.fromEnvironment().cloud, None)
  }

  test("a cloud provider of 'none' is no cloud") {
    withProperties("ankka.operator.cloud-provider" -> "none") {
      assertEquals(Settings.fromEnvironment().cloud, None)
    }
  }

  test("a known provider with its account and location is the installation's cloud") {
    withProperties(gcp*) {
      assertEquals(
        Settings.fromEnvironment().cloud,
        Some(CloudSettings("gcp", "my-account", "europe-west2", None, 2.minutes, 1.hour))
      )
    }
  }

  test("the wrapping key and the two durations are read when set") {
    withProperties(
      (gcp ++ Seq(
        "ankka.operator.cloud-kms-key"               -> "keys/ankka",
        "ankka.operator.cloud-acknowledgement-bound" -> "30s",
        "ankka.operator.cloud-rotation-grace"        -> "20s"
      ))*
    ) {
      val cloud = Settings.fromEnvironment().cloud.getOrElse(fail("no cloud"))
      assertEquals(cloud.kmsKey, Some("keys/ankka"))
      assertEquals(cloud.acknowledgementBound, 30.seconds)
      assertEquals(cloud.rotationGrace, 20.seconds)
    }
  }

  test("a provider named without its account refuses to start, naming the variable") {
    withProperties(gcp.filterNot(_._1.endsWith("account"))*) {
      val e = intercept[IllegalStateException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_CLOUD_ACCOUNT"), e.getMessage)
    }
  }

  test("a provider named without its location refuses to start, naming the variable") {
    withProperties(gcp.filterNot(_._1.endsWith("location"))*) {
      val e = intercept[IllegalStateException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_CLOUD_LOCATION"), e.getMessage)
    }
  }

  test("a provider the platform does not know refuses to start, naming the known ones") {
    withProperties("ankka.operator.cloud-provider" -> "aws") {
      val e = intercept[IllegalStateException](Settings.fromEnvironment())
      assert(e.getMessage.contains("'aws'"), e.getMessage)
      assert(e.getMessage.contains("gcp"), e.getMessage)
    }
  }

  test("a malformed duration refuses to start rather than becoming the default") {
    withProperties((gcp :+ ("ankka.operator.cloud-rotation-grace" -> "an hour"))*) {
      val e = intercept[IllegalStateException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_CLOUD_ROTATION_GRACE"), e.getMessage)
    }
  }

  test("durations are seconds, minutes or hours, and a bare number is seconds") {
    assertEquals(CloudSettings.duration("2m", "X"), 2.minutes)
    assertEquals(CloudSettings.duration("1h", "X"), 1.hour)
    assertEquals(CloudSettings.duration("45", "X"), 45.seconds)
  }

  test("a provider for another cloud needs only its name known to the platform") {
    // providers.feature, scenario 3: the platform's side of a second provider is one name.
    val lookup: (String, String) => Option[String] = (property, _) =>
      Map(
        "ankka.operator.cloud-provider" -> "other",
        "ankka.operator.cloud-account"  -> "acct",
        "ankka.operator.cloud-location" -> "somewhere"
      ).get(property)
    intercept[IllegalStateException](CloudSettings.read(lookup))
    assertEquals(
      CloudSettings.read(lookup, known = Set("gcp", "other")).map(_.provider),
      Some("other")
    )
  }
