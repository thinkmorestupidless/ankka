package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec}
import io.fabric8.kubernetes.api.model.{Container, ObjectMetaBuilder}

import scala.jdk.CollectionConverters.*

/**
 * Where the keyring's address is rendered (feature 042): on the container the runtime runs in, for
 * every hosting but web, and nowhere when the installation runs no keyring.
 */
class KeyringRenderingSuite extends munit.FunSuite:

  private val address = "https://ankka-keyring.ankka-keyring.svc:9020"

  private def render(hosting: String, settings: Settings): Vector[Container] =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("orders").withUid("u-1").build()
    )
    r.setSpec(
      AnkkaServiceSpec(
        projectId = "shop",
        serviceName = "orders",
        generation = 1L,
        image = "orders:1.0.0",
        port = Some(9000),
        hosting = hosting
      )
    )
    Rendering
      .render(r, settings.copy(proxyImage = "ankka-proxy:9.9.9"), ProvisioningPlan.Supplied)
      .fold(problems => fail(problems.mkString("; ")), identity)
      .collectFirst { case Action.ApplyDeployment(d) => d }
      .get
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .toVector

  private def keyring(c: Container) =
    c.getEnv.asScala.find(_.getName == PlatformVariables.KeyringUrl).map(_.getValue)

  private val withKeyring = Settings.default.copy(keyringUrl = Some(address))

  test("an embedded service and a module have the address on their one container") {
    Vector("embedded", "wasm").foreach { hosting =>
      val Vector(only) = render(hosting, withKeyring)
      assertEquals(keyring(only), Some(address), hosting)
    }
  }

  test("a process-hosted service has it on the platform's container and not the process's") {
    val cs = render("process", withKeyring)
    assertEquals(keyring(cs(0)), Some(address))
    assertEquals(keyring(cs.find(_.getName.endsWith("-app")).get), None)
  }

  test("a web-hosted service, which runs no runtime, has it nowhere") {
    assert(render("web", withKeyring).forall(keyring(_).isEmpty))
  }

  test("an installation without the keyring renders no address at all") {
    assert(render("embedded", Settings.default).forall(keyring(_).isEmpty))
  }
