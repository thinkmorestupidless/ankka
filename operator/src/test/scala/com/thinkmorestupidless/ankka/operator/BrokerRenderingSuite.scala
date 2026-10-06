package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, EnvEntry}
import com.thinkmorestupidless.ankka.operator.strimzi.{
  AclResource,
  AclRule,
  KafkaUserAuthentication,
  KafkaUserAuthorization,
  KafkaUserSpec,
  StrimziDefinitions
}
import io.fabric8.kubernetes.api.model.{Container, GenericKubernetesResource, ObjectMetaBuilder}

import scala.jdk.CollectionConverters.*

/**
 * What the operator renders of the installation's broker, row by row of `contracts/operator.md`'s
 * "What is rendered for whom": the objects themselves, never a string found somewhere.
 */
class BrokerRenderingSuite extends munit.FunSuite:

  private val withBroker = Settings.default.copy(
    sidecarImage = "ankka-sidecar:9.9.9",
    broker = Some(BrokerStack.settings)
  )
  private val withoutBroker = withBroker.copy(broker = None)

  private val wallet = AnkkaServiceSpec(
    projectId = "money",
    serviceName = "wallet",
    generation = 1L,
    image = "wallet:1",
    port = Some(9000),
    provisionDatabase = false,
    env = List(EnvEntry("ANKKA_DB_HOST", Some("db"), None, None))
  )

  /** A service as every case renders it; topics are the project's, rendered elsewhere. */
  private val declaring = wallet

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withNamespace("ankka-money")
        .withName(spec.serviceName)
        .withUid("u")
        .build()
    )
    r.setSpec(spec)
    r

  private def actions(spec: AnkkaServiceSpec, settings: Settings = withBroker): Vector[Action] =
    Rendering
      .render(
        resource(spec),
        settings,
        ProvisioningPlan.Supplied,
        BrokerProvisioning.known(spec, settings.broker)
      )
      .fold(problems => fail(problems.mkString("; ")), identity)

  private def users(as: Vector[Action])  = as.collect { case Action.EnsureKafkaUser(u) => u }
  private def topics(as: Vector[Action]) = as.collect { case Action.EnsureKafkaTopic(t) => t }

  private def containers(as: Vector[Action]): Vector[Container] =
    as.collect { case Action.ApplyDeployment(d) => d }
      .head
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .asScala
      .toVector

  private def env(c: Container): Map[String, String] =
    c.getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("<ref>")).toMap

  private def serviceCertificate(as: Vector[Action]): GenericKubernetesResource =
    as.collect {
      case Action.EnsureCertificate(c) if c.getMetadata.getName == "wallet-service" => c
    }.head

  private def commonName(as: Vector[Action]): Option[String] =
    Option(serviceCertificate(as).getAdditionalProperties.get("spec"))
      .collect { case m: java.util.Map[?, ?] => m.get("commonName") }
      .flatMap(Option(_))
      .map(_.toString)

  private val brokerVariables = Map(
    "ANKKA_KAFKA_BOOTSTRAP_SERVERS" -> "ankka-kafka-bootstrap.ankka-broker.svc:9093",
    "ANKKA_KAFKA_TLS_DIRECTORY"     -> "/var/run/secrets/ankka/service",
    "ANKKA_KAFKA_TOPIC_PREFIX"      -> "money."
  )

  test("the user's permissions end at the project's topics and the service's own groups") {
    val user = users(actions(declaring)).head
    assertEquals(user.getMetadata.getName, "money.wallet")
    assertEquals(user.getMetadata.getNamespace, "ankka-broker")
    assertEquals(user.getMetadata.getLabels.get(StrimziDefinitions.ClusterLabel), "ankka")
    assertEquals(
      user.getSpec,
      KafkaUserSpec(
        KafkaUserAuthentication("tls-external"),
        KafkaUserAuthorization(
          "simple",
          Vector(
            AclRule(AclResource("topic", "money.", "prefix"), Vector("Read", "Write", "Describe")),
            AclRule(AclResource("group", "ankka.money.wallet.", "prefix"), Vector("Read"))
          )
        )
      )
    )
    // Nothing of the kind lets a service make a topic by using one, or touch the cluster.
    assert(!user.getSpec.authorization.acls.exists(_.operations.contains("Create")))
    assert(!user.getSpec.authorization.acls.exists(_.resource.`type` == "cluster"))
  }

  test("a service's user is owned by nothing, and nothing removes it") {
    val as = actions(declaring)
    for r <- users(as) ++ topics(as) do
      assert(Option(r.getMetadata.getOwnerReferences).forall(_.isEmpty), r.getMetadata.getName)
    // No action of any kind removes a topic or a user.
    assert(
      !as.exists(_.describe.matches("(?i).*(remove|delete).*kafka.*")),
      as.map(_.describe).toString
    )
  }

  test("a service is told where the installation's broker is") {
    val as = actions(wallet)
    assertEquals(
      env(containers(as).head).filter((k, _) => k.startsWith("ANKKA_KAFKA_")),
      brokerVariables
    )
    assertEquals(users(as).map(_.getMetadata.getName), Vector("money.wallet"))
    assertEquals(topics(as), Vector.empty)
  }

  test(
    "both programs of a service hosted as a process are told where the installation's broker is"
  ) {
    val cs = containers(actions(declaring.copy(hosting = "process")))
    assertEquals(cs.map(_.getName), Vector("wallet", "wallet-app"))
    for c <- cs do
      assertEquals(
        env(c).filter((k, _) => k.startsWith("ANKKA_KAFKA_")),
        brokerVariables,
        c.getName
      )
  }

  test("a module is told where the installation's broker is, in its one container") {
    val cs = containers(actions(declaring.copy(hosting = "wasm")))
    assertEquals(cs.size, 1)
    assertEquals(env(cs.head).filter((k, _) => k.startsWith("ANKKA_KAFKA_")), brokerVariables)
  }

  test("a service proves which service it is to the broker with its certificate") {
    val as = actions(declaring)
    assertEquals(commonName(as), Some("money.wallet"))
    // The certificate the service already holds: no other certificate, volume or Secret appears.
    val without = actions(declaring, withoutBroker)
    assertEquals(
      as.collect { case Action.EnsureCertificate(c) => c.getMetadata.getName },
      without.collect { case Action.EnsureCertificate(c) => c.getMetadata.getName }
    )
    assertEquals(
      containers(as).flatMap(_.getVolumeMounts.asScala.map(_.getMountPath)),
      containers(without).flatMap(_.getVolumeMounts.asScala.map(_.getMountPath))
    )
  }

  test("a web-hosted service is given nothing of the installation's broker") {
    val web = wallet.copy(hosting = "web", provisionDatabase = false, env = Nil)
    val as  = actions(web)
    assertEquals(users(as) ++ topics(as), Vector.empty)
    assert(containers(as).forall(c => !env(c).keys.exists(_.startsWith("ANKKA_KAFKA_"))))
    assertEquals(commonName(as), None)
  }

  test("a service whose descriptor names a broker is given nothing on the installation's") {
    val supplied = wallet.copy(
      provisionBroker = false,
      env = wallet.env :+ EnvEntry("ANKKA_KAFKA_BOOTSTRAP_SERVERS", Some("mine:9092"), None, None)
    )
    val as = actions(supplied)
    assertEquals(users(as) ++ topics(as), Vector.empty)
    assertEquals(
      env(containers(as).head).filter((k, _) => k.startsWith("ANKKA_KAFKA_")),
      Map("ANKKA_KAFKA_BOOTSTRAP_SERVERS" -> "mine:9092")
    )
    assertEquals(commonName(as), None)
  }

  test("a service of an installation with no broker is deployed as it was before") {
    // Rendered with no broker at all, and with the topics argument absent as every caller before
    // this feature passed it: the two are the same, object for object.
    val now = actions(wallet, withoutBroker)
    val before =
      Rendering.render(resource(wallet), withoutBroker, ProvisioningPlan.Supplied).toOption.get
    assertEquals(now.map(_.describe), before.map(_.describe))
    assertEquals(containers(now).map(env), containers(before).map(env))
    assertEquals(commonName(now), None)
  }
