package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * A descriptor's declared topics: what it may say, and every refusal, in the words the CLI and the
 * control plane both print (contracts/descriptor.md, "Refusals from the descriptor's own rules").
 */
class TopicsDescriptorSuite extends munit.FunSuite:

  private val wallet = ServiceSpec(image = "wallet:1")

  private def problems(spec: ServiceSpec, name: String = "wallet"): Vector[String] =
    ServiceDescriptor(name, spec).problems

  private def refused(spec: ServiceSpec, message: String)(using munit.Location): Unit =
    val found = problems(spec)
    assert(found.contains(message), s"expected «$message» among $found")

  private def declaring(topics: TopicDeclaration*): ServiceSpec =
    wallet.copy(topics = topics.toVector)

  test("a descriptor that declares topics of the right shape is valid") {
    assertEquals(
      problems(
        declaring(TopicDeclaration("transactions", 12), TopicDeclaration("wallet-events", 3))
      ),
      Vector.empty
    )
    assertEquals(
      problems(declaring(TopicDeclaration("a", 1), TopicDeclaration("v1.audit", 1000))),
      Vector.empty
    )
  }

  test("a descriptor that declares no topics says nothing about them on the wire") {
    assert(!writeToString(ServiceDescriptor("wallet", wallet)).contains("topics"))
    val declared = ServiceDescriptor("wallet", declaring(TopicDeclaration("transactions", 12)))
    assertEquals(readFromString[ServiceDescriptor](writeToString(declared)), declared)
  }

  test("a topic whose name is of the wrong shape is refused") {
    val rule =
      "a name is lower-case letters, digits, \"-\" and \".\", starting and ending with a letter " +
        "or digit, at most 100 characters"
    for name <- Vector(
        "Transactions",
        "not a name",
        "-leading",
        "trailing.",
        "under_score",
        "",
        "x" * 101
      )
    do refused(declaring(TopicDeclaration(name, 1)), s"topic '$name': $rule")
  }

  test("a topic whose partitions are out of range is refused") {
    for n <- Vector(0, -1, 1001) do
      refused(
        declaring(TopicDeclaration("transactions", n)),
        s"topic 'transactions': partitions $n is outside the range 1-1000"
      )
  }

  test("a topic declared twice is refused") {
    refused(
      declaring(TopicDeclaration("transactions", 12), TopicDeclaration("transactions", 12)),
      "topic 'transactions' is declared more than once"
    )
  }

  test("a web-hosted service declares no topics") {
    refused(
      declaring(TopicDeclaration("transactions", 12)).copy(hosting = ServiceSpec.Web),
      "topics is meaningful only for a service with components; a web-hosted service declares none"
    )
  }

  test("topics beside a broker variable are refused, naming both") {
    val spec = declaring(TopicDeclaration("transactions", 12))
      .copy(env = Vector(EnvVar("ANKKA_KAFKA_BOOTSTRAP_SERVERS", Some("kafka:9092"))))
    refused(
      spec,
      "topics are declared for the installation's broker, and env var " +
        "'ANKKA_KAFKA_BOOTSTRAP_SERVERS' names another; remove one"
    )
  }

  test("every problem is reported at once") {
    val spec =
      declaring(TopicDeclaration("Bad", 0), TopicDeclaration("ok", 1), TopicDeclaration("ok", 1))
        .copy(env = Vector(EnvVar("ANKKA_KAFKA_BOOTSTRAP_SERVERS", Some("kafka:9092"))))
    val found = problems(spec)
    assert(found.exists(_.startsWith("topic 'Bad': a name")), found.toString)
    assert(found.exists(_.contains("partitions 0")), found.toString)
    assert(found.exists(_.contains("'ok' is declared more than once")), found.toString)
    assert(found.exists(_.contains("names another")), found.toString)
  }

  test("a descriptor supplies its broker by a variable's name, whatever the value") {
    assert(!wallet.suppliesBroker)
    assert(
      wallet
        .copy(env = Vector(EnvVar("ANKKA_KAFKA_BOOTSTRAP_SERVERS", Some("k:9092"))))
        .suppliesBroker
    )
    assert(
      wallet
        .copy(env =
          Vector(EnvVar("ANKKA_KAFKA_SASL", secretKeyRef = Some(SecretKeyRef("kafka", "sasl"))))
        )
        .suppliesBroker
    )
    assert(!wallet.copy(env = Vector(EnvVar("KAFKA_HOST", Some("k")))).suppliesBroker)
  }

  test(
    "what a member reads carries the broker's phrase and the qualified topics, and omits both when empty"
  ) {
    val base = ServiceStatus(
      name = "wallet",
      projectId = "money",
      lifecycle = ServiceLifecycle.Ready,
      generation = 1L,
      image = "wallet:1",
      readyInstances = 1,
      desiredInstances = 1
    )
    val status = base.copy(broker = Some("provisioned"), topics = Vector("money.transactions"))
    val json   = writeToString(status)
    assert(json.contains("\"broker\":\"provisioned\""), json)
    assertEquals(readFromString[ServiceStatus](json), status)
    val none = writeToString(base)
    assert(!none.contains("broker") && !none.contains("topics"), none)
  }
