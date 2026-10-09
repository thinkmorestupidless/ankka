package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders for the installation's secret store (feature 038): its settings on the
 * platform's program of every hosting and on no other container, nothing at all when the
 * installation sets nothing, and never a credential for Google Cloud — a service reaches Secret
 * Manager as its own identity, which needs nothing in the pod.
 */
class SecretsRenderingSuite extends munit.FunSuite:

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-spinvibe")
        .withName("payments")
        .withUid("u")
        .build()
    )
    r.setSpec(spec)
    r

  private val embedded = AnkkaServiceSpec(
    projectId = "spinvibe",
    serviceName = "payments",
    generation = 1L,
    image = "payments:1.0.0",
    port = Some(9000)
  )

  private val onSecretManager = Settings.default.copy(
    secretStore = Settings.SecretStore(
      backend = Some("secret-manager"),
      move = Some("copy"),
      versionsKept = Some("3"),
      cloudAccount = Some("spinvibe-prod"),
      cloudLocation = Some("europe-west2")
    )
  )

  private def actions(spec: AnkkaServiceSpec, settings: Settings): Vector[Action] =
    Rendering.render(resource(spec), settings, ProvisioningPlan.Supplied).toOption.get

  private def deployment(spec: AnkkaServiceSpec, settings: Settings): Deployment =
    actions(spec, settings).collectFirst { case Action.ApplyDeployment(d) => d }.get

  private def containers(spec: AnkkaServiceSpec, settings: Settings) =
    deployment(spec, settings).getSpec.getTemplate.getSpec.getContainers.asScala.toVector

  private def envOf(c: io.fabric8.kubernetes.api.model.Container): Map[String, String] =
    c.getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("<ref>")).toMap

  private val expected = Map(
    PlatformVariables.SecretBackend      -> "secret-manager",
    PlatformVariables.SecretMove         -> "copy",
    PlatformVariables.SecretVersionsKept -> "3",
    PlatformVariables.CloudAccount       -> "spinvibe-prod",
    PlatformVariables.CloudLocation      -> "europe-west2"
  )

  test("the settings reach the platform's program of an embedded, a process and a module service") {
    for hosting <- Vector("embedded", "process", "wasm") do
      val cs       = containers(embedded.copy(hosting = hosting), onSecretManager)
      val platform = cs.head
      expected.foreach((name, value) =>
        assertEquals(envOf(platform).get(name), Some(value), s"$hosting: $name")
      )
      cs.drop(1).foreach { other =>
        val leaked = envOf(other).keySet.intersect(expected.keySet)
        assertEquals(leaked, Set.empty[String], s"$hosting: ${other.getName} is given $leaked")
      }
  }

  test("a web-hosted service is given none of them") {
    val cs = containers(embedded.copy(hosting = "web", port = Some(8080)), onSecretManager)
    cs.foreach(c => assertEquals(envOf(c).keySet.intersect(expected.keySet), Set.empty[String]))
  }

  test("an installation that sets nothing, or only the defaults, renders what it rendered before") {
    val before = deployment(embedded, Settings.default)
    val defaults = Settings.default.copy(
      secretStore = Settings.SecretStore(backend = Some("postgres"), versionsKept = Some("2"))
    )
    assertEquals(deployment(embedded, defaults), before)
    val names = envOf(before.getSpec.getTemplate.getSpec.getContainers.get(0)).keySet
    assert(
      !names.exists(n => n.startsWith("ANKKA_SECRET_") && n != PlatformVariables.SecretKey),
      names
    )
    assert(!names.contains(PlatformVariables.SecretRecordsUrl), names)
  }

  test("an instance holds no credential for Google Cloud, and its secret key is still rendered") {
    val rendered = deployment(embedded, onSecretManager)
    val pod      = rendered.getSpec.getTemplate.getSpec
    val text     = io.fabric8.kubernetes.client.utils.Serialization.asYaml(rendered)
    for word <- Vector(
        "GOOGLE_APPLICATION_CREDENTIALS",
        "iam.gke.io",
        "service_account",
        "key.json",
        "credentials.json"
      )
    do assert(!text.contains(word), s"the Deployment mentions $word")
    assert(Option(pod.getServiceAccountName).contains("payments"), pod.getServiceAccountName)
    val key = pod.getContainers.get(0).getEnv.asScala.find(_.getName == PlatformVariables.SecretKey)
    assert(
      key.exists(_.getValueFrom.getSecretKeyRef.getName == "payments-secret-key"),
      key.toString
    )
    assert(
      actions(embedded, onSecretManager).exists {
        case Action.EnsureSecretKey(_, name, _) => name == "payments-secret-key"
        case _                                  => false
      },
      "the secret key is still made, so a move can decrypt the rows it copies"
    )
  }

  test("a descriptor cannot set the installation's secret settings") {
    expected.keySet.foreach(name =>
      assert(PlatformVariables.platformOnly(name), s"$name may be set by a descriptor")
    )
    assert(PlatformVariables.platformOnly(PlatformVariables.SecretRecordsUrl))
    assert(PlatformVariables.platformOnly(PlatformVariables.CloudProvider))
  }
