package com.thinkmorestupidless.ankka.crd

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.utils.KubernetesSerialization

/**
 * The resource's identity, read back off the annotations rather than restated.
 *
 * Declaring the group and version twice — once for fabric8 and once for anything that needs the
 * strings — is how the two drift apart. These derive from the class, so they cannot.
 */
object AnkkaServiceDefinition:

  val resourceClass: Class[AnkkaService] = classOf[AnkkaService]

  val group: String   = HasMetadata.getGroup(resourceClass)
  val version: String = HasMetadata.getVersion(resourceClass)
  val kind: String    = HasMetadata.getKind(resourceClass)
  val plural: String  = HasMetadata.getPlural(resourceClass)

  /** `ankka.thinkmorestupidless.com/v1alpha1`. */
  val apiVersion: String = HasMetadata.getApiVersion(resourceClass)

  /** `ankkaservices.ankka.thinkmorestupidless.com` — the name of the definition itself. */
  val crdName: String = s"$plural.$group"

  /** Where the CRD manifest lives on the classpath, so no caller hardcodes the path. */
  val manifestResource: String = "/ankka/crd/ankkaservice.yaml"

/**
 * Serialization that understands Scala.
 *
 * fabric8 serialises with Jackson, which without the Scala module encodes `Option` as
 * `{"empty":false,"defined":true}` and does not round-trip Scala collections at all. Both are
 * silent, and both only surface once a resource reaches a real API server.
 *
 * Built explicitly and handed to each client rather than mutating fabric8's global mapper: a static
 * mutation that has to happen before the first serialization is a startup-order bug waiting to be
 * written.
 */
object AnkkaSerialization:

  def mapper(): ObjectMapper =
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
    // A field this side does not know about must not be fatal. The two processes version
    // independently (FR-003), so a newer control plane writing a field an older operator
    // predates has to leave that operator able to reconcile everything it does understand.
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    m

  def apply(): KubernetesSerialization = new KubernetesSerialization(mapper(), true)
