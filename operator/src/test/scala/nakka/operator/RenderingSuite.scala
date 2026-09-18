package nakka.operator

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import nakka.crd.{EnvEntry, NakkaService, NakkaServiceSpec}

import scala.jdk.CollectionConverters.*

/**
 * Rendering is a total function over data, so all of it is provable here — no cluster, no Docker.
 *
 * Three of these are regression tests for mistakes that are permanent if they ship: a generation in
 * the selector bricks the service at generation 2, a rendered autoscaler corrupts the journal, and
 * an overridable identity label lets one service disown itself.
 */
class RenderingSuite extends munit.FunSuite:

  private val settings = Settings.default

  private def resource(
      spec: NakkaServiceSpec,
      uid: String = "uid-1",
      name: String = "cart",
      namespace: String = "nakka-checkout"
  ): NakkaService =
    val r = new NakkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace(namespace).withName(name).withUid(uid).build()
    )
    r.setSpec(spec)
    r

  private val spec = NakkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    image = "registry.example.com/acme/cart:1.4.2",
    cpuMillis = 500,
    memoryMiB = 512
  )

  private def deploymentFor(s: NakkaServiceSpec, uid: String = "uid-1") =
    Rendering.render(resource(s, uid), settings, ProvisioningPlan.Supplied, "unused") match
      case Right(actions) =>
        actions
          .collectFirst { case Action.ApplyDeployment(d) => d }
          .getOrElse(fail("no deployment was rendered"))
      case Left(problems) => fail(s"rendering failed: ${problems.mkString("; ")}")

  test("a namespace is ensured before the workload that goes in it") {
    val Right(actions) =
      Rendering.render(resource(spec), settings, ProvisioningPlan.Supplied, "unused"): @unchecked
    assertEquals(actions.head, Action.EnsureNamespace("nakka-checkout"))
  }

  test("rendering is deterministic") {
    // Comparing desired against observed is only meaningful if the same input renders the
    // same output. An accidental Instant.now() would make every pass look like a change.
    assertEquals(deploymentFor(spec), deploymentFor(spec))
  }

  test("the selector never contains the generation") {
    // spec.selector is immutable after creation. A value that changes every apply would
    // make the second apply permanently rejected, bricking the service at generation 2.
    val selector = deploymentFor(spec).getSpec.getSelector.getMatchLabels.asScala
    assert(!selector.contains(Labels.GenerationKey), s"generation leaked into selector: $selector")
    assertEquals(selector.keySet.toSet, Labels.identity("checkout", "cart").keySet)
  }

  test("the selector is identical across generations, so a second apply is accepted") {
    val first  = deploymentFor(spec).getSpec.getSelector
    val second = deploymentFor(spec.copy(generation = 99L)).getSpec.getSelector
    assertEquals(first, second)
  }

  test("the restart count reaches the pod template, which is what makes a restart roll") {
    val template = deploymentFor(spec).getSpec.getTemplate.getMetadata.getAnnotations.asScala
    assertEquals(template.get(Labels.RestartsKey), Some("0"))
    val rolled = deploymentFor(spec.copy(restarts = 1))
    assertEquals(
      rolled.getSpec.getTemplate.getMetadata.getAnnotations.asScala.get(Labels.RestartsKey),
      Some("1")
    )
  }

  test(
    "the generation does NOT reach the pod template, so an apply that changes nothing there rolls nothing"
  ) {
    // Feature 001 put it there, so every apply — a pure scale included — rolled every pod
    // (feature 004, FR-016). It stays on the Deployment's own metadata, where status reads it.
    val one = deploymentFor(spec)
    val two = deploymentFor(spec.copy(generation = 5L))
    assertEquals(one.getSpec.getTemplate, two.getSpec.getTemplate)
    assertEquals(two.getMetadata.getAnnotations.get(Labels.GenerationKey), "5")
    assert(!two.getSpec.getTemplate.getMetadata.getAnnotations.containsKey(Labels.GenerationKey))
  }

  test("a change of instance count changes nothing on the pod template") {
    val three = deploymentFor(spec.copy(autoscaling = spec.autoscaling.copy(minInstances = 3)))
    val five = deploymentFor(
      spec.copy(generation = 9L, autoscaling = spec.autoscaling.copy(minInstances = 5))
    )
    assertEquals(three.getSpec.getTemplate, five.getSpec.getTemplate)
  }
  test("the instance count is the descriptor's minInstances, honoured at last") {
    assertEquals(deploymentFor(spec).getSpec.getReplicas.intValue, 1, "default")
    val three = spec.copy(autoscaling = spec.autoscaling.copy(minInstances = 3))
    assertEquals(deploymentFor(three).getSpec.getReplicas.intValue, 3)
    // Paused is paused, however many were asked for.
    assertEquals(deploymentFor(three.copy(paused = true)).getSpec.getReplicas.intValue, 0)
  }

  test("no autoscaler is rendered — the maximum and the CPU target stay carried and unhonoured") {
    val Right(actions) =
      Rendering.render(resource(spec), settings, ProvisioningPlan.Supplied, "unused"): @unchecked
    // Stated as what it means rather than as a count: nothing is rendered beyond the namespace,
    // the identity, the Deployment and the service's address. An autoscaler would be another
    // kind of thing — and scaling a sharded cluster on a load signal needs draining proven first.
    val unexpected = actions.filterNot {
      case _: Action.EnsureNamespace | _: Action.ApplyDeployment | _: Action.EnsureService |
          _: Action.RemoveService | _: Action.EnsureServiceAccount | _: Action.EnsureRole |
          _: Action.EnsureRoleBinding | _: Action.EnsureHttpRoute | _: Action.RemoveHttpRoute =>
        true
      case _ => false
    }
    assertEquals(unexpected, Vector.empty, s"rendered something that is not one of those: $actions")
  }

  test("the rollout strategy is RollingUpdate, one at a time, at every instance count") {
    // Feature 003 rendered Recreate, and was right to: a node then joined *itself*, so the surge
    // pod of a rolling update was a second single-node cluster writing the same journal. Feature
    // 004 removes the cause — a surge pod now joins the existing cluster and is ready before an
    // old pod leaves — and with it the reason, and with that the outage Recreate cost every
    // deploy. Measured on a real cluster: one cluster throughout, at three instances and at one,
    // never fewer than one ready pod (research R6). maxSurge 1 / maxUnavailable 0 is what makes
    // "one at a time" literal.
    for count <- Vector(1, 3) do
      val s        = spec.copy(autoscaling = spec.autoscaling.copy(minInstances = count))
      val strategy = deploymentFor(s).getSpec.getStrategy
      assertEquals(strategy.getType, "RollingUpdate", s"at $count")
      assertEquals(strategy.getRollingUpdate.getMaxSurge.getIntVal.intValue, 1, s"at $count")
      assertEquals(strategy.getRollingUpdate.getMaxUnavailable.getIntVal.intValue, 0, s"at $count")
  }

  test("a paused service renders zero replicas and keeps its configuration") {
    val paused = deploymentFor(spec.copy(paused = true))
    assertEquals(paused.getSpec.getReplicas.intValue, 0)
    assertEquals(
      paused.getSpec.getTemplate.getSpec.getContainers.get(0).getImage,
      "registry.example.com/acme/cart:1.4.2"
    )
  }

  test("every object carries an owner reference with the resource's uid") {
    // uid rather than name: a resource deleted and recreated under the same name gets a new
    // uid, and children of the old one are collected rather than silently adopted.
    val owners = deploymentFor(spec, uid = "uid-7").getMetadata.getOwnerReferences.asScala
    assertEquals(owners.size, 1)
    assertEquals(owners.head.getUid, "uid-7")
    assertEquals(owners.head.getKind, "NakkaService")
    assertEquals(owners.head.getController.booleanValue, true)
  }

  test("a descriptor label cannot override an identity label") {
    // Otherwise a descriptor could disown itself, or impersonate another service.
    val hostile = spec.copy(labels =
      Map(Labels.ManagedByKey -> "someone-else", Labels.ServiceKey -> "payments", "team" -> "cx")
    )
    val labels = deploymentFor(hostile).getMetadata.getLabels.asScala
    assertEquals(labels.get(Labels.ManagedByKey), Some("nakka"))
    assertEquals(labels.get(Labels.ServiceKey), Some("cart"))
    assertEquals(labels.get("team"), Some("cx"))
  }

  test("resources come from the resolved numbers, with requests equal to limits") {
    val container = deploymentFor(
      spec.copy(cpuMillis = 1000, memoryMiB = 1024)
    ).getSpec.getTemplate.getSpec.getContainers
      .get(0)
    assertEquals(container.getResources.getLimits.get("cpu").toString, "1000m")
    assertEquals(container.getResources.getLimits.get("memory").toString, "1024Mi")
    assertEquals(container.getResources.getRequests, container.getResources.getLimits)
  }

  test("a literal env var renders a value and a secret one renders a reference") {
    val withEnv = spec.copy(env =
      List(
        EnvEntry("LOG_LEVEL", value = Some("info")),
        EnvEntry("NAKKA_DB_PASSWORD", secretName = Some("cart-db"), secretKey = Some("password"))
      )
    )
    val env = deploymentFor(withEnv).getSpec.getTemplate.getSpec.getContainers.get(0).getEnv.asScala

    assertEquals(env.find(_.getName == "LOG_LEVEL").get.getValue, "info")

    val secret = env.find(_.getName == "NAKKA_DB_PASSWORD").get
    assertEquals(secret.getValue, null)
    assertEquals(secret.getValueFrom.getSecretKeyRef.getName, "cart-db")
    assertEquals(secret.getValueFrom.getSecretKeyRef.getKey, "password")
  }

  test("the progress deadline is handed to Kubernetes rather than timed here") {
    assertEquals(
      deploymentFor(
        spec.copy(progressDeadlineSeconds = 120)
      ).getSpec.getProgressDeadlineSeconds.intValue,
      120
    )
  }

  test("an unusable project id is a reported problem, not an exception") {
    val bad = Rendering.render(
      resource(spec.copy(projectId = "Not_A_Label")),
      settings,
      ProvisioningPlan.Supplied,
      "unused"
    )
    assert(bad.isLeft)
    assert(bad.left.exists(_.exists(_.contains("DNS label"))), s"got: $bad")
  }

  test("a namespace name too long to exist is refused") {
    val bad = Rendering.render(
      resource(spec.copy(projectId = "a" * 60)),
      settings,
      ProvisioningPlan.Supplied,
      "unused"
    )
    assert(bad.left.exists(_.exists(_.contains("over the 63"))), s"got: $bad")
  }

  test("every problem is reported at once") {
    val bad = Rendering.render(
      resource(spec.copy(projectId = "BAD", serviceName = "", image = "")),
      settings,
      ProvisioningPlan.Supplied,
      "unused"
    )
    assertEquals(bad.left.map(_.size), Left(3))
  }

  // --- The port (feature 003): one value, three things on the container

  private def containerOf(s: NakkaServiceSpec) =
    deploymentFor(s).getSpec.getTemplate.getSpec.getContainers.get(0)

  test(
    "a port renders the http container port and NAKKA_HTTP_PORT — the same value"
  ) {
    val c = containerOf(spec.copy(port = Some(8080)))

    // Beside the management and remoting ports every service carries (feature 004).
    val http = c.getPorts.asScala.find(_.getName == "http").getOrElse(fail("no http port"))
    assertEquals(http.getContainerPort.intValue, 8080)

    assertEquals(
      c.getEnv.asScala.find(_.getName == "NAKKA_HTTP_PORT").map(_.getValue),
      Some("8080")
    )

  }

  test("no HTTP port renders no http container port and no NAKKA_HTTP_PORT") {
    val c = containerOf(spec.copy(port = None))
    assert(!c.getPorts.asScala.exists(_.getName == "http"), c.getPorts.toString)
    assert(!c.getEnv.asScala.exists(_.getName == "NAKKA_HTTP_PORT"))
  }

  // --- Cluster membership (feature 004): readiness, ports, identity, environment

  test("readiness is cluster membership: httpGet /ready on the management port, HTTP or not") {
    // Replaces feature 003's tcpSocket on the HTTP port, which could not exist for a service that
    // serves no HTTP — so such a service was Ready the moment its container ran. Every service is
    // a cluster member, so every service now has a meaningful Ready. Cluster Bootstrap registers
    // the membership check itself; nakka adds "HTTP is bound" for services that declare a port.
    for s <- Vector(spec.copy(port = Some(8080)), spec.copy(port = None)) do
      val probe = containerOf(s).getReadinessProbe
      assert(probe != null, s"no probe for port=${s.port}")
      assertEquals(probe.getHttpGet.getPath, "/ready")
      assertEquals(probe.getHttpGet.getPort.getStrVal, "management")
      assertEquals(probe.getTcpSocket, null)
      assertEquals(probe.getPeriodSeconds.intValue, 5)
  }

  test("the management and remoting ports are always exposed, and the management one is NAMED") {
    // Kubernetes API discovery finds a pod's contact point by looking for a container port with
    // this exact name. Get it wrong and discovery finds every pod and reaches none of them.
    for s <- Vector(spec.copy(port = Some(8080)), spec.copy(port = None)) do
      val ports =
        containerOf(s).getPorts.asScala.map(p => p.getName -> p.getContainerPort.intValue).toMap
      assertEquals(ports.get("management"), Some(7626), s"port=${s.port}")
      assertEquals(ports.get("remoting"), Some(17355), s"port=${s.port}")
  }

  test("the pod runs as the service's own identity") {
    val podSpec = deploymentFor(spec).getSpec.getTemplate.getSpec
    assertEquals(podSpec.getServiceAccountName, Names.serviceAccount("cart"))
  }

  test("the environment tells a node it is in Kubernetes, and how to find its peers") {
    val d   = deploymentFor(spec.copy(autoscaling = spec.autoscaling.copy(minInstances = 3)))
    val env = d.getSpec.getTemplate.getSpec.getContainers.get(0).getEnv.asScala
    def value(name: String) = env.find(_.getName == name).map(_.getValue)

    assertEquals(value("NAKKA_CLUSTER_MODE"), Some("kubernetes"))
    assertEquals(value("NAKKA_CLUSTER_SERVICE"), Some("cart"))
    assertEquals(value("NAKKA_CLUSTER_CONTACT_POINTS"), Some("2"))
    // From the downward API, never a literal — the platform cannot know a pod's IP in advance.
    val podIp = env.find(_.getName == "POD_IP").getOrElse(fail("no POD_IP"))
    assertEquals(podIp.getValueFrom.getFieldRef.getFieldPath, "status.podIP")

    // The selector is the Deployment's own selector plus the formation label, rendered k=v,k=v
    // — compared against the rendered objects, not against Labels.identity, so drift between
    // them cannot pass. Every label in it must be on the pod template, or discovery finds nothing.
    val templateLabels = d.getSpec.getTemplate.getMetadata.getLabels.asScala
    val expected =
      (d.getSpec.getSelector.getMatchLabels.asScala.toMap +
        (Labels.FormationKey -> Labels.FormationBootstrap)).toSeq.sorted
        .map((k, v) => s"$k=$v")
        .mkString(",")
    assertEquals(value("NAKKA_CLUSTER_POD_SELECTOR"), Some(expected))
    for (k, v) <- expected.split(",").map(_.split("=", 2)).map(a => a(0) -> a(1)) do
      assertEquals(templateLabels.get(k), Some(v), s"selector label $k not on the pod template")
  }

  test(
    "the formation label is on the pod template and the contact-point selector, not the Deployment's selector"
  ) {
    // A pod from a template without it — a pre-formation image — is never a contact point, so
    // it cannot deadlock bootstrap; and the Deployment's selector is immutable, so it stays as
    // it was.
    val d = deploymentFor(spec)
    assertEquals(
      d.getSpec.getTemplate.getMetadata.getLabels.get(Labels.FormationKey),
      Labels.FormationBootstrap
    )
    assertEquals(d.getSpec.getSelector.getMatchLabels.containsKey(Labels.FormationKey), false)
    assert(
      Rendering.contactPointSelector(Map("a" -> "b")).contains(s"${Labels.FormationKey}=bootstrap")
    )
  }

  test("required contact points are min(instances, 2): one must form alone, more must not") {
    for (count, expected) <- Vector(1 -> "1", 2 -> "2", 5 -> "2") do
      val env = containerOf(
        spec.copy(autoscaling = spec.autoscaling.copy(minInstances = count))
      ).getEnv.asScala
      assertEquals(
        env.find(_.getName == "NAKKA_CLUSTER_CONTACT_POINTS").map(_.getValue),
        Some(expected),
        s"at $count"
      )
  }

  test("the platform's variables sit beside the descriptor's own env, not instead of it") {
    val env = containerOf(
      spec.copy(env = List(EnvEntry("LOG_LEVEL", value = Some("info"))))
    ).getEnv.asScala.map(_.getName).toSet
    assert(env.contains("LOG_LEVEL"), env.toString)
    assert(env.contains("NAKKA_CLUSTER_MODE"), env.toString)
  }

  test("the injected port sits beside the descriptor's own env, not instead of it") {
    val c = containerOf(
      spec.copy(port = Some(8080), env = List(EnvEntry("LOG_LEVEL", value = Some("info"))))
    )
    val names = c.getEnv.asScala.map(_.getName).toVector
    assert(names.contains("LOG_LEVEL"), names.toString)
    assert(names.contains("NAKKA_HTTP_PORT"), names.toString)
  }

  test("a stopping pod keeps serving while endpoints catch up: preStop sleep, Kubernetes' own") {
    // Kubernetes' sleep action rather than `exec sleep`: a workload image owes the platform no
    // shell. The window it bridges is kube-proxy learning the pod left the endpoints.
    for c <- Vector(containerOf(spec.copy(port = Some(8080))), containerOf(spec.copy(port = None)))
    do
      val sleep = c.getLifecycle.getPreStop.getSleep
      assertEquals(sleep.getSeconds.longValue, Rendering.PreStopSeconds)
      assertEquals(c.getLifecycle.getPreStop.getExec, null)
  }

  test("there is never a liveness probe — a restart now also costs a shard rebalance") {
    assertEquals(containerOf(spec.copy(port = Some(8080))).getLivenessProbe, null)
    assertEquals(containerOf(spec.copy(port = None)).getLivenessProbe, null)
  }

  test(
    "imagePullPolicy is IfNotPresent, always — the :latest default is Always, which ignores a loaded image"
  ) {
    // Kubernetes defaults this to Always for a :latest tag, so an image loaded straight onto the
    // node (kind load, ctr import) is ignored and the pod fails ErrImagePull. Verified against a
    // real node during planning: same image, same cluster, this one field apart. It never showed
    // before because every test used registry.k8s.io/pause:3.9 — pullable, and not :latest.
    assertEquals(containerOf(spec.copy(port = Some(8080))).getImagePullPolicy, "IfNotPresent")
    assertEquals(containerOf(spec.copy(port = None)).getImagePullPolicy, "IfNotPresent")
    assertEquals(containerOf(spec.copy(image = "cart:latest")).getImagePullPolicy, "IfNotPresent")
  }

  // --- Exposure (feature 005): contracts/route-object.md

  private val exposing = settings.copy(baseDomain = Some("example.test"))

  private def routeActionFor(s: NakkaServiceSpec, settings: Settings = exposing) =
    Rendering.render(resource(s, "uid-1"), settings, ProvisioningPlan.Supplied, "unused") match
      case Right(actions) =>
        actions
          .collectFirst {
            case a: Action.EnsureHttpRoute => a
            case a: Action.RemoveHttpRoute => a
          }
          .getOrElse(fail(s"no route action was rendered: $actions"))
      case Left(problems) => fail(s"rendering failed: ${problems.mkString("; ")}")

  test("an exposed service renders one HTTPRoute, field for field") {
    val Action.EnsureHttpRoute(route) =
      routeActionFor(spec.copy(exposed = true, port = Some(9000))): @unchecked
    assertEquals(route.getMetadata.getName, "cart")
    assertEquals(route.getMetadata.getNamespace, "nakka-checkout")
    assertEquals(route.getMetadata.getOwnerReferences.get(0).getUid, "uid-1")
    assertEquals(route.getMetadata.getOwnerReferences.get(0).getKind, "NakkaService")
    assertEquals(route.getMetadata.getLabels.get(Labels.NameKey), "cart")

    val parent = route.getSpec.getParentRefs.get(0)
    assertEquals(parent.getGroup, "gateway.networking.k8s.io")
    assertEquals(parent.getKind, "Gateway")
    assertEquals(parent.getName, Rendering.GatewayName)
    assertEquals(parent.getNamespace, Rendering.GatewayNamespace)
    assertEquals(parent.getSectionName, Rendering.GatewaySection)

    assertEquals(route.getSpec.getHostnames.asScala.toVector, Vector("cart-checkout.example.test"))
    val backend = route.getSpec.getRules.get(0).getBackendRefs.get(0)
    assertEquals(backend.getName, "cart")
    assertEquals(backend.getPort.intValue, 9000)
    // Same namespace by omission: the API forbids a cross-namespace backend without a
    // ReferenceGrant, and none is ever rendered — so a route cannot name another service.
    assertEquals(backend.getNamespace, null)
  }

  test("the route follows the port, and is deterministic") {
    val Action.EnsureHttpRoute(a) =
      routeActionFor(spec.copy(exposed = true, port = Some(8080))): @unchecked
    assertEquals(a.getSpec.getRules.get(0).getBackendRefs.get(0).getPort.intValue, 8080)
    assertEquals(
      routeActionFor(spec.copy(exposed = true)),
      routeActionFor(spec.copy(exposed = true))
    )
  }

  test(
    "unexposed, or exposed with no HTTP, or with no base domain: the route is removed if owned"
  ) {
    val removal = Action.RemoveHttpRoute("nakka-checkout", "cart", "uid-1")
    assertEquals(routeActionFor(spec), removal)
    assertEquals(routeActionFor(spec.copy(exposed = true, port = None)), removal)
    assertEquals(routeActionFor(spec.copy(exposed = true), settings), removal)
  }

  test("an unexposed service's other objects are untouched by this feature") {
    // SC-009: nothing changes for a service that was never exposed.
    val Right(before) =
      Rendering.render(resource(spec), settings, ProvisioningPlan.Supplied, "unused"): @unchecked
    val Right(after) =
      Rendering.render(resource(spec), exposing, ProvisioningPlan.Supplied, "unused"): @unchecked
    assertEquals(before, after)
    assertEquals(routeActionFor(spec, settings), routeActionFor(spec, exposing))
  }
