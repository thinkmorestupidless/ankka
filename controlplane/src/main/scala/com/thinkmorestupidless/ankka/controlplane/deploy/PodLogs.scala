package com.thinkmorestupidless.ankka.controlplane.deploy

import io.fabric8.kubernetes.client.KubernetesClient

import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * A deployed service's output, read from the pods running it.
 *
 * Nothing is stored. This reads what Kubernetes holds for a pod at the moment of asking and hands
 * it over — so a service that has restarted many times has lost everything but its last two
 * containers, and that is Kubernetes' behaviour reported rather than papered over. A log store is a
 * feature with a schema, a retention policy and a bill; this is not it.
 */
final class PodLogs(client: KubernetesClient, namespacePrefix: String):

  /** Pods belonging to one service, newest first so the current instance leads. */
  def instances(projectId: String, service: String): Vector[String] =
    Try {
      client
        .pods()
        .inNamespace(namespaceFor(projectId))
        .withLabel("app.kubernetes.io/name", service)
        .list()
        .getItems
        .asScala
        .toVector
        .sortBy(pod => Option(pod.getStatus).flatMap(s => Option(s.getStartTime)).getOrElse(""))
        .reverse
        .map(_.getMetadata.getName)
    }.getOrElse(Vector.empty)

  /**
   * Recent output from one instance.
   *
   * `previous` reads the container that died rather than the one that replaced it, which is usually
   * where the answer is after a crash — and is the reason the flag exists at all.
   */
  def read(
      projectId: String,
      instance: String,
      tail: Option[Int],
      sinceSeconds: Option[Int],
      previous: Boolean
  ): Either[String, String] =
    Try {
      val pod = client
        .pods()
        .inNamespace(namespaceFor(projectId))
        .withName(instance)

      // Order matters: fabric8's DSL narrows the type after each of these, and only
      // sinceSeconds-then-tailingLines type-checks. Reversed, `tailingLines` returns something
      // with no `sinceSeconds` on it.
      val loggable = if previous then pod.terminated() else pod
      val log = (sinceSeconds, tail) match
        case (Some(seconds), Some(lines)) =>
          loggable.sinceSeconds(seconds).tailingLines(lines).getLog(true)
        case (Some(seconds), None) => loggable.sinceSeconds(seconds).getLog(true)
        case (None, Some(lines))   => loggable.tailingLines(lines).getLog(true)
        case (None, None)          => loggable.getLog(true)
      log
    }.toEither.left.map {
      // A pod with no previous container is the common case, not a fault: it means the service
      // has not crashed. Say that rather than surfacing a client exception.
      case failure if previous && isNotFound(failure) =>
        s"$instance has no previous container — it has not restarted"
      case failure if isNotFound(failure) =>
        s"$instance is not running"
      case failure =>
        Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)
    }

  private def isNotFound(failure: Throwable): Boolean =
    Option(failure.getMessage).exists(m => m.contains("404") || m.toLowerCase.contains("not found"))

  private def namespaceFor(projectId: String): String = s"$namespacePrefix-$projectId"

object PodLogs:
  def apply(namespacePrefix: String): PodLogs =
    new PodLogs(new io.fabric8.kubernetes.client.KubernetesClientBuilder().build(), namespacePrefix)
