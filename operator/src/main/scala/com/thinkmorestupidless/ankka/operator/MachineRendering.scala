package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaMachineSpec, AnkkaProjectSpec}
import com.thinkmorestupidless.ankka.operator.strimzi.{
  AclResource,
  AclRule,
  KafkaUserAuthorization,
  KafkaUserQuotas,
  KafkaUserResource,
  KafkaUserSpec,
  StrimziDefinitions
}

/**
 * What a machine may do on the installation's broker unless its organization says otherwise
 * (feature 040), and the most it may ever be allowed.
 */
final case class MachineDefaults(
    produceBytesPerSecond: Long = MachineDefaults.ProduceBytes,
    consumeBytesPerSecond: Long = MachineDefaults.ConsumeBytes,
    requestPercentage: Int = MachineDefaults.RequestPercentage,
    byteRateCeiling: Long = MachineDefaults.Ceiling
)

object MachineDefaults:
  val ProduceBytes: Long     = 1048576L
  val ConsumeBytes: Long     = 4194304L
  val RequestPercentage: Int = 50
  val Ceiling: Long          = 33554432L

/**
 * A registered machine's user on the installation's broker (feature 040), `machine.<org>.<name>` in
 * the broker's namespace. It has no authentication of its own: the external listener names the
 * principal from the `broker_user` claim of the token the control plane issued the machine. It may
 * read and publish exactly the topics its accepted grants name, read under its own group prefix,
 * and no more than its byte rates, clamped to the installation's ceiling.
 */
object MachineRendering:

  def userName(organizationId: String, name: String): String =
    s"machine.$organizationId.$name"

  def groupPrefix(organizationId: String, name: String): String =
    s"ankka.machine.$organizationId.$name."

  /** The grantee word a grant to a machine carries. */
  def grantee(organizationId: String, name: String): String = s"machine:$organizationId/$name"

  /** Every accepted topic grant naming the machine, from whichever project made it. */
  def granted(
      projects: Iterable[AnkkaProjectSpec],
      organizationId: String,
      name: String
  ): Vector[GrantedTopic] =
    val word = grantee(organizationId, name)
    projects.toVector
      .flatMap(p =>
        p.grants.collect {
          case g
              if g.kind == "topic" && g.grantee == word && g.topic.isDefined && g.right.isDefined =>
            GrantedTopic(p.projectId, g.topic.get, g.right.get)
        }
      )
      .distinct
      .sortBy(g => (g.project, g.topic, g.right))

  /**
   * The user, for a machine that is registered (`spec`) or that is gone (`None`): a machine deleted
   * keeps its user, which grants nothing on any topic, since a user once made is never removed.
   */
  def user(
      organizationId: String,
      name: String,
      spec: Option[AnkkaMachineSpec],
      granted: Vector[GrantedTopic],
      defaults: MachineDefaults,
      broker: BrokerSettings
  ): KafkaUserResource =
    val topics = if spec.isDefined then StrimziRendering.grantedRules(granted) else Vector.empty
    def clamp(rate: Long) = rate.max(1L).min(defaults.byteRateCeiling)
    KafkaUserResource(
      broker.namespace,
      userName(organizationId, name),
      Map(
        Labels.ManagedByKey                          -> Labels.ManagedByAnkka,
        StrimziDefinitions.ClusterLabel              -> broker.cluster,
        "ankka.thinkmorestupidless.com/organization" -> organizationId
      ),
      KafkaUserSpec(
        authentication = None,
        authorization = KafkaUserAuthorization(
          "simple",
          topics :+ AclRule(
            AclResource("group", groupPrefix(organizationId, name), "prefix"),
            Vector("Read")
          )
        ),
        quotas = Some(
          KafkaUserQuotas(
            producerByteRate = Some(
              clamp(spec.flatMap(_.produceBytesPerSecond).getOrElse(defaults.produceBytesPerSecond))
            ),
            consumerByteRate = Some(
              clamp(spec.flatMap(_.consumeBytesPerSecond).getOrElse(defaults.consumeBytesPerSecond))
            ),
            requestPercentage = Some(
              spec
                .flatMap(_.requestPercentage)
                .getOrElse(defaults.requestPercentage)
                .max(1)
                .min(100)
            )
          )
        )
      )
    )
