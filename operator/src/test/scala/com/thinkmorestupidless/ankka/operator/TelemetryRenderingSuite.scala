package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.{Container, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.utils.Serialization

import scala.jdk.CollectionConverters.*

/**
 * Where the installation's telemetry settings are rendered, by hosting: the scenarios of
 * `features/observability/telemetry-settings.feature` that rendering holds.
 */
class TelemetryRenderingSuite extends munit.FunSuite:

  private val address = "http://otel-collector.ankka-telemetry.svc.cluster.local:4318"

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("orders").withUid("u-1").build()
    )
    r.setSpec(spec)
    r

  private def spec(hosting: String) = AnkkaServiceSpec(
    projectId = "shop",
    serviceName = "orders",
    generation = 1L,
    image = "orders:1.0.0",
    port = Some(9000),
    hosting = hosting
  )

  private def render(hosting: String, settings: Settings): Vector[Action] =
    Rendering
      .render(
        resource(spec(hosting)),
        settings.copy(proxyImage = "ankka-proxy:9.9.9"),
        ProvisioningPlan.Supplied
      )
      .fold(problems => fail(problems.mkString("; ")), identity)

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

  private def named(c: Container, name: String) = c.getEnv.asScala.find(_.getName == name)

  private val withAddress = Settings.default.copy(otlpEndpoint = Some(address))
  private val withHeaders =
    withAddress.copy(otlpHeaders = Some(Settings.Credential("authorization=Bearer s3cr3t")))

  test(
    "a deployed service exports without its descriptor asking: the address on its one container"
  ) {
    val Vector(only) = containers(render("embedded", withAddress))
    assertEquals(named(only, PlatformVariables.OtlpEndpoint).map(_.getValue), Some(address))
    assertEquals(named(only, PlatformVariables.OtlpHeaders), None)
  }

  test(
    "a process-hosted service has the address on the platform's container and not the process's"
  ) {
    val cs = containers(render("process", withAddress))
    assertEquals(named(cs(0), PlatformVariables.OtlpEndpoint).map(_.getValue), Some(address))
    val app = cs.find(_.getName.endsWith("-app")).get
    assertEquals(named(app, PlatformVariables.OtlpEndpoint), None)
  }

  test("a module-hosted service has the address on its one container, the runtime's") {
    val Vector(only) = containers(render("wasm", withAddress))
    assertEquals(named(only, PlatformVariables.OtlpEndpoint).map(_.getValue), Some(address))
  }

  test("the headers are a reference to the service's own Secret, written before the Deployment") {
    for hosting <- Vector("embedded", "process", "wasm") do
      val actions = render(hosting, withHeaders)
      val ref = named(containers(actions).head, PlatformVariables.OtlpHeaders)
        .map(_.getValueFrom.getSecretKeyRef)
        .getOrElse(fail(s"$hosting: no headers"))
      assertEquals((ref.getName, ref.getKey), ("orders-telemetry", "headers"), hosting)
      val secretAt = actions.indexWhere(_.isInstanceOf[Action.EnsureTelemetrySecret])
      val deployAt = actions.indexWhere(_.isInstanceOf[Action.ApplyDeployment])
      assert(secretAt >= 0 && secretAt < deployAt, s"$hosting: ${actions.map(_.describe)}")
  }

  test("the credential is in no action and no rendered object") {
    val actions = render("process", withHeaders)
    val secret  = actions.collectFirst { case s: Action.EnsureTelemetrySecret => s }.get
    assert(!secret.toString.contains("s3cr3t"), secret.toString)
    assert(!secret.describe.contains("s3cr3t"))
    assertEquals(secret.owner.getUid, "u-1", "owned by the service, so it goes with it")
    actions.collect { case Action.ApplyDeployment(d) => d }.foreach { d =>
      assert(!Serialization.asYaml(d).contains("s3cr3t"))
    }
    assert(!withHeaders.toString.contains("s3cr3t"), withHeaders.toString)
  }

  test("a web-hosted service exports nothing") {
    val actions = render("web", withHeaders)
    containers(actions).foreach { c =>
      assertEquals(named(c, PlatformVariables.OtlpEndpoint), None, c.getName)
      assertEquals(named(c, PlatformVariables.OtlpHeaders), None, c.getName)
    }
    assert(!actions.exists(_.isInstanceOf[Action.EnsureTelemetrySecret]))
  }

  test("headers with no address render nothing") {
    val actions = render("embedded", Settings.default.copy(otlpHeaders = withHeaders.otlpHeaders))
    assertEquals(named(containers(actions).head, PlatformVariables.OtlpHeaders), None)
    assert(!actions.exists(_.isInstanceOf[Action.EnsureTelemetrySecret]))
  }

  test("with no collector named, the Deployment is what it was before the feature") {
    val before = render("embedded", Settings.default)
    assert(!before.exists(_.isInstanceOf[Action.EnsureTelemetrySecret]))
    containers(before).foreach(c =>
      assert(!c.getEnv.asScala.exists(_.getName.startsWith("ANKKA_OTLP_")), c.getName)
    )
  }
