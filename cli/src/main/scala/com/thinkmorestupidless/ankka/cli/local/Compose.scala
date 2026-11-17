package com.thinkmorestupidless.ankka.cli.local

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import scala.collection.immutable.ListMap

/**
 * The compose document `ankka local up` renders for a project (feature 066, research R1), as a
 * value: the plan builds it, `json` writes it to `.ankka/local/compose.json`, and `docker compose`
 * reads it, JSON being YAML. Field names are compose's own; an empty field is left out, so the file
 * says only what was decided.
 */
final case class Compose(
    name: String,
    services: ListMap[String, Compose.Service],
    volumes: ListMap[String, Compose.Volume] = ListMap.empty
)

object Compose:

  final case class Volume(name: Option[String] = None)

  final case class Healthcheck(
      test: Vector[String],
      interval: String = "2s",
      timeout: String = "5s",
      retries: Int = 60,
      startPeriod: Option[String] = None
  )

  final case class Dependency(condition: String)

  final case class Service(
      image: String,
      command: Vector[String] = Vector.empty,
      entrypoint: Vector[String] = Vector.empty,
      environment: ListMap[String, String] = ListMap.empty,
      ports: Vector[String] = Vector.empty,
      volumes: Vector[String] = Vector.empty,
      dependsOn: ListMap[String, Dependency] = ListMap.empty,
      healthcheck: Option[Healthcheck] = None,
      extraHosts: Vector[String] = Vector.empty,
      networkMode: Option[String] = None,
      memLimit: Option[String] = None,
      restart: Option[String] = None,
      labels: ListMap[String, String] = ListMap.empty
  )

  /** What a service's container is given to reach the host: Docker's own name for it. */
  val HostGateway: String = "host.docker.internal:host-gateway"

  /** A `depends_on` condition that waits for a run-once service to end well. */
  val CompletedSuccessfully: String = "service_completed_successfully"
  val Healthy: String               = "service_healthy"

  given codec: JsonValueCodec[Compose] = JsonCodecMaker.make(
    CodecMakerConfig.withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )

  /**
   * The document as the file holds it: two-space indented, a newline at the end. Compose
   * interpolates `$VAR` in every string it reads, and nothing here means one, so each `$` a value
   * holds (a local value, a descriptor's literal, a companion's command) is written as `$$`, which
   * compose reads back as the `$` it was.
   */
  def json(compose: Compose): String =
    writeToString(escaped(compose), WriterConfig.withIndentionStep(2)) + "\n"

  private def escaped(compose: Compose): Compose =
    def e(text: String): String = text.replace("$", "$$")
    compose.copy(services = compose.services.map { (name, s) =>
      name -> s.copy(
        command = s.command.map(e),
        entrypoint = s.entrypoint.map(e),
        environment = s.environment.map((k, v) => k -> e(v)),
        healthcheck = s.healthcheck.map(h => h.copy(test = h.test.map(e))),
        labels = s.labels.map((k, v) => k -> e(v))
      )
    })

  /** A port published on `address` of the host at `host`, for `container` in the container. */
  def publish(address: String, host: Int, container: Int): String = s"$address:$host:$container"
