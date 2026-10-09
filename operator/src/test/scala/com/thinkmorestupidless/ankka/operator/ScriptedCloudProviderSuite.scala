package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  CloudKinds,
  CloudResourceSpec,
  CloudResourceStatus,
  CloudSubject
}
import com.thinkmorestupidless.ankka.operator.cloud.{
  Ended,
  Issued,
  ScriptedFulfilment,
  ScriptedMemory,
  SecretWrites
}

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

/**
 * The scripted cloud provider's answers (feature 044), held to the rules a real provider must keep
 * (`docs/platform/cloud-provider.md`): every kind answered with made-up outputs, recovery, refusal,
 * and a credential offered once, replaced by a raised generation and ended after the grace.
 */
class ScriptedCloudProviderSuite extends munit.FunSuite:

  private val start = Instant.parse("2026-10-09T12:00:00Z")

  /** A Secret store that remembers only what was offered, and answers a create as the API would. */
  private final class Secrets extends SecretWrites:
    val held    = mutable.Map.empty[(String, String), Map[String, String]]
    val patches = mutable.ArrayBuffer.empty[String]
    def create(ns: String, name: String, e: Map[String, String]) =
      if held.contains(ns -> name) then SecretWrites.Outcome.Exists
      else
        held(ns -> name) = e
        SecretWrites.Outcome.Created
    def patch(ns: String, name: String, e: Map[String, String]): Unit =
      patches += name
      held(ns -> name) = held.getOrElse(ns -> name, Map.empty) ++ e

  private final class Fixture(memory: ScriptedMemory = new ScriptedMemory, acct: String = "acct"):
    var now     = start
    val secrets = new Secrets
    val f       = ScriptedFulfilment("gcp", acct, "europe-west2", 20.seconds, () => now, memory)
    def fulfil(
        name: String,
        spec: CloudResourceSpec,
        generation: Long = 1L,
        previous: Option[CloudResourceStatus] = None
    ) = f.fulfil("ankka-shop", name, spec, generation, previous, secrets)

  private val reports = CloudSubject("shop", "reports")

  private def spec(kind: String, parameters: (String, String)*): CloudResourceSpec =
    CloudResourceSpec("gcp", kind, reports, parameters = parameters.toMap)

  private val bucket = spec(
    CloudKinds.Bucket,
    "purpose"        -> "service",
    "location"       -> "europe-west2",
    "versioning"     -> "false",
    "softDeleteDays" -> "0",
    "corsOrigins"    -> "",
    "kmsKey"         -> ""
  )

  private def credential(generation: Long, bucketName: String = "acct-shop-reports") =
    spec(
      CloudKinds.BucketCredential,
      "bucket"     -> bucketName,
      "identity"   -> "reports@acct.scripted",
      "secretName" -> "reports-storage"
    ).copy(credentialGeneration = generation)

  private val everyKind = Vector(
    spec(CloudKinds.Identity, "serviceAccount" -> "reports"),
    spec(CloudKinds.SecretAccess, "identity"   -> "i", "own" -> "a", "read" -> ""),
    spec(
      CloudKinds.SecretSync,
      "secretName"      -> "checkout",
      "entries"         -> "A=a",
      "entryGeneration" -> "4"
    ),
    bucket,
    credential(1),
    spec(CloudKinds.WrappingKey, "identity" -> "i", "key" -> "keys/ankka")
  )

  test("every kind is fulfilled, acknowledged at its generation, in the account, by the script") {
    val fx = Fixture()
    everyKind.zipWithIndex.foreach { (s, i) =>
      val status = fx.fulfil(s"request-$i", s, generation = 3L)
      assertEquals(status.observedGeneration, Some(3L), s.kind)
      assertEquals(status.phase, "Ready", s.kind)
      assertEquals(status.account, "acct", s.kind)
      assertEquals(status.location, "europe-west2", s.kind)
      assert(status.providerVersion.startsWith("scripted"), status.providerVersion)
      assertEquals(status.outputs.keySet, CloudRequests.Keys.outputs(s.kind), s.kind)
    }
    assertEquals(fx.f.reached.get, 0)
  }

  test("the outputs are made up, and say so") {
    val fx = Fixture()
    assertEquals(
      fx.fulfil("reports-identity", everyKind(0)).outputs,
      Map("identity" -> "reports@acct.scripted")
    )
    assertEquals(
      fx.fulfil("reports-bucket", bucket).outputs,
      Map(
        "bucket"   -> "acct-shop-reports",
        "endpoint" -> "https://storage.scripted.invalid",
        "region"   -> "europe-west2"
      )
    )
    assertEquals(fx.fulfil("k", everyKind(5)).outputs, Map("key" -> "keys/ankka"))
  }

  test("a secret sync creates the named Secret and says which entries it kept in step") {
    val fx     = Fixture()
    val status = fx.fulfil("shop.secret-sync", everyKind(2))
    assertEquals(status.outputs, Map("entryGeneration" -> "4"))
    assertEquals(fx.secrets.held("ankka-shop" -> "checkout"), Map("A" -> "scripted-value-of-a"))
    assertEquals(fx.secrets.patches.toVector, Vector.empty)
  }

  test("a secret sync of a Secret that is already there patches it") {
    // On Secret Manager nothing but the provider writes the project's Secret, so the first sync
    // finds none and must create it; every later one finds it and patches.
    val fx = Fixture()
    fx.fulfil("shop.secret-sync", everyKind(2)): Unit
    fx.fulfil("shop.secret-sync", everyKind(2)): Unit
    assertEquals(fx.secrets.patches.toVector, Vector("checkout"))
  }

  test("a second request for what it made before is recovered") {
    val memory = new ScriptedMemory
    val first  = Fixture(memory).fulfil("reports-bucket", bucket)
    val again  = Fixture(memory).fulfil("reports-bucket", bucket)
    assertEquals(first.phase, "Ready")
    assertEquals(again.phase, "Recovered")
    assert(again.recovered)
    assertEquals(again.outputs("bucket"), first.outputs("bucket"))
  }

  test("a kind it does not implement is refused, naming the kind and its version") {
    val status = Fixture().fulfil("x", spec("managed-database"))
    assertEquals(status.phase, "Failed")
    assertEquals(status.detail, Some("kind managed-database is not implemented by scripted 0.1.0"))
  }

  test("a request now in another account or location is refused, never moved") {
    val memory = new ScriptedMemory
    Fixture(memory).fulfil("reports-bucket", bucket)
    val moved = Fixture(memory, acct = "other").fulfil("reports-bucket", bucket)
    assertEquals(moved.phase, "Failed")
    assertEquals(moved.detail, Some("made in another account or location"))
    val elsewhere = Fixture(memory).fulfil(
      "reports-bucket",
      bucket.copy(parameters = bucket.parameters + ("location" -> "us-east1"))
    )
    assertEquals(elsewhere.detail, Some("made in another account or location"))
  }

  test("a request the script names fails with the script's reason") {
    val fx = Fixture()
    fx.f.failing("reports-bucket", "the location is refused")
    val status = fx.fulfil("reports-bucket", bucket)
    assertEquals(status.phase, "Failed")
    assertEquals(status.detail, Some("the location is refused"))
  }

  test("a backup bucket's credential is refused to anyone but the project's database") {
    val fx     = Fixture()
    val backup = CloudSubject("shop")
    fx.fulfil(
      "shop.identity",
      CloudResourceSpec(
        "gcp",
        CloudKinds.Identity,
        backup,
        parameters = Map("serviceAccount" -> "shop-db")
      )
    )
    val wrong = fx.fulfil(
      "shop.backup-credential",
      credential(1, bucketName = "acct-shop--backup").copy(subject = backup)
    )
    assertEquals(wrong.detail, Some("a backup bucket is granted only to its project's database"))
    val right = fx.fulfil(
      "shop.backup-credential",
      credential(1, bucketName = "acct-shop--backup").copy(
        subject = backup,
        parameters = credential(1).parameters ++ Map(
          "bucket"   -> "acct-shop--backup",
          "identity" -> "shop-db@acct.scripted"
        )
      )
    )
    assertEquals(right.phase, "Ready")
  }

  // The credential, written once (credential.feature).

  test("a bucket credential request is fulfilled by offering the secret once") {
    val fx     = Fixture()
    val status = fx.fulfil("reports-storage-credential", credential(1))
    assertEquals(status.phase, "Ready")
    assertEquals(status.outputs, Map("secretName" -> "reports-storage"))
    assertEquals(status.credentialGeneration, Some(1L))
    assertEquals(fx.f.issued, Vector(Issued("reports-storage", 1, start)))
    assertEquals(fx.f.ended, Vector.empty)
    assert(fx.secrets.held.contains("ankka-shop" -> "reports-storage"))
    assertEquals(fx.secrets.patches.toVector, Vector.empty, "a first credential is a create")
  }

  test("a bucket credential request whose secret is already there ends the credential just made") {
    val fx = Fixture()
    fx.secrets.held("ankka-shop" -> "reports-storage") = Map("ANKKA_S3_ACCESS_KEY" -> "before")
    val status = fx.fulfil("reports-storage-credential", credential(1))
    assertEquals(status.phase, "Ready")
    assertEquals(status.outputs, Map("secretName" -> "reports-storage"))
    assertEquals(fx.f.issued.size, 1)
    assertEquals(fx.f.ended, Vector(Ended("reports-storage", 1, start, "conflict")))
    assertEquals(
      fx.secrets.held("ankka-shop" -> "reports-storage")("ANKKA_S3_ACCESS_KEY"),
      "before"
    )
  }

  test("a request answered at its generation issues nothing more") {
    val fx    = Fixture()
    val first = fx.fulfil("reports-storage-credential", credential(1))
    val again = fx.fulfil("reports-storage-credential", credential(1), previous = Some(first))
    assertEquals(fx.f.issued.size, 1)
    assertEquals(again.credentialGeneration, Some(1L))
  }

  test(
    "raising the credential generation replaces the credential and ends the old one after the grace"
  ) {
    val fx    = Fixture()
    val first = fx.fulfil("reports-storage-credential", credential(1))
    val keyOf = () => fx.secrets.held("ankka-shop" -> "reports-storage")("ANKKA_S3_ACCESS_KEY")
    val old   = keyOf()
    fx.now = start.plusSeconds(60)
    val second =
      fx.fulfil(
        "reports-storage-credential",
        credential(2),
        generation = 2L,
        previous = Some(first)
      )
    assertEquals(second.credentialGeneration, Some(2L))
    assertEquals(second.credentialReportedAt, Some(fx.now.toString))
    assertNotEquals(keyOf(), old, "the same Secret, patched with the new credential")
    assertEquals(fx.secrets.patches.toVector, Vector("reports-storage"))
    assertEquals(fx.f.issued.map(_.generation), Vector(1L, 2L))
    fx.now = start.plusSeconds(79)
    assertEquals(fx.f.endDue(), Vector.empty, "the old credential works until the grace has passed")
    fx.now = start.plusSeconds(80)
    assertEquals(fx.f.endDue().map(e => e.generation -> e.why), Vector(1L -> "rotated"))
    assertEquals(fx.f.ended.map(_.generation), Vector(1L))
    assertEquals(fx.f.endDue(), Vector.empty, "ended once")
  }

  test("a later generation of a recovered request stays recovered") {
    val memory = new ScriptedMemory
    Fixture(memory).fulfil("reports-storage-credential", credential(1))
    val fx    = Fixture(memory)
    val first = fx.fulfil("reports-storage-credential", credential(1))
    val bump  = fx.fulfil("reports-storage-credential", credential(2), 2L, Some(first))
    assert(first.recovered && bump.recovered)
  }
