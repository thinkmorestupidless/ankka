package com.thinkmorestupidless.ankka.operator.strimzi

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import io.fabric8.kubernetes.api.model.{Namespaced, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, Version}

/**
 * How the broker knows the user: `tls-external`, a client certificate Strimzi did not issue. The
 * service's own certificate, from the installation's service authority; Strimzi makes no credential
 * and writes no Secret.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaUserAuthentication(`type`: String = "tls-external")

/** What a permission is on: a topic or a group, named exactly or by a prefix. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AclResource(
    `type`: String = "",
    name: String = "",
    patternType: String = "literal"
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AclRule(
    resource: AclResource = AclResource(),
    operations: Vector[String] = Vector.empty
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaUserAuthorization(
    `type`: String = "simple",
    acls: Vector[AclRule] = Vector.empty
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaUserSpec(
    authentication: KafkaUserAuthentication = KafkaUserAuthentication(),
    authorization: KafkaUserAuthorization = KafkaUserAuthorization()
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class KafkaUserStatus(
    conditions: Vector[StrimziCondition] = Vector.empty,
    observedGeneration: Option[Long] = None,
    /** The principal the broker knows the user as: `CN=<project>.<service>`. */
    username: Option[String] = None
) extends StrimziStatus

@Group("kafka.strimzi.io")
@Version("v1")
@Kind("KafkaUser")
@Plural("kafkausers")
class KafkaUserResource extends CustomResource[KafkaUserSpec, KafkaUserStatus] with Namespaced:
  override protected def initSpec(): KafkaUserSpec     = KafkaUserSpec()
  override protected def initStatus(): KafkaUserStatus = null

object KafkaUserResource:
  val identity: StrimziDefinitions.Identity =
    StrimziDefinitions.identityOf(classOf[KafkaUserResource])

  def apply(
      namespace: String,
      name: String,
      labels: Map[String, String],
      spec: KafkaUserSpec
  ): KafkaUserResource =
    val resource = new KafkaUserResource
    resource.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .withLabels(scala.jdk.CollectionConverters.MapHasAsJava(labels).asJava)
        .build()
    )
    resource.setSpec(spec)
    resource
