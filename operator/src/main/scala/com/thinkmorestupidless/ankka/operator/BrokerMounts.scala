package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, ProjectBrokerEntry}
import io.fabric8.kubernetes.api.model.{
  EnvVar,
  EnvVarBuilder,
  SecretVolumeSourceBuilder,
  Volume,
  VolumeBuilder,
  VolumeMountBuilder
}
import io.fabric8.kubernetes.api.model.apps.Deployment

import scala.jdk.CollectionConverters.*

/**
 * A project's declared brokers on a service (feature 037): each broker's project secret mounted
 * read-only on the platform container, with the variables the runtime reads to reach it. Every
 * service of the project is given every declared broker, since a service is rendered before its
 * components are known; the process container is given nothing, so a credential never reaches it. A
 * web-hosted service has no runtime and mounts nothing.
 */
object BrokerMounts:

  val MountRoot: String = "/var/run/secrets/ankka/brokers"

  private def volumeName(broker: String) = s"ankka-broker-$broker"

  def variables(broker: ProjectBrokerEntry): Vector[EnvVar] =
    val upper = broker.name.toUpperCase.replace('-', '_')
    def literal(name: String, value: String) =
      new EnvVarBuilder().withName(name).withValue(value).build()
    Vector(
      literal(s"ANKKA_TOPIC_BROKER_${upper}_NAME", broker.name),
      literal(s"ANKKA_TOPIC_BROKER_${upper}_BOOTSTRAP_SERVERS", broker.bootstrap),
      literal(s"ANKKA_TOPIC_BROKER_${upper}_SHAPE", broker.shape),
      literal(s"ANKKA_TOPIC_BROKER_${upper}_SECRET_DIRECTORY", s"$MountRoot/${broker.name}")
    )

  def volume(broker: ProjectBrokerEntry): Volume =
    new VolumeBuilder()
      .withName(volumeName(broker.name))
      .withSecret(new SecretVolumeSourceBuilder().withSecretName(broker.secretName).build())
      .build()

  /** The rendered Deployment with the brokers attached, or as it was when there are none. */
  def attach(
      deployment: Deployment,
      spec: AnkkaServiceSpec,
      brokers: Vector[ProjectBrokerEntry]
  ): Deployment =
    if brokers.isEmpty || spec.hosting == Rendering.WebHosting then deployment
    else
      val pod      = deployment.getSpec.getTemplate.getSpec
      val sorted   = brokers.sortBy(_.name)
      val platform = Names.container(spec.serviceName)
      pod.setVolumes((pod.getVolumes.asScala.toVector ++ sorted.map(volume)).asJava)
      pod.getContainers.asScala.filter(_.getName == platform).foreach { container =>
        container.setEnv((container.getEnv.asScala.toVector ++ sorted.flatMap(variables)).asJava)
        container.setVolumeMounts(
          (container.getVolumeMounts.asScala.toVector ++ sorted.map(b =>
            new VolumeMountBuilder()
              .withName(volumeName(b.name))
              .withMountPath(s"$MountRoot/${b.name}")
              .withReadOnly(true)
              .build()
          )).asJava
        )
      }
      deployment
