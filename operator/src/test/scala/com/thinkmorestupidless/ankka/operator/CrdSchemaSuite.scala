package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, AnkkaServiceStatus}
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.client.utils.Serialization

import scala.jdk.CollectionConverters.*

/**
 * The resource's Scala shape and its published schema describe the same resource.
 *
 * A structural schema is *closed*: a server-side apply carrying a field the CRD does not declare is
 * rejected outright with `failed to create typed patch object … field not declared in schema`, and
 * a 500 at that. So a field added to `AnkkaServiceSpec` without being added to `ankkaservice.yaml`
 * breaks every projection of every service the moment it is set — while every offline test passes,
 * because nothing but a real API server validates against the schema.
 *
 * That is what happened to `imagePullSecret`, found by a k3s suite three runs in. This suite turns
 * the same mistake into a failure that takes milliseconds and names the field.
 */
class CrdSchemaSuite extends munit.FunSuite:

  private val crd: CustomResourceDefinition =
    val stream = getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")
    assert(stream != null, "the CRD was not found on the classpath — check the crd symlink")
    try Serialization.unmarshal(stream, classOf[CustomResourceDefinition])
    finally stream.close()

  /** The property names one object in the schema declares. */
  private def declared(path: String*): Set[String] =
    val version = crd.getSpec.getVersions.asScala.headOption
      .getOrElse(fail("the CRD declares no version"))
    var schema = Option(version.getSchema)
      .flatMap(s => Option(s.getOpenAPIV3Schema))
      .getOrElse(fail("the CRD's version has no schema"))
    path.foreach { name =>
      schema = Option(schema.getProperties)
        .flatMap(p => Option(p.get(name)))
        .getOrElse(fail(s"the schema declares no '$name'"))
    }
    Option(schema.getProperties).map(_.asScala.keySet.toSet).getOrElse(Set.empty)

  /**
   * A case class's fields, in declaration order.
   *
   * Reflection over the compiled class rather than a mirror: this only needs the names, and a list
   * that has to be kept in step by hand is exactly the thing this suite exists to stop.
   */
  private def fieldsOf(clazz: Class[?]): Set[String] =
    clazz.getDeclaredFields.map(_.getName).filterNot(_.startsWith("$")).toSet

  test("every field of AnkkaServiceSpec is declared in the CRD's schema") {
    val missing = fieldsOf(classOf[AnkkaServiceSpec]) -- declared("spec")
    assertEquals(
      missing,
      Set.empty[String],
      s"these fields would be rejected by a real API server: ${missing.mkString(", ")}"
    )
  }

  test("every field of AnkkaServiceStatus is declared in the CRD's schema") {
    val missing = fieldsOf(classOf[AnkkaServiceStatus]) -- declared("status")
    assertEquals(
      missing,
      Set.empty[String],
      s"these status fields would be rejected by a real API server: ${missing.mkString(", ")}"
    )
  }

  test("the schema declares nothing the resource cannot carry") {
    // The other direction, which is not a rejection but a lie: a documented field that no code reads
    // is a promise the platform does not keep, and the only place anyone would find that out is by
    // setting it and watching nothing happen.
    val spurious = declared("spec") -- fieldsOf(classOf[AnkkaServiceSpec])
    assertEquals(
      spurious,
      Set.empty[String],
      s"the schema declares fields AnkkaServiceSpec does not have: ${spurious.mkString(", ")}"
    )
  }
