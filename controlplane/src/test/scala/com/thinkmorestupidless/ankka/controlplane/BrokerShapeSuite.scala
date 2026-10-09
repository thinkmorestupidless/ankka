package com.thinkmorestupidless.ankka.controlplane

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import munit.FunSuite

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/**
 * The broker's shape, by value (feature 043): the `broker-three-nodes` component, rendered over the
 * local overlay by `kustomization/tests/broker-three-nodes`, sets every setting that counts copies
 * for three nodes and has the control plane give a topic three copies, two in sync; the local
 * overlay alone still says one everywhere. Asked of the parsed resources, each setting once, never
 * of the render's text, which a stray word in a schema can satisfy.
 *
 * Skips when `kubectl` is absent, as `RemoteOverlaySuite` does.
 */
final class BrokerShapeSuite extends FunSuite with LogCapturing:

  private val kubectl = Try("kubectl version --client".!(ProcessLogger(_ => ()))).getOrElse(1) == 0

  override def munitIgnore: Boolean = !kubectl

  // Two renders of the whole installation; on a loaded machine each can take most of a minute.
  override val munitTimeout = scala.concurrent.duration.Duration(3, "min")

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  private def render(dir: String): Vector[JsonNode] =
    val out    = Process(Seq("kubectl", "kustomize", repoRoot.resolve(dir).toString)).!!
    val mapper = YAMLMapper()
    mapper
      .readerFor(classOf[JsonNode])
      .readValues[JsonNode](out)
      .readAll()
      .asScala
      .toVector
      .filter(_ != null)

  private lazy val three = render("kustomization/tests/broker-three-nodes")
  private lazy val one   = render("kustomization/overlays/local")

  private def only(docs: Vector[JsonNode], kind: String, name: String): JsonNode =
    val found = docs.filter(d =>
      d.path("kind").asText == kind && d.path("metadata").path("name").asText == name
    )
    assertEquals(found.size, 1, s"$kind $name")
    found.head

  private def kafkaConfig(docs: Vector[JsonNode]): Map[String, String] =
    val config = only(docs, "Kafka", "ankka").path("spec").path("kafka").path("config")
    config.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap

  private def controlPlaneEnv(docs: Vector[JsonNode]): Vector[(String, String)] =
    only(docs, "Deployment", "ankka-controlplane")
      .path("spec")
      .path("template")
      .path("spec")
      .path("containers")
      .elements
      .asScala
      .find(_.path("name").asText == "ankka-controlplane")
      .getOrElse(fail("no ankka-controlplane container"))
      .path("env")
      .elements
      .asScala
      .toVector
      .map(e => e.path("name").asText -> e.path("value").asText)

  private val Copies = Map(
    "default.replication.factor"               -> ("3", "1"),
    "min.insync.replicas"                      -> ("2", "1"),
    "offsets.topic.replication.factor"         -> ("3", "1"),
    "transaction.state.log.replication.factor" -> ("3", "1"),
    "transaction.state.log.min.isr"            -> ("2", "1")
  )

  // features/broker/copies.feature
  test(
    "an installation installed with three broker nodes sets every setting that counts copies for three"
  ) {
    assertEquals(only(three, "KafkaNodePool", "dual").path("spec").path("replicas").asInt, 3)
    val config = kafkaConfig(three)
    Copies.foreach((key, values) => assertEquals(config.get(key), Some(values._1), key))
    val env = controlPlaneEnv(three)
    for (name, value) <- Seq(
        "ANKKA_TOPIC_DEFAULT_COPIES"      -> "3",
        "ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC" -> "2"
      )
    do
      assertEquals(env.filter(_._1 == name), Vector(name -> value), s"$name is set once, to $value")
    // The component changes nothing else the broker counts.
    assertEquals(config.get("auto.create.topics.enable"), Some("false"))
  }

  test("an installation without the component is one node, with one copy everywhere") {
    assertEquals(only(one, "KafkaNodePool", "dual").path("spec").path("replicas").asInt, 1)
    val config = kafkaConfig(one)
    Copies.foreach((key, values) => assertEquals(config.get(key), Some(values._2), key))
    val env = controlPlaneEnv(one)
    assertEquals(
      env.filter(_._1 == "ANKKA_TOPIC_DEFAULT_COPIES"),
      Vector("ANKKA_TOPIC_DEFAULT_COPIES" -> "1")
    )
    assertEquals(
      env.filter(_._1 == "ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC"),
      Vector("ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC" -> "1")
    )
  }
