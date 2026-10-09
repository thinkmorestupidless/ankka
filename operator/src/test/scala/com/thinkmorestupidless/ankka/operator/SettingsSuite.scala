package com.thinkmorestupidless.ankka.operator

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

  // Feature 039: the store new buckets are made in.

  private val gcs = Vector(
    "ankka.operator.object-store.backend" -> "gcs",
    "ankka.operator.object-store.prefix"  -> "ankka",
    "ankka.operator.cloud-provider"       -> "gcp"
  )

  test("an installation with Garage makes new buckets in Garage, and one with no store in none") {
    assertEquals(Settings.fromEnvironment().objectStoreBackend, None)
    withProperties(store*) {
      assertEquals(Settings.fromEnvironment().objectStoreBackend, Some(ObjectStoreBackend.Garage))
      assertEquals(Settings.fromEnvironment().gcs, None)
    }
  }

  test(
    "Google Cloud Storage is read with its prefix, a soft-delete window of 7 days, and Google's address"
  ) {
    withProperties(gcs*) {
      val settings = Settings.fromEnvironment()
      assertEquals(settings.objectStoreBackend, Some(ObjectStoreBackend.Gcs))
      assertEquals(
        settings.gcs,
        Some(GcsSettings("ankka", softDeleteDays = 7, "https://storage.googleapis.com"))
      )
      assertEquals(settings.objectStore, None)
    }
  }

  test(
    "Google Cloud Storage beside Garage keeps both: the store a move goes from, and the one it goes to"
  ) {
    withProperties((store ++ gcs)*) {
      val settings = Settings.fromEnvironment()
      assertEquals(settings.objectStoreBackend, Some(ObjectStoreBackend.Gcs))
      assert(settings.objectStore.isDefined)
      assert(settings.gcs.isDefined)
    }
  }

  test("the soft-delete window and the address of Google Cloud Storage are read when given") {
    withProperties(
      (gcs ++ Vector(
        "ankka.operator.object-store.soft-delete-days" -> "30",
        "ankka.operator.object-store.gcs-endpoint"     -> "http://garage.garage-system.svc:3900"
      ))*
    ) {
      assertEquals(
        Settings.fromEnvironment().gcs,
        Some(GcsSettings("ankka", 30, "http://garage.garage-system.svc:3900"))
      )
    }
  }

  test("Google Cloud Storage with no cloud provider, or none, fails naming the setting") {
    for provider <- Vector(None, Some("none")) do
      withProperties(
        (gcs.filterNot(_._1 == "ankka.operator.cloud-provider") ++
          provider.map("ankka.operator.cloud-provider" -> _))*
      ) {
        val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
        assert(e.getMessage.contains("ANKKA_CLOUD_PROVIDER"), e.getMessage)
      }
  }

  test("Google Cloud Storage with no prefix fails naming the setting") {
    withProperties(gcs.filterNot(_._1.endsWith("prefix"))*) {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_OBJECT_STORE_PREFIX"), e.getMessage)
    }
  }

  test("a soft-delete window outside 7 to 90 days fails naming the setting and the value") {
    for bad <- Vector("6", "91", "seven") do
      withProperties((gcs :+ ("ankka.operator.object-store.soft-delete-days" -> bad))*) {
        val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
        assert(e.getMessage.contains("ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS"), e.getMessage)
        assert(e.getMessage.contains(bad), e.getMessage)
      }
  }

  test("a backend that is neither garage nor gcs fails naming it, and garage needs a store") {
    withProperties("ankka.operator.object-store.backend" -> "s3") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_OBJECT_STORE_BACKEND"), e.getMessage)
      assert(e.getMessage.contains("s3"), e.getMessage)
    }
    withProperties("ankka.operator.object-store.backend" -> "garage") {
      val e = intercept[IllegalArgumentException](Settings.fromEnvironment())
      assert(e.getMessage.contains("ANKKA_OBJECT_STORE_ADMIN_URL"), e.getMessage)
    }
  }

  test(
    "the mover's image defaults to the locally built tag, as the sidecar's does, and is read when given"
  ) {
    assertEquals(Settings.fromEnvironment().storageMoverImage, "ankka-storage-mover:latest")
    withProperties(
      "ankka.operator.storage-mover-image" -> "ghcr.io/example/ankka-storage-mover:1.2.3"
    ) {
      assertEquals(
        Settings.fromEnvironment().storageMoverImage,
        "ghcr.io/example/ankka-storage-mover:1.2.3"
      )
    }
  }
