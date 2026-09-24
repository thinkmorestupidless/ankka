package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, EnvEntry}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders for `hosting: process` (feature 009): two containers, the sidecar as
 * the node and the developer's image beside it, with the environment split between them — and
 * nothing else about the Deployment changed.
 */
class ProcessHostingRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default.copy(sidecarImage = "ankka-sidecar:9.9.9")

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-checkout").withName("cart").withUid("u").build()
    )
    r.setSpec(spec)
    r

  private val embedded = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 1L,
    image = "my-cart:1.0.0",
    port = Some(9000),
    env = List(
      EnvEntry("GREETING", Some("hi"), None, None),
      EnvEntry("ANTHROPIC_API_KEY", None, Some("models"), Some("anthropic")),
      EnvEntry("ANKKA_MODEL_DEFAULT", Some("claude"), None, None),
      EnvEntry("ANKKA_DB_HOST", Some("postgres"), None, None)
    )
  )
  private val process = embedded.copy(hosting = "process")

  private def deployment(spec: AnkkaServiceSpec): Deployment =
    Rendering
      .render(resource(spec), settings, ProvisioningPlan.Supplied, "pw")
      .toOption
      .get
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get

  private def containers(spec: AnkkaServiceSpec) =
    deployment(spec).getSpec.getTemplate.getSpec.getContainers.asScala.toVector

  private def envOf(c: io.fabric8.kubernetes.api.model.Container): Map[String, String] =
    c.getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("<ref>")).toMap

  test("embedded hosting renders exactly one container, as before") {
    val cs = containers(embedded)
    assertEquals(cs.size, 1)
    assertEquals(cs.head.getImage, "my-cart:1.0.0")
    assert(envOf(cs.head).contains("GREETING"))
  }

  test("process hosting renders the sidecar as the node and the image beside it") {
    val cs = containers(process)
    assertEquals(cs.map(_.getImage), Vector("ankka-sidecar:9.9.9", "my-cart:1.0.0"))
    val node = cs(0)
    val app  = cs(1)
    // The node carries every port and the readiness probe; the app carries none.
    assertEquals(node.getPorts.asScala.map(_.getName).toSet, Set("http", "management", "remoting"))
    assertEquals(node.getReadinessProbe.getHttpGet.getPort.getStrVal, "management")
    assert(app.getPorts.isEmpty)
    assertEquals(app.getReadinessProbe, null)
    // Both keep serving through a replacement.
    assertEquals(
      node.getLifecycle.getPreStop.getSleep.getSeconds,
      app.getLifecycle.getPreStop.getSleep.getSeconds
    )
  }

  test("the environment is split: model variables to the sidecar, the rest to the process") {
    val cs   = containers(process)
    val node = envOf(cs(0))
    val app  = envOf(cs(1))
    assert(node.contains("ANTHROPIC_API_KEY") && node.contains("ANKKA_MODEL_DEFAULT"))
    assert(node.contains("ANKKA_DB_HOST"), "a supplied database is the sidecar's")
    assert(!node.contains("GREETING"))
    assert(app.contains("GREETING"))
    assert(!app.contains("ANTHROPIC_API_KEY") && !app.contains("ANKKA_DB_HOST"))
    // How the two find each other, on loopback.
    assertEquals(node("ANKKA_PROCESS_ADDRESS"), "127.0.0.1:9010")
    assertEquals(node("ANKKA_SIDECAR_PORT"), "9011")
    assertEquals(app("ANKKA_PROCESS_PORT"), "9010")
    assertEquals(app("ANKKA_SIDECAR_ADDRESS"), "127.0.0.1:9011")
    // The cluster's variables and the HTTP port stay on the node.
    assert(node.contains("ANKKA_CLUSTER_MODE") && node.contains("ANKKA_HTTP_PORT"))
    assert(!app.contains("ANKKA_CLUSTER_MODE") && !app.contains("ANKKA_HTTP_PORT"))
  }

  test("the credential reaches the sidecar only") {
    val cs = Rendering
      .render(resource(process), settings, ProvisioningPlan.Ready(recovered = false), "pw")
      .toOption
      .get
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .toVector
    assert(cs(0).getEnvFrom.asScala.nonEmpty, "the sidecar gets the credential secret")
    assert(cs(1).getEnvFrom.asScala.isEmpty, "the process must not")
  }

  test("the selector, strategy and restart annotation are unchanged by hosting") {
    val a = deployment(embedded)
    val b = deployment(process)
    assertEquals(a.getSpec.getSelector, b.getSpec.getSelector)
    assertEquals(a.getSpec.getStrategy, b.getSpec.getStrategy)
    assertEquals(
      a.getSpec.getTemplate.getMetadata.getAnnotations,
      b.getSpec.getTemplate.getMetadata.getAnnotations
    )
  }

  test("process hosting with no sidecar image configured is refused, not rendered") {
    val refused = Rendering.render(
      resource(process),
      settings.copy(sidecarImage = ""),
      ProvisioningPlan.Supplied,
      "pw"
    )
    assert(refused.left.exists(_.exists(_.contains("no sidecar image"))), refused)
  }
