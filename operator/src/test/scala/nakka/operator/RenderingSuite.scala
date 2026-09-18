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

  test("the generation reaches the pod template, which is what makes a restart roll") {
    val annotations = deploymentFor(spec).getSpec.getTemplate.getMetadata.getAnnotations.asScala
    assertEquals(annotations.get(Labels.GenerationKey), Some("4"))

    val rolled = deploymentFor(spec.copy(generation = 5L))
    assertEquals(
      rolled.getSpec.getTemplate.getMetadata.getAnnotations.asScala.get(Labels.GenerationKey),
      Some("5")
    )
  }

  test("exactly one replica, and no autoscaler is rendered") {
    // Not a simplification. Each pod joins itself as a single-node cluster, so a second
    // replica is a second writer to the same journal.
    assertEquals(deploymentFor(spec).getSpec.getReplicas.intValue, 1)

    val Right(actions) =
      Rendering.render(resource(spec), settings, ProvisioningPlan.Supplied, "unused"): @unchecked
    // Stated as what it means rather than as a count: nothing is rendered beyond the namespace,
    // the Deployment and the service's address. An autoscaler would be a fourth kind of thing.
    val unexpected = actions.filterNot {
      case _: Action.EnsureNamespace | _: Action.ApplyDeployment | _: Action.EnsureService |
          _: Action.RemoveService =>
        true
      case _ => false
    }
    assertEquals(unexpected, Vector.empty, s"rendered something that is not one of those: $actions")
  }

  test("the rollout strategy is Recreate — a rolling update is two writers to one journal") {
    // Kubernetes' default is RollingUpdate with maxSurge 25%, which at one replica rounds up to a
    // whole extra pod: old and new run side by side on every deploy and every restart. Each joins
    // *itself* as a single-node cluster, so that is two clusters hosting the same entity ids over
    // one journal — the exact failure "exactly one replica" exists to prevent, arrived at by a
    // route that never touches the replica count. The platform's own manifests have always said
    // Recreate for this reason; workloads did not, and it went unnoticed because `pause` has no
    // journal and, with no readiness probe, the overlap lasted about a second. Found by deploying
    // the first real service (feature 003) and seeing two pods.
    val strategy = deploymentFor(spec).getSpec.getStrategy
    assertEquals(strategy.getType, "Recreate")
    assertEquals(strategy.getRollingUpdate, null, "rollingUpdate is invalid beside Recreate")
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
    "a port renders a container port, NAKKA_HTTP_PORT and a readiness probe — all the same value"
  ) {
    val c = containerOf(spec.copy(port = Some(8080)))

    val ports = c.getPorts.asScala
    assertEquals(ports.size, 1)
    assertEquals(ports.head.getName, "http")
    assertEquals(ports.head.getContainerPort.intValue, 8080)

    assertEquals(
      c.getEnv.asScala.find(_.getName == "NAKKA_HTTP_PORT").map(_.getValue),
      Some("8080")
    )

    val probe = c.getReadinessProbe
    assertEquals(probe.getTcpSocket.getPort.getIntVal.intValue, 8080)
    assertEquals(probe.getInitialDelaySeconds.intValue, 10)
    assertEquals(probe.getPeriodSeconds.intValue, 5)
    assertEquals(
      probe.getHttpGet,
      null,
      "tcpSocket only: the operator knows neither routes nor ACLs"
    )
  }

  test("no port renders none of the three, so a service that serves no HTTP can still be Ready") {
    val c = containerOf(spec.copy(port = None))
    assert(c.getPorts.isEmpty, c.getPorts.toString)
    assert(!c.getEnv.asScala.exists(_.getName == "NAKKA_HTTP_PORT"))
    assertEquals(c.getReadinessProbe, null)
  }

  test("the injected port sits beside the descriptor's own env, not instead of it") {
    val c = containerOf(
      spec.copy(port = Some(8080), env = List(EnvEntry("LOG_LEVEL", value = Some("info"))))
    )
    val names = c.getEnv.asScala.map(_.getName).toVector
    assert(names.contains("LOG_LEVEL"), names.toString)
    assert(names.contains("NAKKA_HTTP_PORT"), names.toString)
  }

  test("there is never a liveness probe — it would restart a healthy pod mid-GC") {
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
