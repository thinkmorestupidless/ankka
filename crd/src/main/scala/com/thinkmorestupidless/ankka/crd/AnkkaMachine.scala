package com.thinkmorestupidless.ankka.crd

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, ShortNames, Version}

/**
 * A machine outside the installation, registered on an organization (feature 040): what the
 * operator needs to give it a user on the installation's broker. Written by the control plane when
 * the machine is registered or its byte rates change, and deleted with the machine. Its grants are
 * the granting projects', read from their `AnkkaProject` resources. No credential is here: the
 * broker knows a machine by the token the control plane issues it.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaMachineSpec(
    organizationId: String = "",
    name: String = "",
    // Erasure hides an Option's element from Jackson, which reads the number as an Integer.
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    produceBytesPerSecond: Option[Long] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    consumeBytesPerSecond: Option[Long] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    requestPercentage: Option[Int] = None
)

/** How far the operator has got with the machine's broker user: Waiting, Provisioned or Failed. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaMachineStatus(
    user: Option[String] = None,
    phase: String = "",
    detail: Option[String] = None
)

@Group("ankka.thinkmorestupidless.com")
@Version("v1alpha1")
@Kind("AnkkaMachine")
@Plural("ankkamachines")
@ShortNames(Array("amach"))
class AnkkaMachine extends CustomResource[AnkkaMachineSpec, AnkkaMachineStatus]:
  override protected def initSpec(): AnkkaMachineSpec = AnkkaMachineSpec()

  /** Null until the operator has reported. */
  override protected def initStatus(): AnkkaMachineStatus = null

object AnkkaMachine:
  /**
   * `<organization>.<name>`: both are DNS labels, which hold no dot, so no two machines share it.
   */
  def nameOf(organizationId: String, name: String): String = s"$organizationId.$name"

  def apply(spec: AnkkaMachineSpec): AnkkaMachine =
    val resource = new AnkkaMachine
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withName(nameOf(spec.organizationId, spec.name))
        .build()
    )
    resource.setSpec(spec)
    resource
