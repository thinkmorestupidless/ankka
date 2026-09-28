package com.thinkmorestupidless.ankka.operator

/** Which Deployments need the one non-rolling transition to mutual TLS (feature 014). */
class TransitionSuite extends munit.FunSuite:

  private val identity = Labels.identity("checkout", "cart")
  private val preFeature =
    identity + (Labels.FormationKey -> Labels.FormationBootstrap)

  test("a template without the transport label needs the transition") {
    assert(Transition.needed(Some(preFeature)))
  }

  test("a template with it does not, and neither does no Deployment at all") {
    assert(!Transition.needed(Some(preFeature + (Labels.TransportKey -> Labels.TransportTls))))
    assert(!Transition.needed(None))
  }

  test("what the operator renders today never needs it — the transition happens once") {
    val spec = com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec(
      projectId = "checkout",
      serviceName = "cart",
      image = "cart:1",
      cpuMillis = 100,
      memoryMiB = 128
    )
    val resource = new com.thinkmorestupidless.ankka.crd.AnkkaService
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withName("cart")
        .withNamespace("ankka-checkout")
        .withUid("u")
        .build()
    )
    resource.setSpec(spec)
    val labels = Rendering
      .deployment(resource, spec, "ankka-checkout")
      .getSpec
      .getTemplate
      .getMetadata
      .getLabels
    import scala.jdk.CollectionConverters.*
    assert(!Transition.needed(Some(labels.asScala.toMap)))
  }
