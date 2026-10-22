package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.sdk.BucketExposure
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.nio.file.Paths
import scala.concurrent.duration.*

class StorageSettingsSuite extends munit.FunSuite:

  private val config = ConfigFactory.load()

  private def read(env: Map[String, String], locally: Boolean = true, backend: String = "") =
    val c =
      if backend.isEmpty then config
      else ConfigFactory.parseString(s"ankka.storage.backend = $backend").withFallback(config)
    StorageSettings.from(c, env, runningLocally = locally)

  private val unnamed = Map(
    "ANKKA_S3_ENDPOINT"        -> "http://garage.garage-system:3900",
    "ANKKA_S3_REGION"          -> "garage",
    "ANKKA_S3_BUCKET"          -> "shop.listings",
    "ANKKA_S3_ACCESS_KEY"      -> "GK1",
    "ANKKA_S3_SECRET_KEY"      -> "s3cr3t",
    "ANKKA_S3_PUBLIC_ENDPOINT" -> "https://storage.example.com"
  )

  test("a service on a developer's machine with no variables keeps its objects in a directory") {
    val s = read(Map.empty).toOption.get
    assertEquals(s.backend, StorageBackend.Directory)
    assertEquals(s.directory, Paths.get(".ankka/storage").toAbsolutePath.normalize)
    assertEquals(s.timeout, 30.seconds)
    assertEquals(s.maxSignedUrl, 7.days)
    assertEquals(s.defaultSignedUrl, 15.minutes)
    assertEquals(s.unnamed, None)
  }

  test("variables in ankka.storage.variables are laid over the environment") {
    val c = ConfigFactory
      .parseString(
        """ankka.storage.variables { ANKKA_S3_ENDPOINT = "http://127.0.0.1:1", ANKKA_S3_REGION = "garage", ANKKA_S3_BUCKET = "t.b" }"""
      )
      .withFallback(config)
    val s = StorageSettings.from(
      c,
      Map("ANKKA_S3_BUCKET" -> "from-the-environment"),
      runningLocally = true
    )
    assertEquals(s.map(_.backend), Right(StorageBackend.S3))
    assertEquals(
      s.toOption.flatMap(_.unnamed).map(t => (t.endpoint.toString, t.bucket)),
      Some(("http://127.0.0.1:1", "t.b"))
    )
  }

  test("a deployed service with no variables has no store") {
    assertEquals(read(Map.empty, locally = false).map(_.backend), Right(StorageBackend.Unavailable))
  }

  test("the unnamed family chooses the s3 store, on a laptop or deployed") {
    for locally <- Vector(true, false) do
      val s = read(unnamed, locally).toOption.get
      assertEquals(s.backend, StorageBackend.S3)
      val t = s.unnamed.get
      assertEquals(t.endpoint, URI.create("http://garage.garage-system:3900"))
      assertEquals(t.region, "garage")
      assertEquals(t.bucket, "shop.listings")
      assertEquals(t.credentials, Some(SigV4.Credentials("GK1", "s3cr3t")))
      assertEquals(t.publicEndpoint, Some(URI.create("https://storage.example.com")))
      assertEquals(s.unnamedExposure, BucketExposure.Signed)
  }

  test("ANKKA_STORAGE_BACKEND wins") {
    assertEquals(read(unnamed, backend = "memory").map(_.backend), Right(StorageBackend.Memory))
    assertEquals(
      read(Map.empty, locally = false, backend = "directory").map(_.backend),
      Right(StorageBackend.Directory)
    )
  }

  test("named buckets the platform made share the unnamed family's endpoint and key") {
    val env = unnamed - "ANKKA_S3_BUCKET" ++ Map(
      "ANKKA_S3_BUCKET_PHOTOS"     -> "shop.listings.photos",
      "ANKKA_S3_EXPOSURE_PHOTOS"   -> "public",
      "ANKKA_S3_PUBLIC_URL_PHOTOS" -> "https://storage.example.com/shop.listings.photos/",
      "ANKKA_S3_BUCKET_OLD_PHOTOS" -> "shop.listings.old-photos",
      "ANKKA_S3_BUCKET_EXPORTS"    -> "shop.listings.exports",
      "ANKKA_S3_EXPOSURE_EXPORTS"  -> "signed"
    )
    val s = read(env, locally = false).toOption.get
    assertEquals(s.backend, StorageBackend.S3)
    assertEquals(s.unnamed, None, "no ANKKA_S3_BUCKET: no unnamed bucket")
    val byName = s.named.map(n => n.name -> n).toMap
    assertEquals(byName.keySet, Set("photos", "old-photos", "exports"))
    assertEquals(byName("photos").exposure, BucketExposure.Public)
    assertEquals(
      byName("photos").publicUrl,
      Some("https://storage.example.com/shop.listings.photos")
    )
    assertEquals(byName("photos").target.bucket, "shop.listings.photos")
    assertEquals(byName("photos").target.credentials, Some(SigV4.Credentials("GK1", "s3cr3t")))
    assertEquals(byName("old-photos").exposure, BucketExposure.Private)
    assertEquals(byName("exports").exposure, BucketExposure.Signed)
  }

  test("a supplied bucket has its own store and key") {
    val env = Map(
      "ANKKA_S3_ENDPOINT_ARCHIVE"   -> "https://s3.eu-west-2.amazonaws.com",
      "ANKKA_S3_REGION_ARCHIVE"     -> "eu-west-2",
      "ANKKA_S3_BUCKET_ARCHIVE"     -> "acme-archive",
      "ANKKA_S3_ACCESS_KEY_ARCHIVE" -> "AKIA1",
      "ANKKA_S3_SECRET_KEY_ARCHIVE" -> "k"
    )
    val s = read(env, locally = false).toOption.get
    assertEquals(s.backend, StorageBackend.S3)
    val archive = s.named.single
    assertEquals(archive.name, "archive")
    assertEquals(archive.exposure, BucketExposure.Supplied)
    assertEquals(
      archive.target,
      S3Target(
        URI.create("https://s3.eu-west-2.amazonaws.com"),
        "eu-west-2",
        "acme-archive",
        Some(SigV4.Credentials("AKIA1", "k")),
        None
      )
    )
  }

  test("an endpoint and a bucket without a key is a bucket with no credential") {
    val s = read(unnamed -- Vector("ANKKA_S3_ACCESS_KEY", "ANKKA_S3_SECRET_KEY")).toOption.get
    assertEquals(s.unnamed.map(_.credentials), Some(None))
  }

  test("each refusal names its variable") {
    assertEquals(
      read(Map.empty, backend = "s3").left.map(_.contains("ANKKA_S3_ENDPOINT")),
      Left(true)
    )
    assert(
      read(Map.empty, backend = "disk").left.exists(_.contains("one of s3, directory or memory"))
    )
    assert(read(unnamed - "ANKKA_S3_REGION").left.exists(_.contains("ANKKA_S3_REGION is not set")))
    assert(
      read(Map("ANKKA_S3_BUCKET_PHOTOS" -> "b")).left
        .exists(_.contains("ANKKA_S3_ENDPOINT is not set"))
    )
    assert(
      read(
        unnamed ++ Map("ANKKA_S3_BUCKET_PHOTOS" -> "b", "ANKKA_S3_EXPOSURE_PHOTOS" -> "public")
      ).left
        .exists(_.contains("ANKKA_S3_PUBLIC_URL_PHOTOS"))
    )
    assert(
      read(
        unnamed ++ Map("ANKKA_S3_BUCKET_PHOTOS" -> "b", "ANKKA_S3_EXPOSURE_PHOTOS" -> "visible")
      ).left
        .exists(_.contains("private, signed or public"))
    )
    assert(
      read(Map("ANKKA_S3_ENDPOINT_ARCHIVE" -> "https://x")).left
        .exists(_.contains("ANKKA_S3_REGION_ARCHIVE"))
    )
    assert(
      read(Map("ANKKA_S3_ENDPOINT" -> "nope", "ANKKA_S3_REGION" -> "r")).left
        .exists(_.contains("not a URL"))
    )
  }

  extension [A](v: Vector[A])
    private def single: A =
      assertEquals(v.size, 1, v)
      v.head
