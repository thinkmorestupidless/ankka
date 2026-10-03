package com.thinkmorestupidless.ankka.controlplane.deploy

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{
  InstanceStatus,
  InstanceTopology,
  InstanceTopologyDocument
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, RotatingTls}
import io.fabric8.kubernetes.client.KubernetesClient

import java.net.ConnectException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.file.{Path, Paths}
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** What a service's instances say their topology is, read as the control plane. */
trait TopologyReader:
  /**
   * Every running instance of the service, each with its document when it answered. Never throws
   * for one instance: an instance that could not be read is reported as such beside the others.
   */
  def read(
      projectId: String,
      service: String
  ): Vector[(InstanceTopology, Option[InstanceTopologyDocument])]

/**
 * A deployed service's topology, read from each of its pods over the observe port (contract
 * `observe-port`): mutual TLS with the control plane's own service certificate, expecting exactly
 * the identity of the service asked for — the pod is reached by IP, which no certificate names, so
 * the identity is the whole of the check. The pods are read concurrently, each given a deadline, so
 * a service with many instances answers in the time of one, and one that never answers costs the
 * others nothing.
 *
 * Nothing is stored, as with logs: this is what the instances say at the moment of asking.
 *
 * @param pods
 *   the running pods of a service, by name and address.
 * @param fetch
 *   one pod's answer; may block, and is cut off at the deadline.
 */
final class InstanceTopologies(
    pods: (String, String) => Vector[(String, String)],
    fetch: (String, String, String, String) => (InstanceTopology, Option[InstanceTopologyDocument]),
    perInstance: FiniteDuration = 2.seconds
) extends TopologyReader:

  private given ExecutionContext = AnkkaExecutors.virtual

  def read(
      projectId: String,
      service: String
  ): Vector[(InstanceTopology, Option[InstanceTopologyDocument])] =
    val running = pods(projectId, service)
    // Every read starts now; then each is waited for in turn, so the waits overlap and the whole
    // takes about one deadline, however many instances there are.
    val started  = running.map((pod, ip) => pod -> Future(fetch(projectId, service, pod, ip)))
    val deadline = System.nanoTime() + perInstance.toNanos + 500.millis.toNanos
    started.map { (pod, read) =>
      val left = math.max(0L, deadline - System.nanoTime())
      try Await.result(read, left.nanos)
      catch
        case _: TimeoutException =>
          (
            InstanceTopology(
              pod,
              InstanceStatus.Unreachable,
              Some(s"no answer within ${perInstance.toSeconds}s")
            ),
            None
          )
        case e: Exception =>
          (
            InstanceTopology(
              pod,
              InstanceStatus.Failed,
              Some(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
            ),
            None
          )
    }

object InstanceTopologies:

  /** The port every workload's runtime serves its topology on, by contract. */
  val ObservePort: Int = 7628

  /** Where the control plane's own service certificate is mounted, as every workload's is. */
  val ServiceDirectoryKey: String = "ankka.tls.service-directory"

  /** The reader that ships: pods from the API server, each read over the observe port. */
  def apply(namespacePrefix: String): InstanceTopologies =
    // Through the cluster overlay, as the runtime loads its own: the certificate's directory is the
    // Kubernetes overlay's to set, and the plain loader never sees an overlay.
    val config = com.thinkmorestupidless.ankka.runtime.ClusterConfig.load()
    val directory =
      Option
        .when(config.hasPath(ServiceDirectoryKey))(config.getString(ServiceDirectoryKey))
        .filter(_.nonEmpty)
        .map(Paths.get(_))
    val client = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()
    new InstanceTopologies(runningPods(client, namespacePrefix), ObservePortFetch(directory).apply)

  /** Running pods of one service, with their addresses, newest first. */
  def runningPods(client: KubernetesClient, namespacePrefix: String)(
      projectId: String,
      service: String
  ): Vector[(String, String)] =
    Try {
      client
        .pods()
        .inNamespace(s"$namespacePrefix-$projectId")
        .withLabel("app.kubernetes.io/name", service)
        .list()
        .getItems
        .asScala
        .toVector
        .filter(pod => Option(pod.getStatus).exists(s => s.getPhase == "Running"))
        .sortBy(pod => Option(pod.getStatus).flatMap(s => Option(s.getStartTime)).getOrElse(""))
        .reverse
        .flatMap(pod => Option(pod.getStatus.getPodIP).map(ip => pod.getMetadata.getName -> ip))
    }.getOrElse(Vector.empty)

  /**
   * One pod's topology over its observe port. Answered and decoded is `ok`; a refused connection is
   * `unsupported` (a runtime from before the port); a deadline is `unreachable`; anything else,
   * including a certificate the reader does not expect or a body that is not a topology, is
   * `failed` with the problem.
   */
  final class ObservePortFetch(
      serviceDirectory: Option[Path],
      reloadInterval: FiniteDuration = 1.minute,
      port: Int = ObservePort,
      perInstance: FiniteDuration = 2.seconds
  ):
    // One identity per service read, built from the control plane's own certificate: a renewal
    // reaches the next connection, and a service's pods all present the same identity.
    private val expecting = new ConcurrentHashMap[String, RotatingTls]()

    private def tlsFor(projectId: String, service: String): RotatingTls =
      val uri = s"ankka://$projectId/$service"
      expecting.computeIfAbsent(
        uri,
        _ =>
          RotatingTls(
            serviceDirectory.getOrElse(
              throw IllegalStateException(
                "the control plane has no service certificate to read a topology with"
              )
            ),
            reloadInterval,
            RotatingTls.Peers.Exactly(uri)
          )
      )

    def apply(
        projectId: String,
        service: String,
        pod: String,
        ip: String
    ): (InstanceTopology, Option[InstanceTopologyDocument]) =
      def failed(problem: String) =
        (InstanceTopology(pod, InstanceStatus.Failed, Some(problem)), None)
      try
        val http = HttpClient
          .newBuilder()
          .sslContext(tlsFor(projectId, service).sslContext)
          .connectTimeout(Duration.ofMillis(perInstance.toMillis))
          .build()
        val response = http.send(
          HttpRequest
            .newBuilder(URI.create(s"https://$ip:$port/observability/topology"))
            .timeout(Duration.ofMillis(perInstance.toMillis))
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        if response.statusCode() != 200 then
          failed(s"the instance answered ${response.statusCode()} for its topology")
        else
          Try(readFromString[InstanceTopologyDocument](response.body())).toEither match
            case Right(document) =>
              (
                InstanceTopology(
                  pod,
                  InstanceStatus.Ok,
                  None,
                  Some(document.service.runtime),
                  Some(Instant.now().toString)
                ),
                Some(document)
              )
            case Left(e) => failed(s"the instance's answer is not a topology: ${e.getMessage}")
      catch
        case _: ConnectException =>
          (
            InstanceTopology(
              pod,
              InstanceStatus.Unsupported,
              Some("this instance's runtime serves no topology")
            ),
            None
          )
        case _: HttpTimeoutException =>
          (
            InstanceTopology(
              pod,
              InstanceStatus.Unreachable,
              Some(s"no answer within ${perInstance.toSeconds}s")
            ),
            None
          )
        case e: Exception =>
          failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
