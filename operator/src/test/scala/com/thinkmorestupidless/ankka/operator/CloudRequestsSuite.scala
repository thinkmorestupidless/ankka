package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaProjectSpec,
  AnkkaService,
  AnkkaServiceSpec,
  CloudKinds,
  CloudResource,
  CloudResourceStatus,
  CloudSubject
}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The six cloud requests as the operator renders them (feature 044): their names, their owners, and
 * exactly the keys the contract gives each kind, in the platform's words and no cloud's.
 * `features/cloud-provider/kinds.feature` is tested here, each case named for its scenario, until
 * the features that write those requests (038, 041, 042) add the settings that call for them.
 */
class CloudRequestsSuite extends munit.FunSuite:
  import CloudRequests.*

  private val cloud =
    CloudSettings("gcp", "my-account", "europe-west2", Some("keys/ankka"), 2.minutes, 1.hour)

  private val service: AnkkaService =
    val r = AnkkaService(
      "ankka-shop",
      "reports",
      AnkkaServiceSpec(projectId = "shop", serviceName = "reports", image = "img:1")
    )
    r.getMetadata.setUid("service-uid")
    r

  private val project: AnkkaProject =
    val p = AnkkaProject("ankka-shop", "shop", AnkkaProjectSpec(projectId = "shop"))
    p.getMetadata.setUid("project-uid")
    p

  private val byService = Requester.of(service)
  private val byProject = Requester.of(project)

  private def keys(r: CloudResource): Set[String] = r.getSpec.parameters.keySet

  private def holdsTheContract(r: CloudResource): Unit =
    val kind = r.getSpec.kind
    assert(Keys.required(kind).subsetOf(keys(r)), s"$kind's keys ${keys(r)} lack a required one")
    assert(
      keys(r).subsetOf(Keys.parameters(kind)),
      s"$kind's keys ${keys(r)} are not the contract's"
    )
    // A credential names its buckets one way or the other, never both and never neither.
    if kind == CloudKinds.BucketCredential then
      assertEquals(keys(r).intersect(Set("bucket", "buckets")).size, 1, keys(r).toString)
    assertEquals(r.getSpec.provider, "gcp")

  private def ownedBy(r: CloudResource, kind: String, uid: String): Unit =
    val owners = r.getMetadata.getOwnerReferences.asScala
    assertEquals(owners.size, 1)
    assertEquals(owners.head.getKind, kind)
    assertEquals(owners.head.getUid, uid)
    assertEquals(owners.head.getController, java.lang.Boolean.TRUE)

  // Names.

  test("a service's request is <service>-<suffix>, a project's <project>.<suffix>") {
    assertEquals(Names.CloudRequest.ofService("reports", "bucket"), "reports-bucket")
    assertEquals(Names.CloudRequest.ofProject("shop", "backup-bucket"), "shop.backup-bucket")
  }

  test("no service's request can share a name with its project's, whatever it is called") {
    // A service called "shop-backup" in "shop" would collide with "shop-backup-bucket" under a
    // hyphen; a dot cannot appear in a service's name.
    val services = Vector("shop-backup", "shop", "backup", "shop-backup-bucket")
    val projects = Names.CloudRequest.ofProject("shop", Names.CloudRequest.BackupBucketSuffix)
    services.foreach { name =>
      assertNotEquals(Names.CloudRequest.ofService(name, Names.CloudRequest.BucketSuffix), projects)
    }
  }

  // The bucket path (bucket.feature, scenario 1).

  test("an identity request is <service>-identity, naming the service's ServiceAccount alone") {
    val r = identity(cloud, byService, Names.serviceAccount("reports"))
    assertEquals(r.getMetadata.getName, "reports-identity")
    assertEquals(r.getMetadata.getNamespace, "ankka-shop")
    assertEquals(r.getSpec.kind, CloudKinds.Identity)
    assertEquals(r.getSpec.subject, CloudSubject("shop", "reports"))
    assertEquals(r.getSpec.parameters, Map("serviceAccount" -> Names.serviceAccount("reports")))
    assertEquals(r.getSpec.credentialGeneration, 0L)
    assertEquals(r.getMetadata.getLabels.asScala.toMap, Labels.identity("shop", "reports"))
    ownedBy(r, "AnkkaService", "service-uid")
    holdsTheContract(r)
  }

  test("a bucket request for a service names its purpose, the location and what it asks") {
    val r = bucket(cloud, byService, Purpose.Service, cloud.location, BucketAsk())
    assertEquals(r.getMetadata.getName, "reports-bucket")
    assertEquals(r.getSpec.kind, CloudKinds.Bucket)
    assertEquals(
      r.getSpec.parameters,
      Map(
        "purpose"               -> "service",
        "location"              -> "europe-west2",
        "versioning"            -> "false",
        "softDeleteDays"        -> "0",
        "corsOrigins"           -> "",
        "kmsKey"                -> "keys/ankka",
        "namePrefix"            -> "",
        "noncurrentVersionDays" -> ""
      )
    )
    ownedBy(r, "AnkkaService", "service-uid")
    holdsTheContract(r)
  }

  test(
    "a bucket credential request names the bucket, the identity and the Secret, at a generation"
  ) {
    val r = bucketCredential(
      cloud,
      byService,
      Purpose.Service,
      "my-account-shop-reports",
      "reports@my-account.scripted",
      "reports-storage",
      generation = 2L
    )
    assertEquals(r.getMetadata.getName, "reports-storage-credential")
    assertEquals(r.getSpec.kind, CloudKinds.BucketCredential)
    assertEquals(r.getSpec.credentialGeneration, 2L)
    assertEquals(
      r.getSpec.parameters,
      Map(
        "bucket"     -> "my-account-shop-reports",
        "identity"   -> "reports@my-account.scripted",
        "secretName" -> "reports-storage"
      )
    )
    holdsTheContract(r)
  }

  test(
    "a bucket request carries the installation's name prefix and the age of a noncurrent version"
  ) {
    // Feature 039 (research R1a D3): a list is comma-separated, a number its decimal text.
    val ask = BucketAsk(
      versioning = true,
      softDeleteDays = 7,
      corsOrigins = Vector("https://play.example", "https://shop.example"),
      namePrefix = "acme",
      noncurrentVersionDays = Some(30)
    )
    val p = bucket(cloud, byService, Purpose.Service, cloud.location, ask).getSpec.parameters
    assertEquals(p("namePrefix"), "acme")
    assertEquals(p("noncurrentVersionDays"), "30")
    assertEquals(p("versioning"), "true")
    assertEquals(p("softDeleteDays"), "7")
    assertEquals(p("corsOrigins"), "https://play.example,https://shop.example")
  }

  test("an identity answers the annotations its ServiceAccount carries, beside the identity") {
    assertEquals(
      Keys.outputs(CloudKinds.Identity),
      Set("identity", "serviceAccountAnnotations")
    )
  }

  test("an installation that names no wrapping key asks a bucket for none") {
    val r = bucket(cloud.copy(kmsKey = None), byService, Purpose.Service, "x", BucketAsk())
    assertEquals(r.getSpec.parameters("kmsKey"), "")
  }

  // kinds.feature.

  test(
    "a service whose project secrets are kept in the cloud account asks for an identity and " +
      "access to its secrets"
  ) {
    val r = secretAccess(
      cloud,
      byService,
      "reports@my-account.scripted",
      own = Vector("shop-reports-a", "shop-reports-b"),
      read = Vector("shop-checkout")
    )
    assertEquals(r.getMetadata.getName, "reports-secret-access")
    assertEquals(
      r.getSpec.parameters,
      Map(
        "identity" -> "reports@my-account.scripted",
        "own"      -> "shop-reports-a,shop-reports-b",
        "read"     -> "shop-checkout"
      )
    )
    holdsTheContract(r)
  }

  test(
    "a project whose project secrets are kept in the cloud account asks for them to be kept in step"
  ) {
    val r = secretSync(
      cloud,
      byProject,
      "checkout",
      Map("WEBHOOK_KEY" -> "shop-checkout-webhook", "STRIPE_KEY" -> "shop-checkout-stripe"),
      entryGeneration = 3L
    )
    assertEquals(r.getMetadata.getName, "shop.secret-sync.checkout")
    assertEquals(r.getSpec.subject, CloudSubject("shop"))
    assertEquals(
      r.getSpec.parameters,
      Map(
        "secretName"      -> "checkout",
        "entries"         -> "STRIPE_KEY=shop-checkout-stripe,WEBHOOK_KEY=shop-checkout-webhook",
        "entryGeneration" -> "3"
      )
    )
    ownedBy(r, "AnkkaProject", "project-uid")
    holdsTheContract(r)
  }

  test(
    "a project whose backups are kept in the cloud account asks for a backup bucket and a " +
      "credential for its database"
  ) {
    val b = bucket(cloud, byProject, Purpose.Backup, cloud.location, BucketAsk())
    assertEquals(b.getMetadata.getName, "shop.backup-bucket")
    assertEquals(b.getSpec.parameters("purpose"), "backup")
    val c = bucketCredential(
      cloud,
      byProject,
      Purpose.Backup,
      "my-account-shop-backup",
      "shop-db@my-account.scripted",
      "shop-backup-credential",
      generation = 1L
    )
    assertEquals(c.getMetadata.getName, "shop.backup-credential")
    ownedBy(c, "AnkkaProject", "project-uid")
    holdsTheContract(b)
    holdsTheContract(c)
  }

  // Feature 042: the keyring's requests, a platform workload's.

  private val erasureOn = Settings.default.copy(
    cloud = Some(cloud),
    erasure = Some(KeyringSettings("https://keyring.ankka-keyring.svc:8443"))
  )

  private val noSecrets = new _root_.com.thinkmorestupidless.ankka.operator.cloud.SecretWrites:
    def create(ns: String, name: String, e: Map[String, String]) =
      _root_.com.thinkmorestupidless.ankka.operator.cloud.SecretWrites.Outcome.Created
    def patch(ns: String, name: String, e: Map[String, String]): Unit = ()

  private def keyringRequests(
      settings: Settings,
      answers: Map[String, CloudResourceStatus]
  ): Vector[CloudResource] =
    val now = java.time.Instant.parse("2026-11-04T12:00:00Z")
    PlatformErasure
      .cloudActions(
        settings,
        (_, name) => answers.get(name).map(s => CloudObservation(1L, now, Some(s))),
        now
      )
      .collect { case Action.EnsureCloudResource(r) => r }

  test("a keyring on an installation that names a wrapping key asks to wrap with it") {
    val provider = _root_.com.thinkmorestupidless.ankka.operator.cloud
      .ScriptedFulfilment("gcp", "my-account", "europe-west2", 20.seconds)
    def fulfil(r: CloudResource) =
      provider.fulfil(
        r.getMetadata.getNamespace,
        r.getMetadata.getName,
        r.getSpec,
        1L,
        None,
        noSecrets
      )

    // First the keyring's cloud identity, and nothing to wrap with until it is made.
    val first = keyringRequests(erasureOn, Map.empty)
    assertEquals(first.map(_.getMetadata.getName), Vector("keyring-identity"))
    val identity = first.head
    assertEquals(identity.getSpec.parameters, Map("serviceAccount" -> "ankka-keyring"))
    val made = fulfil(identity)

    val second = keyringRequests(erasureOn, Map("keyring-identity" -> made))
    assertEquals(
      second.map(_.getMetadata.getName),
      Vector("keyring-identity", "keyring-wrapping-key")
    )
    val r = second(1)
    assertEquals(r.getSpec.kind, CloudKinds.WrappingKey)
    assertEquals(r.getMetadata.getNamespace, "ankka-keyring")
    assertEquals(r.getSpec.subject, CloudSubject("platform", "keyring"))
    assertEquals(
      r.getSpec.parameters,
      Map("identity" -> made.outputs("identity"), "key" -> "keys/ankka")
    )
    // No resource the operator watches owns the keyring: its requests go with nothing.
    for request <- second do
      assertEquals(request.getMetadata.getOwnerReferences.asScala.toList, List.empty)
    holdsTheContract(identity)
    holdsTheContract(r)

    // The fulfilment names the wrapping key the keyring wraps with.
    val answered = fulfil(r)
    assertEquals(answered.phase, CloudKinds.Ready)
    assertEquals(answered.outputs, Map("key" -> "keys/ankka"))

    // And the keyring's ServiceAccount is bound to the identity as the provider said.
    val bound = PlatformErasure
      .cloudActions(
        erasureOn,
        (_, name) =>
          Option.when(name == "keyring-identity")(
            CloudObservation(1L, java.time.Instant.EPOCH, Some(made))
          ),
        java.time.Instant.EPOCH
      )
      .collectFirst { case Action.EnsureServiceAccount(sa) => sa }
      .getOrElse(fail("the ServiceAccount is not bound"))
    assertEquals(bound.getMetadata.getNamespace, "ankka-keyring")
    assertEquals(bound.getMetadata.getName, "ankka-keyring")
    assertEquals(
      bound.getMetadata.getAnnotations.asScala.toMap,
      Map("scripted.example/identity" -> "ankka-keyring")
    )
  }

  test("a keyring asks nothing of a cloud without a wrapping key, or with erasure off") {
    for settings <- Vector(
        erasureOn.copy(cloud = Some(cloud.copy(kmsKey = None))),
        erasureOn.copy(cloud = Some(cloud.copy(kmsKey = Some("")))),
        erasureOn.copy(erasure = None),
        erasureOn.copy(cloud = None),
        erasureOn.copy(cloud = Some(cloud.copy(provider = "other")))
      )
    do assertEquals(keyringRequests(settings, Map.empty), Vector.empty, settings.toString)
  }

  test("a platform workload's request is <component>-<suffix> in its own namespace") {
    val by = Requester.Platform("keyring")
    assertEquals(by.namespace, "ankka-keyring")
    assertEquals(by.subject, CloudSubject.platform("keyring"))
    assertEquals(by.name("wrapping-key"), "keyring-wrapping-key")
    assertEquals(by.owner, None)
    // A project called `keyring` would render into the same namespace: the id is reserved.
    assert(Names.ReservedProjectIds.contains("keyring"))
  }

  // Feature 055: named buckets, a public one, one credential over several.

  test("a named public bucket's request is <service>-bucket-<name>, naming it and its exposure") {
    val r = bucket(
      cloud,
      byService,
      Purpose.Service,
      cloud.location,
      BucketAsk(versioning = true, softDeleteDays = 7),
      named = Some(NamedAsk("photos", public = true))
    )
    assertEquals(r.getMetadata.getName, "reports-bucket-photos")
    assertEquals(r.getSpec.kind, CloudKinds.Bucket)
    assertEquals(r.getSpec.subject, CloudSubject("shop", "reports"))
    assertEquals(
      r.getSpec.parameters,
      Map(
        "name"                  -> "photos",
        "public"                -> "true",
        "purpose"               -> "service",
        "location"              -> "europe-west2",
        "versioning"            -> "true",
        "softDeleteDays"        -> "7",
        "corsOrigins"           -> "",
        "kmsKey"                -> "keys/ankka",
        "namePrefix"            -> "",
        "noncurrentVersionDays" -> ""
      )
    )
    ownedBy(r, "AnkkaService", "service-uid")
    holdsTheContract(r)
    val signed = bucket(
      cloud,
      byService,
      Purpose.Service,
      cloud.location,
      BucketAsk(),
      named = Some(NamedAsk("uploads", public = false))
    )
    assertEquals(signed.getSpec.parameters("public"), "false")
    assertEquals(signed.getMetadata.getName, "reports-bucket-uploads")
  }

  test("a credential over three buckets names them all, in order, by buckets") {
    val r = bucketCredential(
      cloud,
      byService,
      Purpose.Service,
      Vector("acme-shop-reports-1", "acme-shop-reports-photos-2", "acme-shop-reports-uploads-3"),
      "reports@my-account.scripted",
      "reports-cloud-storage",
      generation = 1L
    )
    assertEquals(r.getMetadata.getName, "reports-storage-credential")
    assertEquals(
      r.getSpec.parameters,
      Map(
        "buckets"  -> "acme-shop-reports-1,acme-shop-reports-photos-2,acme-shop-reports-uploads-3",
        "identity" -> "reports@my-account.scripted",
        "secretName" -> "reports-cloud-storage"
      )
    )
    assertEquals(bucketsOf(r.getSpec.parameters).size, 3)
    holdsTheContract(r)
  }

  test("the unnamed bucket's request and a one-bucket credential are as they were, byte for byte") {
    // What a provider from before named buckets reads, and what an existing service's requests
    // already say: neither changes, so nothing is asked again of a provider on upgrade.
    val before = Map(
      "purpose"               -> "service",
      "location"              -> "europe-west2",
      "versioning"            -> "false",
      "softDeleteDays"        -> "0",
      "corsOrigins"           -> "",
      "kmsKey"                -> "keys/ankka",
      "namePrefix"            -> "",
      "noncurrentVersionDays" -> ""
    )
    val r = bucket(cloud, byService, Purpose.Service, cloud.location, BucketAsk())
    assertEquals(r.getSpec.parameters, before)
    assertEquals(r.getMetadata.getName, "reports-bucket")
    val c = bucketCredential(cloud, byService, Purpose.Service, Vector("b"), "i", "s", 1L)
    assertEquals(c.getSpec.parameters, Map("bucket" -> "b", "identity" -> "i", "secretName" -> "s"))
    assertEquals(bucketsOf(c.getSpec.parameters), Vector("b"))
  }

  test("a public bucket answers its public address beside its bucket, endpoint and region") {
    assertEquals(
      Keys.outputs(CloudKinds.Bucket),
      Set("bucket", "endpoint", "region", "publicUrl")
    )
  }

  private def everyRequest: Vector[CloudResource] = Vector(
    identity(cloud, byService, "reports"),
    secretAccess(cloud, byService, "i", Vector("a"), Vector("b")),
    secretSync(cloud, byProject, "checkout", Map("A" -> "a"), 1L),
    bucket(cloud, byService, Purpose.Service, cloud.location, BucketAsk()),
    bucketCredential(cloud, byService, Purpose.Service, "b", "i", "reports-storage", 1L),
    wrappingKey(cloud, byProject, "i", "keys/ankka")
  )

  test("no cloud request is in a cloud's own words") {
    val requests = everyRequest
    assertEquals(requests.map(_.getSpec.kind), CloudKinds.all)
    val cloudWords = Vector("google", "gcs", "gcp", "aws", "s3", "azure", "iam", "kms")
    requests.foreach { r =>
      holdsTheContract(r)
      r.getSpec.parameters.keys.foreach { key =>
        cloudWords.foreach(word => assert(!key.toLowerCase.contains(word) || key == "kmsKey", key))
      }
      r.getSpec.parameters.get("location").foreach(l => assertEquals(l, "europe-west2"))
    }
  }

  test("a cloud provider for another cloud changes nothing a request asks but its provider") {
    val other = cloud.copy(provider = "other")
    val a     = everyRequest
    val b     = everyRequest.map { r =>
      val again = CloudResource(r.getMetadata.getNamespace, r.getMetadata.getName, r.getSpec)
      again.setSpec(r.getSpec.copy(provider = other.provider))
      again
    }
    a.zip(b).foreach { (x, y) =>
      assertEquals(x.getSpec.copy(provider = ""), y.getSpec.copy(provider = ""))
      assertNotEquals(x.getSpec.provider, y.getSpec.provider)
    }
  }

  test("the contract's outputs are named for every kind") {
    assertEquals(Keys.outputs.keySet, CloudKinds.all.toSet)
    assertEquals(Keys.parameters.keySet, CloudKinds.all.toSet)
    assertEquals(Keys.outputs(CloudKinds.Bucket), Set("bucket", "endpoint", "region", "publicUrl"))
    assert(Keys.optional.keySet.subsetOf(CloudKinds.all.toSet))
  }

  test("a project's request is labelled with its project and owned by its resource") {
    assertEquals(
      byProject.labels,
      Map(Labels.ManagedByKey -> Labels.ManagedByAnkka, Labels.ProjectKey -> "shop")
    )
    assertEquals(
      new ObjectMetaBuilder().withName(byProject.name("x")).build().getName,
      "shop.x"
    )
  }
