package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.{Container, ObjectMetaBuilder}

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders for a service's bucket (feature 034, `contracts/operator.md`): the two
 * actions that reach the store, before the Deployment; the variables on the developer's container
 * and on no other, for each hosting; and nothing at all for a service that does not ask.
 */
class ObjectStorageRenderingSuite extends munit.FunSuite:

  private val store = ObjectStoreSettings(
    "http://garage.garage-system.svc.cluster.local:3903",
    "token",
    "http://garage.garage-system.svc.cluster.local:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )
  private val settings =
    Settings.default.copy(
      sidecarImage = "ankka-sidecar:9.9.9",
      proxyImage = "ankka-proxy:9.9.9",
      objectStore = Some(store)
    )

  private val asks = AnkkaServiceSpec(
    projectId = "shop",
    serviceName = "reports",
    generation = 1L,
    image = "reports:1",
    port = Some(9000),
    provisionObjectStorage = true
  )

  private val Hostings = Vector("embedded", "process", "wasm", "web")

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("reports").withUid("u").build()
    )
    r.setSpec(spec)
    r

  private def render(
      spec: AnkkaServiceSpec,
      plan: ObjectStoragePlan,
      s: Settings = settings
  ): Vector[Action] =
    Rendering
      .render(resource(spec), s, ProvisioningPlan.Supplied, objectStoragePlan = plan)
      .fold(p => fail(p.mkString("; ")), identity)

  private def containers(actions: Vector[Action]): Vector[Container] =
    actions
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .toVector

  private def storageVariables(c: Container): Map[String, String] =
    c.getEnv.asScala
      .filter(_.getName.startsWith("ANKKA_S3_"))
      .map(e => e.getName -> e.getValue)
      .toMap

  private def storageSecrets(c: Container): Vector[String] =
    Option(c.getEnvFrom)
      .map(_.asScala.toVector)
      .getOrElse(Vector.empty)
      .flatMap(f => Option(f.getSecretRef).map(_.getName))
      .filter(_.endsWith("-storage"))

  private def developers(spec: AnkkaServiceSpec, cs: Vector[Container]): Container =
    if spec.hosting == "process" || spec.hosting == "web" then
      cs.find(_.getName == "reports-app").get
    else cs.head

  test("a service with a bucket ensures the bucket, then its credential, before the Deployment") {
    for plan <- Vector(ObjectStoragePlan.Waiting(None), ObjectStoragePlan.Ready(false)) do
      val actions = render(asks, plan)
      val bucket  = actions.indexWhere(_ == Action.EnsureBucket("shop.reports"))
      val credential = actions.indexWhere {
        case Action.EnsureStorageCredential(
              "ankka-shop",
              "reports-storage",
              _,
              "shop.reports",
              0
            ) =>
          true
        case _ => false
      }
      val deployment = actions.indexWhere(_.isInstanceOf[Action.ApplyDeployment])
      assert(bucket >= 0 && bucket < credential && credential < deployment, actions.map(_.describe))
  }

  test("the credential's action is labelled with the service's identity and carries no key") {
    val action = render(asks, ObjectStoragePlan.Ready(false)).collectFirst {
      case a: Action.EnsureStorageCredential => a
    }.get
    assertEquals(action.labels, Labels.identity("shop", "reports"))
    assertEquals(
      action.describe,
      "ensure storage credential ankka-shop/reports-storage for bucket shop.reports (create-if-absent)"
    )
  }

  test(
    "with no store, a store that cannot be reached, or nothing asked, nothing reaches the store"
  ) {
    for plan <- Vector(
        ObjectStoragePlan.Waiting(Some("down")),
        ObjectStoragePlan.Failed(Vector(ObjectStorage.NoStore)),
        ObjectStoragePlan.NotAsked,
        ObjectStoragePlan.Supplied
      )
    do
      val actions = render(asks, plan)
      assert(
        !actions.exists {
          case _: Action.EnsureBucket | _: Action.EnsureStorageCredential => true
          case _                                                          => false
        },
        s"$plan: ${actions.map(_.describe)}"
      )
  }

  test("for every hosting, the variables are on the developer's container and on no other") {
    for hosting <- Hostings do
      val spec = asks.copy(hosting = hosting)
      val cs   = containers(render(spec, ObjectStoragePlan.Ready(false)))
      val dev  = developers(spec, cs)
      assertEquals(
        storageVariables(dev),
        Map(
          "ANKKA_S3_ENDPOINT" -> store.endpoint,
          "ANKKA_S3_REGION"   -> "garage",
          "ANKKA_S3_BUCKET"   -> "shop.reports"
        ),
        hosting
      )
      assertEquals(storageSecrets(dev), Vector("reports-storage"), hosting)
      for other <- cs if other ne dev do
        assertEquals(
          storageVariables(other),
          Map.empty[String, String],
          s"$hosting: ${other.getName}"
        )
        assertEquals(storageSecrets(other), Vector.empty[String], s"$hosting: ${other.getName}")
  }

  test(
    "for every hosting, a service that does not ask has no variable and no credential anywhere"
  ) {
    for hosting <- Hostings do
      val cs = containers(
        render(
          asks.copy(hosting = hosting, provisionObjectStorage = false),
          ObjectStoragePlan.NotAsked
        )
      )
      for c <- cs do
        assertEquals(storageVariables(c), Map.empty[String, String], s"$hosting: ${c.getName}")
        assertEquals(storageSecrets(c), Vector.empty[String], s"$hosting: ${c.getName}")
  }

  test("with no store, the credential's Secret is still named, so no instance starts without one") {
    val cs = containers(
      render(asks, ObjectStoragePlan.Failed(Vector(ObjectStorage.NoStore)), Settings.default)
    )
    val dev = cs.head
    assertEquals(storageSecrets(dev), Vector("reports-storage"))
    assertEquals(storageVariables(dev), Map("ANKKA_S3_BUCKET" -> "shop.reports"))
  }

  // A bucket reachable from the internet (US4).

  private val exposing = asks.copy(exposeObjectStorage = true)
  private val withBase = settings.copy(baseDomain = Some("example.com"), httpsPort = 8443)

  private def routes(actions: Vector[Action]) =
    actions.collect {
      case Action.EnsureHttpRoute(r) if r.getMetadata.getName == "reports-storage" => r
    }

  private def grants(actions: Vector[Action]) =
    actions.collect { case Action.EnsureReferenceGrant(g) => g }

  private def removesRoute(actions: Vector[Action]) =
    actions.contains(Action.RemoveHttpRoute("ankka-shop", "reports-storage", "u"))

  test("a reachable bucket's grant, then its route, field by field") {
    for plan <- Vector(ObjectStoragePlan.Waiting(None), ObjectStoragePlan.Ready(true)) do
      val actions = render(exposing, plan, withBase)
      val grant   = grants(actions).head
      assertEquals(grant.getApiVersion, "gateway.networking.k8s.io/v1beta1")
      assertEquals(grant.getKind, "ReferenceGrant")
      assertEquals(grant.getMetadata.getNamespace, "garage-system")
      assertEquals(grant.getMetadata.getName, "ankka-shop")
      assert(
        grant.getMetadata.getOwnerReferences == null || grant.getMetadata.getOwnerReferences.isEmpty
      )
      val spec =
        grant.getAdditionalProperties.get("spec").asInstanceOf[java.util.Map[String, AnyRef]]
      assertEquals(
        spec.get("from").toString,
        "[{group=gateway.networking.k8s.io, kind=HTTPRoute, namespace=ankka-shop}]"
      )
      assertEquals(spec.get("to").toString, "[{group=, kind=Service, name=garage}]")

      val route = routes(actions).head
      assertEquals(route.getMetadata.getNamespace, "ankka-shop")
      assertEquals(route.getMetadata.getOwnerReferences.asScala.map(_.getUid).toVector, Vector("u"))
      assertEquals(route.getSpec.getHostnames.asScala.toVector, Vector("storage.example.com"))
      val parent = route.getSpec.getParentRefs.get(0)
      assertEquals(
        (parent.getName, parent.getNamespace, parent.getSectionName),
        ("ankka", "ankka-gateway", "https")
      )
      val rule = route.getSpec.getRules.asScala.toVector match
        case Vector(only) => only
        case other        => fail(s"expected one rule, got $other")
      val path = rule.getMatches.get(0).getPath
      assertEquals((path.getType, path.getValue), ("PathPrefix", "/shop.reports"))
      assertEquals(rule.getTimeouts.getRequest, "0s")
      val backend = rule.getBackendRefs.get(0)
      assertEquals(
        (backend.getName, backend.getNamespace, backend.getPort.intValue),
        ("garage", "garage-system", 3900)
      )
      assert(
        actions.indexOf(Action.EnsureReferenceGrant(grant)) < actions.indexWhere(
          _ == Action.EnsureHttpRoute(route)
        )
      )
      assert(
        !actions.exists {
          case Action.EnsureBackendTlsPolicy(p) => p.getMetadata.getName == "reports-storage"
          case _                                => false
        }
      )
  }

  test("the store's address on the internet is on the developer's container alone") {
    for hosting <- Hostings do
      val spec = exposing.copy(hosting = hosting)
      val cs   = containers(render(spec, ObjectStoragePlan.Ready(false), withBase))
      val dev  = developers(spec, cs)
      assertEquals(
        storageVariables(dev).get("ANKKA_S3_PUBLIC_ENDPOINT"),
        Some("https://storage.example.com:8443"),
        hosting
      )
      for other <- cs if other ne dev do
        assertEquals(
          storageVariables(other),
          Map.empty[String, String],
          s"$hosting: ${other.getName}"
        )
  }

  test("a bucket not asked to be reachable has its route removed, no grant, and no address") {
    val actions = render(asks, ObjectStoragePlan.Ready(false), withBase)
    assert(removesRoute(actions))
    assertEquals(grants(actions), Vector.empty)
    assertEquals(storageVariables(containers(actions).head).get("ANKKA_S3_PUBLIC_ENDPOINT"), None)
  }

  test("a service that asks for nothing has the route's removal rendered, and nothing else of it") {
    val actions =
      render(asks.copy(provisionObjectStorage = false), ObjectStoragePlan.NotAsked, withBase)
    assert(removesRoute(actions))
    assertEquals(grants(actions), Vector.empty)
    assertEquals(routes(actions), Vector.empty)
  }

  test("with no base domain there is no route and no address, even when asked") {
    val actions = render(exposing, ObjectStoragePlan.Ready(false), settings)
    assert(removesRoute(actions))
    assertEquals(storageVariables(containers(actions).head).get("ANKKA_S3_PUBLIC_ENDPOINT"), None)
  }

  test(
    "a store that cannot be reached still has its route rendered: the route reaches the store, not the operator"
  ) {
    val actions = render(exposing, ObjectStoragePlan.Waiting(Some("down")), withBase)
    assertEquals(routes(actions).size, 1)
  }

  test("a failed plan has no route") {
    val actions = render(exposing, ObjectStoragePlan.Failed(Vector("x")), withBase)
    assert(removesRoute(actions))
    assertEquals(routes(actions), Vector.empty)
  }

  // Feature 039: a credential issued again, on Garage.

  private def withStatus(spec: AnkkaServiceSpec, inPlace: Int): AnkkaService =
    val r = resource(spec)
    r.setStatus(
      com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus(objectStorage =
        Some(
          com.thinkmorestupidless.ankka.crd
            .ObjectStorageStatus(phase = "Provisioned", credentialGeneration = inPlace)
        )
      )
    )
    r

  private def renderOn(r: AnkkaService, plan: ObjectStoragePlan): Vector[Action] =
    Rendering
      .render(r, settings, ProvisioningPlan.Supplied, objectStoragePlan = plan)
      .fold(p => fail(p.mkString("; ")), identity)

  private def credentialAnnotation(actions: Vector[Action]): Option[String] =
    actions
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .flatMap(d => Option(d.getSpec.getTemplate.getMetadata.getAnnotations))
      .flatMap(a => Option(a.get(Labels.StorageCredentialKey)))

  private val ready = ObjectStoragePlan.Ready(recovered = false)

  test("a credential never issued again renders as before: no annotation, no re-issue, no sweep") {
    val actions = render(asks, ready)
    assertEquals(credentialAnnotation(actions), None)
    assert(!actions.exists(_.isInstanceOf[Action.ReissueStorageCredential]), actions.toString)
    assert(!actions.exists(_.isInstanceOf[Action.DeleteExpiredKeys]), actions.toString)
    assertEquals(
      actions.collect { case a: Action.EnsureStorageCredential => a.generation },
      Vector(0)
    )
  }

  test(
    "a generation asked above the one in place is issued before the Deployment that rolls onto it"
  ) {
    val actions =
      renderOn(withStatus(asks.copy(storageCredentialGeneration = 2), inPlace = 1), ready)
    val reissue = actions.indexWhere {
      case Action.ReissueStorageCredential(_, "reports-storage", "shop.reports", 2) => true
      case _                                                                        => false
    }
    assert(reissue >= 0, actions.toString)
    assert(reissue < actions.indexWhere(_.isInstanceOf[Action.ApplyDeployment]), actions.toString)
    assert(!actions.exists(_.isInstanceOf[Action.EnsureStorageCredential]), actions.toString)
    assertEquals(credentialAnnotation(actions), Some("2"))
    assert(actions.contains(Action.DeleteExpiredKeys("shop.reports")), actions.toString)
  }

  test("a generation in place is ensured, not issued again, and the pods stay on it") {
    val actions =
      renderOn(withStatus(asks.copy(storageCredentialGeneration = 2), inPlace = 2), ready)
    assert(!actions.exists(_.isInstanceOf[Action.ReissueStorageCredential]), actions.toString)
    assertEquals(
      actions.collect { case a: Action.EnsureStorageCredential => a.generation },
      Vector(2)
    )
    assertEquals(credentialAnnotation(actions), Some("2"))
  }

  test(
    "a store that cannot be reached issues nothing, and the pods stay on the generation in place"
  ) {
    val actions = renderOn(
      withStatus(asks.copy(storageCredentialGeneration = 3), inPlace = 2),
      ObjectStoragePlan.Waiting(Some("down"))
    )
    assert(!actions.exists(_.isInstanceOf[Action.ReissueStorageCredential]), actions.toString)
    assertEquals(credentialAnnotation(actions), Some("2"))
  }

  test("the status reports the generation the pass leaves in place") {
    val spec = asks.copy(storageCredentialGeneration = 2)
    assertEquals(
      ObjectStorage
        .status(ready, spec, settings, reported = None, inPlace = 1)
        .map(_.credentialGeneration),
      Some(2)
    )
    assertEquals(
      ObjectStorage
        .status(
          ObjectStoragePlan.Waiting(Some("down")),
          spec,
          settings,
          reported = None,
          inPlace = 1
        )
        .map(_.credentialGeneration),
      Some(1)
    )
  }

  // Feature 039: the platform sets a Garage bucket's CORS rule, from the descriptor.

  test("an exposed bucket's rule admits the descriptor's origins, and any other bucket has none") {
    val origins = List("https://play.example")
    val exposed = asks.copy(exposeObjectStorage = true, objectStorageOrigins = origins)
    assert(render(exposed, ready).contains(Action.SetBucketCors("shop.reports", origins)))
    // Not exposed: the origins name nobody until it is, and a rule left from before is removed.
    assert(
      render(asks.copy(objectStorageOrigins = origins), ready)
        .contains(Action.SetBucketCors("shop.reports", Nil))
    )
  }

  // A bucket in the installation's cloud account (feature 044).

  private val cloud = CloudSettings(
    "gcp",
    "acct",
    "europe-west2",
    None,
    scala.concurrent.duration.Duration(2, "minutes"),
    scala.concurrent.duration.Duration(1, "hour")
  )
  private val withCloud = settings.copy(objectStore = None, cloud = Some(cloud))
  private val cloudReady: ObjectStoragePlan.Ready = ObjectStoragePlan.Ready(
    recovered = false,
    Some(CloudBucket("acct-shop-reports", "https://storage.scripted.invalid", "europe-west2", 2L))
  )

  private def renderCloud(
      spec: AnkkaServiceSpec,
      plan: ObjectStoragePlan,
      identity: Option[String] = None,
      bucket: Option[String] = None
  ): Vector[Action] =
    val r = resource(spec)
    Rendering
      .render(
        r,
        withCloud,
        ProvisioningPlan.Supplied,
        objectStoragePlan = plan,
        cloudRequests =
          ObjectStorage.cloudRequests(r, withCloud, cloud, None, None, identity, bucket)
      )
      .fold(p => fail(p.mkString("; ")), identity => identity)

  private def cloudRequestNames(actions: Vector[Action]): Vector[String] =
    actions.collect { case Action.EnsureCloudResource(r) => r.getMetadata.getName }

  // Feature 039 (research R1a D3): what the descriptor and the installation ask of the bucket.

  private def bucketRequestOf(
      spec: AnkkaServiceSpec,
      settings: Settings = withCloud,
      projectLocation: Option[String] = None,
      existing: Option[CloudObservation] = None
  ) =
    ObjectStorage
      .cloudRequests(
        resource(spec),
        settings,
        settings.cloud.getOrElse(cloud),
        projectLocation,
        existing,
        None,
        None
      )
      .collectFirst { case r if r.getSpec.kind == "bucket" => r }
      .get

  private def bucketAsked(
      spec: AnkkaServiceSpec,
      settings: Settings = withCloud,
      projectLocation: Option[String] = None
  ): Map[String, String] = bucketRequestOf(spec, settings, projectLocation).getSpec.parameters

  /** A bucket request already written, as asked then and stamped with a settings generation. */
  private def existing(stamped: Int, parameters: (String, String)*): Option[CloudObservation] =
    Some(
      CloudObservation(
        generation = 1L,
        createdAt = java.time.Instant.EPOCH,
        status = None,
        spec =
          Some(com.thinkmorestupidless.ankka.crd.CloudResourceSpec(parameters = parameters.toMap)),
        annotations = Map(Labels.SettingsGenerationKey -> stamped.toString)
      )
    )

  private def settingsOf(r: com.thinkmorestupidless.ankka.crd.CloudResource) =
    (
      r.getSpec.parameters("softDeleteDays"),
      r.getSpec.parameters("kmsKey"),
      r.getMetadata.getAnnotations.asScala.get(Labels.SettingsGenerationKey)
    )

  private val now30 = withCloud.copy(gcs = Some(GcsSettings("t", 30)))
  private val keyed = cloud.copy(kmsKey = Some("keys/new"))

  test("settings: a bucket asked for the first time takes the installation's window and key") {
    val r = bucketRequestOf(asks, now30.copy(cloud = Some(keyed)))
    assertEquals(settingsOf(r), ("30", "keys/new", Some("0")))
  }

  test("settings: a bucket already asked keeps its window and key when the installation's change") {
    val was =
      existing(0, "softDeleteDays" -> "7", "kmsKey" -> "keys/old", "location" -> "europe-west2")
    val r = bucketRequestOf(asks, now30.copy(cloud = Some(keyed)), existing = was)
    assertEquals(settingsOf(r), ("7", "keys/old", Some("0")))
  }

  test("settings: a member's raised generation takes the installation's again, and stamps it") {
    val was =
      existing(0, "softDeleteDays" -> "7", "kmsKey" -> "keys/old", "location" -> "europe-west2")
    val r = bucketRequestOf(
      asks.copy(objectStorageSettingsGeneration = 1),
      now30.copy(cloud = Some(keyed)),
      existing = was
    )
    assertEquals(settingsOf(r), ("30", "keys/new", Some("1")))
  }

  test("settings: a bucket's location is never rewritten, whatever its project says now") {
    val was = existing(0, "softDeleteDays" -> "7", "kmsKey" -> "", "location" -> "us-east1")
    val r = bucketRequestOf(
      asks.copy(objectStorageSettingsGeneration = 1),
      projectLocation = Some("europe-west2"),
      existing = was
    )
    assertEquals(r.getSpec.parameters("location"), "us-east1")
  }

  test("cloud: a bucket keeps versions, with the installation's soft-delete window and prefix") {
    val p = bucketAsked(asks, withCloud.copy(gcs = Some(GcsSettings("acme", 30))))
    assertEquals(p("versioning"), "true")
    assertEquals(p("softDeleteDays"), "30")
    assertEquals(p("namePrefix"), "acme")
    // With no settings of Google Cloud Storage's, the shipped window and no prefix.
    assertEquals(bucketAsked(asks)("softDeleteDays"), "7")
    assertEquals(bucketAsked(asks)("namePrefix"), "")
  }

  test(
    "cloud: the descriptor's origins reach the bucket only while it is reachable from the internet"
  ) {
    val origins = List("https://play.example")
    assertEquals(
      bucketAsked(asks.copy(exposeObjectStorage = true, objectStorageOrigins = origins))(
        "corsOrigins"
      ),
      "https://play.example"
    )
    assertEquals(bucketAsked(asks.copy(objectStorageOrigins = origins))("corsOrigins"), "")
  }

  test("cloud: a noncurrent version's age is the descriptor's, and empty when it names none") {
    assertEquals(
      bucketAsked(asks.copy(objectStorageVersionAgeDays = Some(30)))("noncurrentVersionDays"),
      "30"
    )
    assertEquals(bucketAsked(asks)("noncurrentVersionDays"), "")
  }

  test("cloud: a bucket is made in its project's location when the project names one") {
    assertEquals(bucketAsked(asks)("location"), cloud.location)
    assertEquals(bucketAsked(asks, projectLocation = Some("us-east1"))("location"), "us-east1")
  }

  test(
    "cloud: the status names the bucket, its store and its location as the provider reported them"
  ) {
    val ready = ObjectStoragePlan.Ready(
      recovered = false,
      Some(CloudBucket("t-shop-reports-1", "https://e", "auto", 1L, location = "europe-west2"))
    )
    val s = ObjectStorage.status(ready, asks, withCloud, None).get
    assertEquals(s.store, "gcs")
    assertEquals(s.bucket, "t-shop-reports-1")
    assertEquals(s.location, Some("europe-west2"))
    // Until the provider has answered, it names no location.
    assertEquals(
      ObjectStorage.status(ObjectStoragePlan.Waiting(None), asks, withCloud, None).get.location,
      None
    )
  }

  test("cloud: an exposed bucket is told, and reports, the cloud's own address for it") {
    val exposed = asks.copy(exposeObjectStorage = true)
    val cs      = containers(renderCloud(exposed, cloudReady))
    assertEquals(
      storageVariables(developers(exposed, cs)).get("ANKKA_S3_PUBLIC_ENDPOINT"),
      Some("https://storage.scripted.invalid")
    )
    assertEquals(
      ObjectStorage.status(cloudReady, exposed, withCloud, None).flatMap(_.publicAddress),
      Some("https://storage.scripted.invalid/acct-shop-reports")
    )
    // Not exposed: neither.
    assertEquals(
      storageVariables(developers(asks, containers(renderCloud(asks, cloudReady))))
        .get("ANKKA_S3_PUBLIC_ENDPOINT"),
      None
    )
    assertEquals(
      ObjectStorage.status(cloudReady, asks, withCloud, None).flatMap(_.publicAddress),
      None
    )
  }

  test("cloud: the status says how long a deleted object is kept, as the bucket was asked") {
    val settings = withCloud.copy(gcs = Some(GcsSettings("t", 30)))
    assertEquals(
      ObjectStorage.status(cloudReady, asks, settings, None).flatMap(_.softDeleteDays),
      Some(30)
    )
  }

  test("cloud: the ServiceAccount carries what the cloud provider said binds it to its identity") {
    val annotations =
      Map("iam.gke.io/gcp-service-account" -> "reports@acct.iam.gserviceaccount.com")
    val actions = Rendering
      .render(
        resource(asks),
        withCloud,
        ProvisioningPlan.Supplied,
        objectStoragePlan = cloudReady,
        serviceAccountAnnotations = annotations
      )
      .fold(p => fail(p.mkString("; ")), identity => identity)
    val account = actions.collectFirst { case Action.EnsureServiceAccount(sa) => sa }.get
    assertEquals(account.getMetadata.getAnnotations.asScala.toMap, annotations)
    // Every other ServiceAccount is rendered as it was: no annotations at all.
    val plain = render(asks, ObjectStoragePlan.Ready(recovered = false)).collectFirst {
      case Action.EnsureServiceAccount(sa) => sa
    }.get
    assertEquals(Option(plain.getMetadata.getAnnotations).map(_.size).getOrElse(0), 0)
  }

  test("cloud: the identity's annotations are read from its answer, and none before it") {
    val answered = CloudBucketPlans(
      CloudPlan.Ready(
        Map(
          "identity"                  -> "reports@acct.scripted",
          "serviceAccountAnnotations" -> "a.example/one=x, b.example/two=y=z"
        ),
        recovered = false,
        credentialGeneration = None
      ),
      CloudPlan.Waiting(None),
      None
    )
    assertEquals(
      ObjectStorage.serviceAccountAnnotations(answered),
      Map("a.example/one" -> "x", "b.example/two" -> "y=z")
    )
    assertEquals(
      ObjectStorage.serviceAccountAnnotations(answered.copy(identity = CloudPlan.Waiting(None))),
      Map.empty[String, String]
    )
  }

  test("cloud: a waiting bucket asks for an identity and a bucket, and starts no instance") {
    val actions = renderCloud(asks, ObjectStoragePlan.Waiting(None))
    assertEquals(cloudRequestNames(actions), Vector("reports-identity", "reports-bucket"))
    assert(!actions.exists(_.isInstanceOf[Action.ApplyDeployment]), "no instance waits on a guess")
    assert(!actions.exists(_.isInstanceOf[Action.EnsureBucket]), "the store is not asked")
    assert(!actions.exists(_.isInstanceOf[Action.EnsureStorageCredential]))
  }

  test("cloud: the credential is asked for once the identity and the bucket are answered") {
    val actions = renderCloud(
      asks,
      ObjectStoragePlan.Waiting(None),
      identity = Some("reports@acct.scripted"),
      bucket = Some("acct-shop-reports")
    )
    assertEquals(
      cloudRequestNames(actions),
      Vector("reports-identity", "reports-bucket", "reports-storage-credential")
    )
    val credential = actions.collectFirst {
      case Action.EnsureCloudResource(r) if r.getSpec.kind == "bucket-credential" => r
    }.get
    assertEquals(credential.getSpec.parameters("identity"), "reports@acct.scripted")
    assertEquals(credential.getSpec.parameters("bucket"), "acct-shop-reports")
    assertEquals(credential.getSpec.parameters("secretName"), "reports-cloud-storage")
    assertEquals(credential.getSpec.credentialGeneration, 1L)
  }

  test("cloud: a ready bucket's variables are the provider's answer and its credential's Secret") {
    for hosting <- Hostings do
      val spec      = asks.copy(hosting = hosting)
      val cs        = containers(renderCloud(spec, cloudReady))
      val developer = developers(spec, cs)
      assertEquals(
        storageVariables(developer),
        Map(
          "ANKKA_S3_ENDPOINT" -> "https://storage.scripted.invalid",
          "ANKKA_S3_REGION"   -> "europe-west2",
          "ANKKA_S3_BUCKET"   -> "acct-shop-reports"
        ),
        hosting
      )
      assertEquals(storageSecrets(developer), Vector("reports-cloud-storage"), hosting)
      cs.filterNot(_ eq developer).foreach { other =>
        assertEquals(
          storageVariables(other),
          Map.empty[String, String],
          s"$hosting: ${other.getName}"
        )
      }
  }

  test("cloud: the credential's generation is on the pod template, so a new one rolls the pods") {
    def annotation(plan: ObjectStoragePlan) =
      renderCloud(asks, plan)
        .collectFirst { case Action.ApplyDeployment(d) => d }
        .flatMap(d => Option(d.getSpec.getTemplate.getMetadata.getAnnotations))
        .flatMap(a => Option(a.get(Labels.StorageCredentialKey)))
    assertEquals(annotation(cloudReady), Some("2"))
    val third = cloudReady.copy(cloud = cloudReady.cloud.map(_.copy(credentialGeneration = 3L)))
    assertEquals(annotation(third), Some("3"))
    val a = renderCloud(asks, cloudReady).collectFirst { case Action.ApplyDeployment(d) => d }.get
    val b = renderCloud(asks, third).collectFirst { case Action.ApplyDeployment(d) => d }.get
    a.getSpec.getTemplate.getMetadata.getAnnotations.remove(Labels.StorageCredentialKey)
    b.getSpec.getTemplate.getMetadata.getAnnotations.remove(Labels.StorageCredentialKey)
    assertEquals(a, b, "the generation changes the template and nothing else")
  }

  test("cloud: a refused bucket starts the service told of no bucket at all") {
    val cs =
      containers(renderCloud(asks, ObjectStoragePlan.Failed(Vector("the location is refused"))))
    cs.foreach(c => assertEquals(storageVariables(c), Map.empty[String, String], c.getName))
    cs.foreach(c => assertEquals(storageSecrets(c), Vector.empty[String], c.getName))
  }

  test("cloud: the installation's own store renders no cloud request and no generation") {
    for plan <- Vector(ObjectStoragePlan.Waiting(None), ObjectStoragePlan.Ready(false)) do
      val actions = render(asks, plan)
      assertEquals(cloudRequestNames(actions), Vector.empty[String])
      val template =
        actions.collectFirst { case Action.ApplyDeployment(d) => d }.get.getSpec.getTemplate
      assert(
        !template.getMetadata.getAnnotations.containsKey(Labels.StorageCredentialKey)
      )
  }

  test("cloud: a cloud bucket has no route through the installation's gateway") {
    val exposing = asks.copy(exposeObjectStorage = true)
    val actions  = renderCloud(exposing, cloudReady)
    assert(!actions.exists(_.isInstanceOf[Action.EnsureHttpRoute]))
    assert(!actions.exists(_.isInstanceOf[Action.EnsureReferenceGrant]))
  }
