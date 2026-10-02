package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}

import scala.jdk.CollectionConverters.*

/**
 * The Service a deployed workload is reached through — pure, no cluster.
 *
 * See
 * [contracts/service-object.md](../../../../../specs/003-deploy-real-service/contracts/service-object.md).
 *
 * The test that matters most here compares the Service's selector against the *rendered
 * Deployment's* selector, not against a second call to `Labels.identity`. Two calls agreeing proves
 * nothing about whether `Rendering` used them; a Service whose selector drifts from its
 * Deployment's is a service that reports `Ready` and accepts no connections.
 */
class ServiceRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default

  private def resource(spec: AnkkaServiceSpec, uid: String): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-checkout").withName("cart").withUid(uid).build()
    )
    r.setSpec(spec)
    r

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    image = "cart:1.0",
    labels = Map("team" -> "payments"),
    port = Some(8080)
  )

  private def actionsFor(s: AnkkaServiceSpec, uid: String = "uid-1"): Vector[Action] =
    Rendering.render(resource(s, uid), settings, ProvisioningPlan.Supplied) match
      case Right(actions) => actions
      case Left(problems) => fail(s"expected a render, got $problems")

  private def serviceFor(s: AnkkaServiceSpec) =
    actionsFor(s)
      .collectFirst { case Action.EnsureService(svc) => svc }
      .getOrElse(fail("no Service"))

  private def deploymentFor(s: AnkkaServiceSpec) =
    actionsFor(s)
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .getOrElse(fail("no Deployment"))

  test("the Service selects exactly what the Deployment selects — compared object to object") {
    val service    = serviceFor(spec)
    val deployment = deploymentFor(spec)
    assertEquals(
      service.getSpec.getSelector.asScala.toMap,
      deployment.getSpec.getSelector.getMatchLabels.asScala.toMap
    )
  }

  test("every selector label is really on the pods, or the Service has no endpoints") {
    val selector  = serviceFor(spec).getSpec.getSelector.asScala.toMap
    val podLabels = deploymentFor(spec).getSpec.getTemplate.getMetadata.getLabels.asScala.toMap
    selector.foreach((key, value) => assertEquals(podLabels.get(key), Some(value), key))
  }

  test("a descriptor label cannot widen or redirect the selector") {
    val hostile = spec.copy(labels = Map(Labels.NameKey -> "someone-else"))
    assertEquals(serviceFor(hostile).getSpec.getSelector.get(Labels.NameKey), "cart")
  }

  test("it is named after the service, in the project's namespace") {
    val service = serviceFor(spec)
    assertEquals(service.getMetadata.getName, Names.service("cart"))
    assertEquals(service.getMetadata.getName, "cart")
    assertEquals(service.getMetadata.getNamespace, "ankka-checkout")
  }

  test("it is a ClusterIP — in-cluster reachability is the whole scope") {
    assertEquals(serviceFor(spec).getSpec.getType, "ClusterIP")
  }

  test("one port, named http, and port equals targetPort equals the resolved value") {
    val ports = serviceFor(spec).getSpec.getPorts.asScala
    assertEquals(ports.size, 1)
    assertEquals(ports.head.getName, "http")
    assertEquals(ports.head.getPort.intValue, 8080)
    assertEquals(ports.head.getTargetPort.getIntVal.intValue, 8080)
    assertEquals(ports.head.getProtocol, "TCP")
  }

  test(
    "the container exposes the observe port once, by name and number, and no selector names it"
  ) {
    val deployment = deploymentFor(spec)
    val ports      = deployment.getSpec.getTemplate.getSpec.getContainers.get(0).getPorts.asScala
    val observe    = ports.filter(p => p.getName == "observe" || p.getContainerPort == 7628)
    assertEquals(observe.size, 1, ports.map(p => s"${p.getName}:${p.getContainerPort}").toString)
    assertEquals(observe.head.getName, "observe")
    assertEquals(observe.head.getContainerPort.intValue, 7628)
    assertEquals(observe.head.getProtocol, "TCP")
    assert(!deployment.getSpec.getSelector.getMatchLabels.asScala.values.exists(_.contains("7628")))
    assert(
      !serviceFor(spec).getSpec.getPorts.asScala.exists(_.getPort == 7628),
      "not a Service port"
    )
  }

  test("the Service's target is the very port the container exposes") {
    val containerPort = deploymentFor(spec).getSpec.getTemplate.getSpec.getContainers
      .get(0)
      .getPorts
      .get(0)
      .getContainerPort
    val targetPort = serviceFor(spec).getSpec.getPorts.get(0).getTargetPort.getIntVal
    assertEquals(targetPort, containerPort)
  }

  test("it is owned by the resource, so deleting the service removes its address with no sweep") {
    val owners = serviceFor(spec).getMetadata.getOwnerReferences.asScala
    assertEquals(owners.size, 1)
    assertEquals(owners.head.getUid, "uid-1")
    assertEquals(owners.head.getKind, "AnkkaService")
  }

  test("it carries the merged labels and the generation, as the Deployment does") {
    val meta = serviceFor(spec).getMetadata
    assertEquals(meta.getLabels.get("team"), "payments")
    assertEquals(meta.getLabels.get(Labels.ManagedByKey), "ankka")
    assertEquals(meta.getAnnotations.get(Labels.GenerationKey), "4")
  }

  test("a port means EnsureService and never RemoveService") {
    val actions = actionsFor(spec)
    assertEquals(actions.count(_.isInstanceOf[Action.EnsureService]), 1)
    assertEquals(actions.count(_.isInstanceOf[Action.RemoveService]), 0)
  }

  test("no port means RemoveService — carrying the owner's uid — and never EnsureService") {
    val actions = actionsFor(spec.copy(port = None), uid = "uid-9")
    assertEquals(actions.count(_.isInstanceOf[Action.EnsureService]), 0)
    assertEquals(
      actions.collect { case r: Action.RemoveService => r },
      Vector(Action.RemoveService("ankka-checkout", "cart", "uid-9"))
    )
  }

  test("the Service comes after the Deployment it fronts") {
    val actions = actionsFor(spec)
    assert(
      actions.indexWhere(_.isInstanceOf[Action.EnsureService]) >
        actions.indexWhere(_.isInstanceOf[Action.ApplyDeployment])
    )
  }

  test("rendering twice is identical") {
    assertEquals(serviceFor(spec), serviceFor(spec))
  }

  // --- gRPC (feature 020)

  private def portsOf(s: AnkkaServiceSpec) =
    serviceFor(s).getSpec.getPorts.asScala.toList
      .map(p =>
        (
          p.getName,
          p.getPort.intValue,
          p.getTargetPort.getIntVal.intValue,
          Option(p.getAppProtocol)
        )
      )

  test("a service with both ports has an address with both, http first, h2c on grpc only") {
    assertEquals(
      portsOf(spec.copy(grpcPort = Some(9090))),
      List(("http", 8080, 8080, None), ("grpc", 9090, 9090, Some("kubernetes.io/h2c")))
    )
  }

  test("a service with gRPC and no HTTP has an address with the one port") {
    assertEquals(
      portsOf(spec.copy(port = None, grpcPort = Some(9090))),
      List(("grpc", 9090, 9090, Some("kubernetes.io/h2c")))
    )
  }

  test("a service with neither port has no address") {
    val actions = actionsFor(spec.copy(port = None, grpcPort = None))
    assert(actions.exists { case Action.RemoveService(_, "cart", _) => true; case _ => false })
    assert(!actions.exists { case Action.EnsureService(_) => true; case _ => false })
  }

  test("the node container carries the grpc port and ANKKA_GRPC_PORT only when gRPC is declared") {
    def container(s: AnkkaServiceSpec) =
      deploymentFor(s).getSpec.getTemplate.getSpec.getContainers.asScala.head
    val with_   = container(spec.copy(grpcPort = Some(9090)))
    val without = container(spec)
    assert(with_.getPorts.asScala.exists(p => p.getName == "grpc" && p.getContainerPort == 9090))
    assert(with_.getEnv.asScala.exists(e => e.getName == "ANKKA_GRPC_PORT" && e.getValue == "9090"))
    assert(!without.getPorts.asScala.exists(_.getName == "grpc"))
    assert(!without.getEnv.asScala.exists(_.getName == "ANKKA_GRPC_PORT"))
  }

  test("for process hosting the grpc port is on the node container, which is the sidecar") {
    val pod = deploymentFor(
      spec.copy(hosting = "process", grpcPort = Some(9090))
    ).getSpec.getTemplate.getSpec
    val (node, app) = (pod.getContainers.asScala(0), pod.getContainers.asScala(1))
    assert(node.getPorts.asScala.exists(_.getName == "grpc"))
    assert(!Option(app.getPorts).exists(_.asScala.exists(_.getName == "grpc")))
  }
