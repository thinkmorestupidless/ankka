package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaProjectSpec,
  AnkkaService,
  AnkkaServiceSpec,
  CloudResource,
  CloudResourceSpec
}
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder

/**
 * A cloud request's answer wakes the resource that asked (feature 044): a service's on the service
 * queue, a project's on the project queue. The routing is a pure function of the request's
 * controlling owner, so it is tested here with no API server; the informer only calls it.
 */
class OperatorSuite extends munit.FunSuite:

  private def request(owners: io.fabric8.kubernetes.api.model.OwnerReference*): CloudResource =
    val r = CloudResource("ankka-shop", "x", CloudResourceSpec("gcp", "bucket"))
    r.getMetadata.setOwnerReferences(java.util.List.of(owners*))
    r

  private val service =
    val s = AnkkaService(
      "ankka-shop",
      "reports",
      AnkkaServiceSpec(projectId = "shop", serviceName = "reports")
    )
    s.getMetadata.setUid("s")
    s

  private val project =
    val p = AnkkaProject("ankka-shop", "shop", AnkkaProjectSpec(projectId = "shop"))
    p.getMetadata.setUid("p")
    p

  test("a service's request wakes the service, on the service queue") {
    assertEquals(
      Operator.ownerOf(request(Labels.ownerReference(service))),
      Some(Operator.Owner.Service -> ServiceRef("ankka-shop", "reports"))
    )
  }

  test("a project's request wakes the project, on the project queue") {
    assertEquals(
      Operator.ownerOf(request(Labels.ownerReference(project))),
      Some(Operator.Owner.Project -> ServiceRef("ankka-shop", "shop"))
    )
  }

  test("a request owned by nothing the operator reconciles wakes nothing") {
    val stranger = new OwnerReferenceBuilder()
      .withApiVersion("v1")
      .withKind("ConfigMap")
      .withName("x")
      .withUid("c")
      .withController(true)
      .build()
    assertEquals(Operator.ownerOf(request(stranger)), None)
    assertEquals(Operator.ownerOf(request()), None)
  }

  test("only the controlling owner counts") {
    val notController = Labels.ownerReference(service)
    notController.setController(false)
    assertEquals(Operator.ownerOf(request(notController)), None)
  }
