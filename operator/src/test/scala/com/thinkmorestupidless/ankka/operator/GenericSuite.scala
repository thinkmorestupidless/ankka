package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder

import scala.jdk.CollectionConverters.*

/**
 * cert-manager's objects read by path, in both shapes a client may hand them over: Scala
 * collections (the operator's own client, with the Scala module) and Java ones (fabric8's mapper).
 * Reading only the second made a ready certificate read as never issued (feature 045, found on
 * k3s).
 */
class GenericSuite extends munit.FunSuite:

  private def certificate(status: AnyRef) =
    new GenericKubernetesResourceBuilder()
      .withApiVersion("cert-manager.io/v1")
      .withKind("Certificate")
      .withAdditionalProperties(Map[String, AnyRef]("status" -> status).asJava)
      .build()

  test("conditions are read from Scala collections") {
    val status = Map(
      "conditions" -> List(
        Map("type" -> "Ready", "status" -> "True", "reason" -> "Ready", "message" -> "up to date")
      )
    )
    assertEquals(
      Generic.conditions(certificate(status)),
      Map("Ready" -> ("True", "Ready", "up to date"))
    )
  }

  test("conditions are read from Java collections") {
    val status = Map[String, AnyRef](
      "conditions" -> List(
        Map(
          "type"    -> "Ready",
          "status"  -> "False",
          "reason"  -> "DoesNotExist",
          "message" -> "m"
        ).asJava
      ).asJava
    ).asJava
    assertEquals(
      Generic.conditions(certificate(status)),
      Map("Ready" -> ("False", "DoesNotExist", "m"))
    )
  }

  test("a string by path, from either shape, and nothing for a missing one") {
    val scalaShaped = certificate(Map("reason" -> "lookup failed"))
    val javaShaped  = certificate(Map[String, AnyRef]("reason" -> "lookup failed").asJava)
    assertEquals(Generic.string(scalaShaped, "status", "reason"), Some("lookup failed"))
    assertEquals(Generic.string(javaShaped, "status", "reason"), Some("lookup failed"))
    assertEquals(Generic.string(scalaShaped, "status", "missing"), None)
    assertEquals(Generic.conditions(certificate(Map.empty)), Map.empty)
  }
