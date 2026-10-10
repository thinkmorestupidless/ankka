package com.thinkmorestupidless.ankka.controlplane.deploy

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.controlplane.api.TopicDivergence
import com.thinkmorestupidless.ankka.runtime.RotatingTls

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Path
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.util.Try

/**
 * What a restore cannot take back (feature 041), as a service's own instances say it: the topics it
 * publishes to and reads, with what each holds past a moment, asked over the observe port as the
 * topology is. The broker is asked by the service because only the service holds a credential for
 * its topics; the control plane holds none.
 */
trait DivergenceReader:
  /** What the first instance to answer says, or why none did. */
  def since(
      projectId: String,
      service: String,
      at: Instant
  ): Either[String, Vector[TopicDivergence]]

object DivergenceReader:

  /** Merges what several services said: one entry per topic and group, naming who touches it. */
  def merge(answers: Vector[(String, Vector[TopicDivergence])]): Vector[TopicDivergence] =
    answers
      .flatMap((service, entries) => entries.map(e => e.copy(services = Vector(service))))
      .groupBy(e => (e.topic, e.group))
      .toVector
      .map { case ((topic, group), same) =>
        TopicDivergence(
          topic,
          group,
          same.map(_.after).max,
          same.flatMap(_.read).maxOption,
          same.flatMap(_.services).distinct.sorted
        )
      }
      .sortBy(e => (e.topic, e.group.getOrElse("")))

  /** The entry the runtime writes (`Divergence.json`). */
  private final case class Entry(
      topic: String,
      group: Option[String],
      after: Long,
      read: Option[Long]
  )
  private given JsonValueCodec[Vector[Entry]] = JsonCodecMaker.make

  /** The reader that ships: each running pod over the observe port, until one answers. */
  def observePort(namespacePrefix: String): DivergenceReader =
    val config = com.thinkmorestupidless.ankka.runtime.ClusterConfig.load()
    val directory =
      Option
        .when(config.hasPath(InstanceTopologies.ServiceDirectoryKey))(
          config.getString(InstanceTopologies.ServiceDirectoryKey)
        )
        .filter(_.nonEmpty)
        .map(java.nio.file.Paths.get(_))
    lazy val client = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()
    ObservePort(
      (project, service) =>
        InstanceTopologies.runningPods(client, namespacePrefix)(project, service),
      directory
    )

  final class ObservePort(
      pods: (String, String) => Vector[(String, String)],
      serviceDirectory: Option[Path],
      port: Int = InstanceTopologies.ObservePort,
      perInstance: FiniteDuration = 30.seconds
  ) extends DivergenceReader:

    private val expecting = new ConcurrentHashMap[String, RotatingTls]()

    private def tlsFor(uri: String, directory: Path) =
      expecting.computeIfAbsent(
        uri,
        _ => RotatingTls(directory, 1.minute, RotatingTls.Peers.Exactly(uri))
      )

    def since(
        projectId: String,
        service: String,
        at: Instant
    ): Either[String, Vector[TopicDivergence]] =
      serviceDirectory match
        case None => Left("the control plane has no service certificate to ask with")
        case Some(directory) =>
          val running = pods(projectId, service)
          if running.isEmpty then Left(s"$service has no running instance")
          else
            val tls = tlsFor(s"ankka://$projectId/$service", directory)
            val answers = running.iterator.map { (pod, ip) =>
              Try {
                val http = HttpClient
                  .newBuilder()
                  .sslContext(tls.sslContext)
                  .connectTimeout(Duration.ofSeconds(5))
                  .build()
                val response = http.send(
                  HttpRequest
                    .newBuilder(URI.create(s"https://$ip:$port/observability/divergence?since=$at"))
                    .timeout(Duration.ofMillis(perInstance.toMillis))
                    .GET()
                    .build(),
                  HttpResponse.BodyHandlers.ofString()
                )
                if response.statusCode() != 200 then Left(s"$pod answered ${response.statusCode()}")
                else
                  Right(
                    readFromString[Vector[Entry]](response.body())
                      .map(e => TopicDivergence(e.topic, e.group, e.after, e.read))
                  )
              }.toEither.left
                .map(e => s"$pod: ${Option(e.getMessage).getOrElse(e.toString)}")
                .flatten
            }
            val all = answers.toVector
            all
              .collectFirst { case Right(entries) => Right(entries) }
              .getOrElse(Left(all.collect { case Left(why) => why }.mkString("; ")))
