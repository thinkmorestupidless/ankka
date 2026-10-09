package com.thinkmorestupidless.ankka.crd

/**
 * A cloud request survives a round trip through fabric8's serialization (feature 044).
 *
 * Two processes read this resource, and a provider may be written in any language, so its wire form
 * is the contract: absent values omitted rather than null, every parameter a string, and a
 * generation read back as a `Long` (the `Option[Long]` trap in `.claude/rules/kubernetes.md`).
 */
class CloudResourceCodecSuite extends munit.FunSuite:

  private val serialization = AnkkaSerialization()

  private val spec = CloudResourceSpec(
    provider = "gcp",
    kind = CloudKinds.BucketCredential,
    subject = CloudSubject("shop", "reports"),
    credentialGeneration = 2L,
    parameters = Map(
      "bucket"     -> "account-shop-reports",
      "identity"   -> "reports@account.scripted",
      "secretName" -> "reports-storage"
    )
  )

  private val status = CloudResourceStatus(
    observedGeneration = Some(3L),
    phase = CloudKinds.Recovered,
    detail = Some("made before"),
    account = "account",
    location = "europe-west2",
    credentialGeneration = Some(2L),
    credentialReportedAt = Some("2026-10-09T12:00:00Z"),
    recovered = true,
    providerVersion = "scripted 0.7.0",
    outputs = Map("secretName" -> "reports-storage")
  )

  private def resource: CloudResource =
    val r = CloudResource("ankka-shop", "reports-storage-credential", spec)
    r.setStatus(status)
    r

  test("a full spec round-trips unchanged") {
    assertEquals(
      serialization.unmarshal(serialization.asJson(spec), classOf[CloudResourceSpec]),
      spec
    )
  }

  test("a full status round-trips unchanged") {
    assertEquals(
      serialization.unmarshal(serialization.asJson(status), classOf[CloudResourceStatus]),
      status
    )
  }

  test("the resource round-trips with its group, version, kind and both halves") {
    val json    = serialization.asJson(resource)
    val decoded = serialization.unmarshal(json, classOf[CloudResource])
    assertEquals(decoded.getApiVersion, "ankka.thinkmorestupidless.com/v1alpha1")
    assertEquals(decoded.getKind, "CloudResource")
    assertEquals(decoded.getSpec, spec)
    assertEquals(decoded.getStatus, status)
  }

  test("parameters and outputs are maps of strings on the wire") {
    val json = serialization.asJson(resource)
    assert(json.contains("\"secretName\":\"reports-storage\""), json)
    assert(!json.contains("\"defined\""), s"an Option leaked its wrapper: $json")
  }

  test("a new resource has no status, and a status is null until a provider answers") {
    val fresh = CloudResource("ankka-shop", "reports-bucket", CloudResourceSpec(kind = "bucket"))
    assertEquals(fresh.getStatus, null)
    val json = serialization.asJson(fresh)
    assert(!json.contains("\"status\""), json)
  }

  test("nothing is written as null, so an apply claims no field it does not mean to") {
    // The spec is the operator's alone, so a zero generation and an empty service are its to own;
    // what server-side apply must never see is a null, which claims a field without a value.
    val json = serialization.asJson(
      CloudResourceSpec(provider = "gcp", kind = "bucket", subject = CloudSubject("shop"))
    )
    assert(!json.contains("null"), json)
    val statusJson = serialization.asJson(CloudResourceStatus(phase = "Waiting"))
    assert(!statusJson.contains("null"), statusJson)
    assert(!statusJson.contains("observedGeneration"), statusJson)
  }

  test("a sparse status decodes to its defaults") {
    val decoded = serialization.unmarshal("""{"phase":"Waiting"}""", classOf[CloudResourceStatus])
    assertEquals(decoded, CloudResourceStatus(phase = "Waiting"))
  }

  test("a generation is read back as a Long, never a boxed Integer") {
    val decoded = serialization.unmarshal(
      """{"observedGeneration":1,"credentialGeneration":2}""",
      classOf[CloudResourceStatus]
    )
    val observed: Long   = decoded.observedGeneration.getOrElse(fail("absent"))
    val credential: Long = decoded.credentialGeneration.getOrElse(fail("absent"))
    assertEquals(observed + credential, 3L)
  }

  test("the contract names six kinds and four phases, and nothing in a cloud's words") {
    assertEquals(
      CloudKinds.all,
      Vector(
        "identity",
        "secret-access",
        "secret-sync",
        "bucket",
        "bucket-credential",
        "wrapping-key"
      )
    )
    assertEquals(CloudKinds.phases, Vector("Waiting", "Ready", "Recovered", "Failed"))
  }
