package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  ProjectBrokerEntry,
  AnkkaProjectSpec,
  AnkkaProjectStatus,
  AnkkaServiceSpec,
  AnkkaServiceStatus,
  CloudKinds,
  CloudResourceSpec,
  CloudResourceStatus,
  CloudSubject,
  ObjectStorageStatus,
  ProjectTopicEntry,
  ProjectTopicStatus
}
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

  private def load(file: String): CustomResourceDefinition =
    val stream = getClass.getResourceAsStream(s"/ankka/crd/$file")
    assert(stream != null, s"$file was not found on the classpath — check the crd symlink")
    try Serialization.unmarshal(stream, classOf[CustomResourceDefinition])
    finally stream.close()

  private val crd: CustomResourceDefinition        = load("ankkaservice.yaml")
  private val projectCrd: CustomResourceDefinition = load("ankkaproject.yaml")
  private val cloudCrd: CustomResourceDefinition   = load("cloudresource.yaml")

  /** The property names one object in the service's schema declares. */
  private def declared(path: String*): Set[String] = declaredIn(crd, path*)

  /**
   * The property names one object in a schema declares; `items` steps into an array's elements.
   */
  private def declaredIn(crd: CustomResourceDefinition, path: String*): Set[String] =
    val version = crd.getSpec.getVersions.asScala.headOption
      .getOrElse(fail("the CRD declares no version"))
    var schema = Option(version.getSchema)
      .flatMap(s => Option(s.getOpenAPIV3Schema))
      .getOrElse(fail("the CRD's version has no schema"))
    path.foreach { name =>
      schema =
        if name == "items" then
          Option(schema.getItems).flatMap(i => Option(i.getSchema)).getOrElse(fail("no items"))
        else
          Option(schema.getProperties)
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

  test("a project's resource and its schema declare the same fields, at every level") {
    assertEquals(declaredIn(projectCrd, "spec"), fieldsOf(classOf[AnkkaProjectSpec]))
    assertEquals(declaredIn(projectCrd, "status"), fieldsOf(classOf[AnkkaProjectStatus]))
    assertEquals(
      declaredIn(projectCrd, "spec", "topics", "items"),
      fieldsOf(classOf[ProjectTopicEntry])
    )
    assertEquals(
      declaredIn(projectCrd, "status", "topics", "items"),
      fieldsOf(classOf[ProjectTopicStatus])
    )
    assertEquals(
      declaredIn(projectCrd, "spec", "brokers", "items"),
      fieldsOf(classOf[ProjectBrokerEntry])
    )
  }

  test("the schema's hostings are exactly the ones the operator renders") {
    // The structural schema refuses a value outside its enum, so a hosting the operator learns and
    // the CRD does not is refused by the API server on every projection: offline tests cannot see it.
    val hosting = crd.getSpec.getVersions.asScala.head.getSchema.getOpenAPIV3Schema.getProperties
      .get("spec")
      .getProperties
      .get("hosting")
    val enumerated = hosting.getEnum.asScala.map(_.asText).toSet
    assertEquals(enumerated, Rendering.Hostings)
  }

  test("the object storage status block is declared field for field, in both directions") {
    // A closed schema checks a nested object's properties too, and the top-level cases above do not
    // look inside one: a field missing here would be refused on every status write, silently.
    val inSchema = declared("status", "objectStorage")
    val inClass  = fieldsOf(classOf[ObjectStorageStatus])
    assertEquals(inClass -- inSchema, Set.empty[String], "fields the schema does not declare")
    assertEquals(inSchema -- inClass, Set.empty[String], "properties the status cannot carry")
  }

  test("a move's request and its state are declared field for field, in both directions") {
    // Feature 039: two more nested objects, each refused on every write if it drifted from its class.
    assertEquals(
      declared("spec", "objectStorageMove"),
      fieldsOf(classOf[com.thinkmorestupidless.ankka.crd.ObjectStorageMoveRequest])
    )
    assertEquals(
      declared("status", "objectStorage", "move"),
      fieldsOf(classOf[com.thinkmorestupidless.ankka.crd.MoveStatus])
    )
  }

  test("a move's states are exactly the ones the operator writes") {
    val state = crd.getSpec.getVersions.asScala.head.getSchema.getOpenAPIV3Schema.getProperties
      .get("status")
      .getProperties
      .get("objectStorage")
      .getProperties
      .get("move")
      .getProperties
      .get("state")
    assertEquals(state.getEnum.asScala.map(_.asText).toSet, StorageMove.States)
  }

  test("the object storage phases are the database's") {
    def phases(block: String) =
      crd.getSpec.getVersions.asScala.head.getSchema.getOpenAPIV3Schema.getProperties
        .get("status")
        .getProperties
        .get(block)
        .getProperties
        .get("phase")
        .getEnum
        .asScala
        .map(_.asText)
        .toSet
    assertEquals(phases("objectStorage"), phases("database"))
  }

  test("a cloud request and its schema declare the same fields, at every level, both ways") {
    // Feature 044. The operator applies the spec and a provider writes the status; a field either
    // side carries that the schema does not declare is refused on every write, and only a real API
    // server would say so.
    def same(clazz: Class[?], path: String*) =
      val inSchema = declaredIn(cloudCrd, path*)
      val inClass  = fieldsOf(clazz)
      assertEquals(inClass -- inSchema, Set.empty[String], s"${path.mkString(".")}: undeclared")
      assertEquals(inSchema -- inClass, Set.empty[String], s"${path.mkString(".")}: uncarried")
    same(classOf[CloudResourceSpec], "spec")
    same(classOf[CloudSubject], "spec", "subject")
    same(classOf[CloudResourceStatus], "status")
  }

  test("a cloud request's kinds and phases are the contract's, exactly") {
    def enumOf(path: String*) =
      var schema = cloudCrd.getSpec.getVersions.asScala.head.getSchema.getOpenAPIV3Schema
      path.foreach(name => schema = schema.getProperties.get(name))
      schema.getEnum.asScala.map(_.asText).toVector
    assertEquals(enumOf("spec", "kind"), CloudKinds.all)
    assertEquals(enumOf("status", "phase"), CloudKinds.phases)
  }

  test("a cloud request has a status subresource, so a provider's write leaves the spec alone") {
    val version = cloudCrd.getSpec.getVersions.asScala.head
    assert(version.getSubresources != null && version.getSubresources.getStatus != null)
    assertEquals(cloudCrd.getSpec.getScope, "Namespaced")
    assertEquals(cloudCrd.getSpec.getNames.getPlural, "cloudresources")
  }
