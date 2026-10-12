package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaProjectSpec,
  AnkkaService,
  AnkkaServiceSpec,
  CloudKinds,
  CloudResource,
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
    assertEquals(keys(r), Keys.parameters(r.getSpec.kind), s"${r.getSpec.kind}'s keys")
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

  test("a keyring on an installation that names a wrapping key asks to wrap with it") {
    val r = wrappingKey(cloud, byProject, "shop-keyring@my-account.scripted", "keys/ankka")
    assertEquals(r.getMetadata.getName, "shop.wrapping-key")
    assertEquals(
      r.getSpec.parameters,
      Map("identity" -> "shop-keyring@my-account.scripted", "key" -> "keys/ankka")
    )
    holdsTheContract(r)
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
    val b = everyRequest.map { r =>
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
    assertEquals(Keys.outputs(CloudKinds.Bucket), Set("bucket", "endpoint", "region"))
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
