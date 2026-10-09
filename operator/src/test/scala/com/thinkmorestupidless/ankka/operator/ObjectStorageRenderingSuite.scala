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
        case Action.EnsureStorageCredential("ankka-shop", "reports-storage", _, "shop.reports") =>
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
        cloudRequests = ObjectStorage.cloudRequests(r, cloud, identity, bucket)
      )
      .fold(p => fail(p.mkString("; ")), identity => identity)

  private def cloudRequestNames(actions: Vector[Action]): Vector[String] =
    actions.collect { case Action.EnsureCloudResource(r) => r.getMetadata.getName }

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
    assertEquals(credential.getSpec.parameters("secretName"), "reports-storage")
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
      assertEquals(storageSecrets(developer), Vector("reports-storage"), hosting)
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
        .flatMap(a => Option(a.get(Labels.StorageCredentialGenerationKey)))
    assertEquals(annotation(cloudReady), Some("2"))
    val third = cloudReady.copy(cloud = cloudReady.cloud.map(_.copy(credentialGeneration = 3L)))
    assertEquals(annotation(third), Some("3"))
    val a = renderCloud(asks, cloudReady).collectFirst { case Action.ApplyDeployment(d) => d }.get
    val b = renderCloud(asks, third).collectFirst { case Action.ApplyDeployment(d) => d }.get
    a.getSpec.getTemplate.getMetadata.getAnnotations.remove(Labels.StorageCredentialGenerationKey)
    b.getSpec.getTemplate.getMetadata.getAnnotations.remove(Labels.StorageCredentialGenerationKey)
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
        !template.getMetadata.getAnnotations.containsKey(Labels.StorageCredentialGenerationKey)
      )
  }

  test("cloud: a cloud bucket has no route through the installation's gateway") {
    val exposing = asks.copy(exposeObjectStorage = true)
    val actions  = renderCloud(exposing, cloudReady)
    assert(!actions.exists(_.isInstanceOf[Action.EnsureHttpRoute]))
    assert(!actions.exists(_.isInstanceOf[Action.EnsureReferenceGrant]))
  }
