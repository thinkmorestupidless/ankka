package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ObjectStoreKind}
import com.typesafe.config.ConfigFactory

/**
 * The control plane's reading of the installation's object store (feature 039): which store new
 * buckets are made in, and what it refuses when that store cannot give what a descriptor asks.
 */
class DeployConfigSuite extends munit.FunSuite:

  private def read(settings: String): DeployConfig =
    DeployConfig.from(
      ConfigFactory
        .parseString(settings)
        .withFallback(ConfigFactory.defaultReference())
        .resolve()
    )

  private val Section = "ankka.controlplane.object-store"

  test("an installation that names no store keeps its buckets in Garage, as before feature 039") {
    val config = read("")
    assertEquals(config.objectStore, ObjectStoreKind.Garage)
    assertEquals(config.objectStorePrefix, None)
    assertEquals(config.softDeleteDays, 7)
    assertEquals(DeployConfig.default.objectStore, ObjectStoreKind.Garage)
  }

  test("Google Cloud Storage is read with its prefix and the cloud provider that makes it") {
    val config = read(
      s"""$Section.backend = gcs
         |$Section.prefix = ankka
         |$Section.soft-delete-days = 30
         |ankka.controlplane.cloud.provider = gcp""".stripMargin
    )
    assertEquals(config.objectStore, ObjectStoreKind.Gcs)
    assertEquals(config.objectStorePrefix, Some("ankka"))
    assertEquals(config.softDeleteDays, 30)
    assertEquals(config.cloudProvider, Some("gcp"))
  }

  test("Google Cloud Storage with no cloud provider, or none, fails naming the setting") {
    for provider <- Vector("", "ankka.controlplane.cloud.provider = none") do
      val e = intercept[IllegalArgumentException](
        read(s"$Section.backend = gcs\n$Section.prefix = ankka\n$provider")
      )
      assert(e.getMessage.contains("ANKKA_CLOUD_PROVIDER"), e.getMessage)
  }

  test("Google Cloud Storage with no prefix fails naming the setting") {
    val e = intercept[IllegalArgumentException](
      read(s"$Section.backend = gcs\nankka.controlplane.cloud.provider = gcp")
    )
    assert(e.getMessage.contains("ANKKA_OBJECT_STORE_PREFIX"), e.getMessage)
  }

  test("a soft-delete window outside 7 to 90 days fails naming the setting") {
    for bad <- Vector(6, 91) do
      val e = intercept[IllegalArgumentException](read(s"$Section.soft-delete-days = $bad"))
      assert(e.getMessage.contains("ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS"), e.getMessage)
  }

  test("a backend that is neither garage nor gcs fails naming it") {
    val e = intercept[IllegalArgumentException](read(s"$Section.backend = s3"))
    assert(e.getMessage.contains("ANKKA_OBJECT_STORE_BACKEND"), e.getMessage)
    assert(e.getMessage.contains("s3"), e.getMessage)
  }
