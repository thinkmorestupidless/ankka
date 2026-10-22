package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * What a descriptor says about object storage (feature 034), and each refusal in the words the CLI
 * and the control plane both print (`contracts/descriptor-and-status.md`).
 */
class ObjectStorageDescriptorSuite extends munit.FunSuite:

  private val base = ServiceSpec(image = "reports:1")

  private val Hostings = Vector(
    base,
    base.copy(hosting = ServiceSpec.Process, protocol = Some("1.6")),
    base.copy(hosting = ServiceSpec.Wasm, protocol = Some("1.6")),
    base.copy(hosting = ServiceSpec.Web)
  )

  private def problems(spec: ServiceSpec): Vector[String] =
    ServiceDescriptor("reports", spec).problems

  private def decode(json: String): ServiceSpec =
    readFromString[ServiceDescriptor](s"""{"name":"reports","service":$json}""").service

  test("a descriptor that says nothing asks for no bucket") {
    assertEquals(decode("""{"image":"reports:1"}""").provisionObjectStorage, false)
  }

  test("asking for a bucket round-trips, and is omitted from the wire when not asked") {
    val asks = ServiceDescriptor("reports", base.copy(provisionObjectStorage = true))
    assertEquals(readFromString[ServiceDescriptor](writeToString(asks)), asks)
    assert(!writeToString(ServiceDescriptor("reports", base)).contains("ObjectStorage"))
  }

  test("a null for the field is a decode error, never a silent default") {
    // As for every plain boolean of the descriptor: only an Option reads null as absent.
    intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException](
      decode("""{"image":"reports:1","provisionObjectStorage":null}""")
    )
  }

  test("a service of any hosting may ask for a bucket") {
    for spec <- Hostings do
      assertEquals(problems(spec.copy(provisionObjectStorage = true)), Vector.empty, spec.hosting)
  }

  // A storage credential is the platform's (feature 034, US2).

  test("a descriptor cannot take a variable from the secret that holds a storage credential") {
    for
      spec   <- Hostings
      secret <- Vector("exports-storage", "reports-storage")
    do
      val taking = spec.copy(env =
        Vector(
          EnvVar("CREDENTIAL", secretKeyRef = Some(SecretKeyRef(secret, "ANKKA_S3_SECRET_KEY")))
        )
      )
      val found = problems(taking)
      assert(
        found.contains(
          s"env var 'CREDENTIAL': secret '$secret' is issued by the platform and cannot be read by " +
            "a service"
        ),
        s"${spec.hosting}: $found"
      )
  }

  // A store of the service's own (US3) and a bucket reachable from the internet (US4).

  private def literal(name: String) = EnvVar(name, value = Some("x"))
  private def fromSecret(name: String) =
    EnvVar(name, secretKeyRef = Some(SecretKeyRef("my-store", "key")))

  test("a descriptor cannot both ask for a bucket and give a variable of a store of its own") {
    for
      variable <- Vector("ANKKA_S3_ENDPOINT", "ANKKA_S3_SECRET_KEY", "ANKKA_S3_REGION")
      entry    <- Vector(literal(variable), fromSecret(variable))
    do
      val found = problems(base.copy(provisionObjectStorage = true, env = Vector(entry)))
      assert(
        found.contains(
          s"provisionObjectStorage cannot be combined with env var '$variable', which supplies " +
            "an object store of the service's own"
        ),
        found.toString
      )
  }

  test("a store of the service's own, without asking, is valid for every hosting") {
    val own = Vector("ANKKA_S3_ENDPOINT", "ANKKA_S3_BUCKET").map(literal)
    for spec <- Hostings do assertEquals(problems(spec.copy(env = own)), Vector.empty, spec.hosting)
  }

  test("asking that the bucket be reachable round-trips, and is omitted when not asked") {
    val both = ServiceDescriptor(
      "reports",
      base.copy(provisionObjectStorage = true, exposeObjectStorage = true)
    )
    assertEquals(readFromString[ServiceDescriptor](writeToString(both)), both)
    assertEquals(decode("""{"image":"reports:1"}""").exposeObjectStorage, false)
  }

  test("only a bucket the platform made can be made reachable from the internet") {
    val message =
      "exposeObjectStorage needs provisionObjectStorage: only a bucket the platform made can be " +
        "reached from outside the cluster"
    assert(problems(base.copy(exposeObjectStorage = true)).contains(message))
    assert(
      problems(
        base.copy(exposeObjectStorage = true, env = Vector(literal("ANKKA_S3_ENDPOINT")))
      ).contains(message)
    )
  }

  test("a bucket reachable from the internet is valid for every hosting") {
    for spec <- Hostings do
      assertEquals(
        problems(spec.copy(provisionObjectStorage = true, exposeObjectStorage = true)),
        Vector.empty,
        spec.hosting
      )
  }

  // Feature 039: origins, declining a credential, and an age for noncurrent versions.

  private val asking = base.copy(provisionObjectStorage = true)

  test(
    "a descriptor that says nothing of them names no origins, takes a credential and keeps every version"
  ) {
    val spec = decode("""{"image":"reports:1","provisionObjectStorage":true}""")
    assertEquals(spec.objectStorageOrigins, Vector.empty[String])
    assertEquals(spec.objectStorageCredential, true)
    assertEquals(spec.objectStorageVersionAgeDays, None)
  }

  test("the three round-trip, and each is omitted from the wire at its default") {
    val says = ServiceDescriptor(
      "reports",
      asking.copy(
        objectStorageOrigins = Vector("https://play.example"),
        objectStorageCredential = false,
        objectStorageVersionAgeDays = Some(365)
      )
    )
    val json = writeToString(says)
    assertEquals(readFromString[ServiceDescriptor](json), says)
    assert(json.contains("\"objectStorageCredential\":false"), json)
    val plain = writeToString(ServiceDescriptor("reports", asking))
    assert(!plain.contains("objectStorageOrigins"), plain)
    assert(!plain.contains("objectStorageCredential"), plain)
    assert(!plain.contains("objectStorageVersionAgeDays"), plain)
  }

  test("each needs provisionObjectStorage, and the refusal names the field") {
    val cases = Vector(
      "objectStorageOrigins"    -> base.copy(objectStorageOrigins = Vector("https://play.example")),
      "objectStorageCredential" -> base.copy(objectStorageCredential = false),
      "objectStorageVersionAgeDays" -> base.copy(objectStorageVersionAgeDays = Some(30))
    )
    for (field, spec) <- cases do
      assertEquals(
        problems(spec),
        Vector(s"$field needs provisionObjectStorage: it describes a bucket the platform makes"),
        field
      )
  }

  test(
    "an origin is a scheme, a host and perhaps a port, or *; anything else is refused naming it"
  ) {
    val good = Vector(
      "https://play.example",
      "http://localhost:3000",
      "https://play.example:8443",
      "https://a-b.c.example",
      "*"
    )
    assertEquals(problems(asking.copy(objectStorageOrigins = good)), Vector.empty)
    for bad <- Vector(
        "play.example",
        "https://play.example/",
        "https://play.example/kyc",
        "ftp://x",
        ""
      )
    do
      assertEquals(
        problems(asking.copy(objectStorageOrigins = Vector(bad))),
        Vector(s"objectStorageOrigins: '$bad' is not an origin"),
        bad
      )
  }

  test("an age for noncurrent versions is a whole number of days, one or more") {
    assertEquals(problems(asking.copy(objectStorageVersionAgeDays = Some(1))), Vector.empty)
    for bad <- Vector(0, -1) do
      assertEquals(
        problems(asking.copy(objectStorageVersionAgeDays = Some(bad))),
        Vector(s"objectStorageVersionAgeDays is $bad; it must be one or more"),
        bad.toString
      )
  }

  test("declining a credential is valid for every hosting, in the descriptor's own rules") {
    // Whether the installation's store allows it is the control plane's to say, at apply.
    for spec <- Hostings do
      assertEquals(
        problems(spec.copy(provisionObjectStorage = true, objectStorageCredential = false)),
        Vector.empty,
        spec.hosting
      )
  }

  // ── named buckets (feature 055) ───────────────────────────────────────────

  private val fourBuckets = """{"image":"listings:1","provisionObjectStorage":true,"buckets":[
    {"name":"photos","exposure":"public"},
    {"name":"exports","exposure":"signed","origins":["https://app.example.com"]},
    {"name":"documents"},
    {"name":"archive","supplied":{"endpoint":"https://s3.eu-west-2.amazonaws.com",
      "region":"eu-west-2","bucket":"acme-archive","credential":"archive-store"}}]}"""

  test("the contract's four-bucket descriptor is accepted, and round-trips") {
    val spec = decode(fourBuckets)
    assertEquals(problems(spec), Vector.empty)
    assertEquals(
      spec.buckets.map(b => b.name -> b.exposure).take(3),
      Vector(
        "photos"    -> Exposure.Public,
        "exports"   -> Exposure.Signed,
        "documents" -> Exposure.Private
      )
    )
    assertEquals(spec.buckets(3).supplied.map(_.credential), Some("archive-store"))
    val descriptor = ServiceDescriptor("listings", spec)
    assertEquals(readFromString[ServiceDescriptor](writeToString(descriptor)), descriptor)
  }

  test("a bucket's defaults are omitted from the wire, and no buckets is no field") {
    val wire = writeToString(
      ServiceDescriptor("listings", base.copy(buckets = Vector(BucketSpec("documents"))))
    )
    assert(wire.contains("""{"name":"documents"}"""), wire)
    assert(!writeToString(ServiceDescriptor("listings", base)).contains("buckets"))
  }

  test("named buckets are accepted without provisionObjectStorage") {
    Hostings.foreach { h =>
      assertEquals(
        problems(h.copy(buckets = Vector(BucketSpec("photos", Exposure.Public)))),
        Vector.empty
      )
    }
  }

  test("each refusal of a bucket is in the contract's words") {
    def refused(buckets: BucketSpec*) = problems(base.copy(buckets = buckets.toVector))
    assertEquals(
      refused(BucketSpec("photos"), BucketSpec("photos", Exposure.Public)),
      Vector("buckets: 'photos' is named twice")
    )
    assertEquals(
      refused(BucketSpec("Photos")),
      Vector(
        "buckets: 'Photos' is not a bucket name: lower-case letters, digits and '-', 1 to 32 characters"
      )
    )
    assertEquals(
      refused(BucketSpec("photos", Exposure.Public, origins = Vector("https://app.example.com"))),
      Vector("buckets: 'photos' names origins but is not signed")
    )
    val archive =
      SuppliedBucket("https://s3.example.com", "eu-west-2", "acme-archive", "archive-store")
    assertEquals(
      refused(BucketSpec("archive", Exposure.Public, supplied = Some(archive))),
      Vector("buckets: 'archive' is supplied; its exposure is the store's")
    )
    assertEquals(
      refused(BucketSpec("archive", supplied = Some(archive.copy(region = "")))),
      Vector("buckets: 'archive' needs endpoint, region, bucket and credential")
    )
    val reserved =
      refused(BucketSpec("archive", supplied = Some(archive.copy(credential = "archive-storage"))))
    assertEquals(
      reserved,
      ProjectSecrets.nameProblems("archive-storage").map(p => s"buckets: 'archive': $p")
    )
    assert(reserved.nonEmpty)
    assertEquals(
      refused(BucketSpec("exports", Exposure.Signed, origins = Vector("app.example.com"))),
      Vector("buckets: 'exports': 'app.example.com' is not an origin")
    )
  }

  test("a supplied bucket missing a field in the descriptor is refused, not a decode error") {
    val spec = decode(
      """{"image":"a:1","buckets":[{"name":"archive","supplied":{"endpoint":"https://s3.example.com"}}]}"""
    )
    assertEquals(
      problems(spec),
      Vector("buckets: 'archive' needs endpoint, region, bucket and credential")
    )
  }

  test("a named bucket beside a variable of a store of the service's own is refused") {
    val own = base.copy(
      buckets = Vector(BucketSpec("photos")),
      env = Vector(EnvVar("ANKKA_S3_ENDPOINT", value = Some("https://s3.example.com")))
    )
    assertEquals(
      problems(own),
      Vector(
        "buckets cannot be combined with env var 'ANKKA_S3_ENDPOINT', which supplies an object " +
          "store of the service's own"
      )
    )
  }

  test("an exposure the platform does not know is a decode error naming the three") {
    val refused = intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException](
      decode("""{"image":"a:1","buckets":[{"name":"photos","exposure":"open"}]}""")
    )
    assert(refused.getMessage.contains("private, signed or public"), refused.getMessage)
  }

  test("the bucket name rule is the storage client's, held to the shared fixture") {
    val fixture = java.nio.file.Files.readString(
      java.nio.file.Paths.get("../protocol/fixtures/storage/rules.json")
    )
    val rows = "\\{\"unit\": \"([^\"]*)\", \"repeat\": (\\d+), \"accepted\": (true|false)".r
      .findAllMatchIn(fixture.substring(fixture.indexOf("\"bucketNames\"")))
      .takeWhile(_ => true)
      .toVector
    val names = rows.takeWhile(m => !m.matched.isEmpty).take(8)
    assertEquals(names.size, 8, fixture)
    names.foreach { m =>
      val name     = m.group(1) * m.group(2).toInt
      val accepted = m.group(3) == "true"
      assertEquals(BucketSpec.nameProblem(name).isEmpty, accepted, s"'$name'")
      if !accepted then
        assert(BucketSpec.nameProblem(name).exists(_.endsWith(BucketSpec.NameRule)), name)
    }
  }

  test("a status's bucket reports round-trip, kept and supplied among them") {
    val status = ServiceStatus(
      "listings",
      "shop",
      ServiceLifecycle.Ready,
      1,
      "listings:1",
      1,
      1,
      buckets = Vector(
        BucketReport(
          "photos",
          "Provisioned",
          Some("garage"),
          Some("shop.listings.photos"),
          "public",
          Some("https://storage.example.com/shop.listings.photos")
        ),
        BucketReport("archive", "Supplied", None, Some("acme-archive"), "supplied"),
        BucketReport(
          "old-photos",
          "Kept",
          Some("garage"),
          Some("shop.listings.old-photos"),
          kept = true
        )
      )
    )
    assertEquals(readFromString[ServiceStatus](writeToString(status)), status)
    assert(!writeToString(status.copy(buckets = Vector.empty)).contains("buckets"))
  }
