package com.thinkmorestupidless.ankka.controlplane

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.k3s.K3sContainer
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{
  RequestChecksumCalculation,
  ResponseChecksumValidation
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

import java.net.URI
import scala.jdk.CollectionConverters.*

/**
 * What a k3s suite does to a deployed service's pods (features 034 and 039): find them, run a shell
 * command in the service's own container with its environment, read that environment, and make a
 * signed S3 request from inside it with exactly the variables the pod was given. Shared by the
 * suites of `features/object-storage/` and `features/object-storage-gcs/`, so a bucket on either
 * store is reached the one way.
 */
final class ServicePods(k3s: K3sContainer, k8s: KubernetesClient, namespace: String => String):

  def pods(project: String, name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(namespace(project))
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  /** A shell command run inside the service's own container, with its environment. */
  def inPod(project: String, name: String, script: String): (Int, String) =
    val pod = pods(project, name)
      .find(ServicePods.running)
      .getOrElse(throw AssertionError(s"$name has no running pod"))
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      namespace(project),
      pod.getMetadata.getName,
      "-c",
      name,
      "--",
      "sh",
      "-c",
      script
    )
    (r.getExitCode, r.getStdout + r.getStderr)

  def environment(project: String, name: String): Map[String, String] =
    inPod(project, name, "env")._2.linesIterator
      .flatMap(l => l.split("=", 2) match { case Array(k, v) => Some(k -> v); case _ => None })
      .toMap

  /** A signed request from inside the pod: its status, and the body. */
  def s3(
      project: String,
      name: String,
      method: String,
      path: String,
      upload: Option[String] = None
  ): (Int, String) =
    // The payload's hash, sent as its own header: a curl before 8 signs without it, and the store
    // refuses a request that does not say it (`Missing X-Amz-Content-Sha256`).
    val body = upload.fold("printf '' > /tmp/upload && ")(content =>
      s"printf '%s' '$content' > /tmp/upload && "
    ) + "hash=$(sha256sum /tmp/upload | cut -d' ' -f1) && "
    val send = upload.fold("")(_ => "-T /tmp/upload ")
    val (_, out) = inPod(
      project,
      name,
      body +
        s"""curl -s -o /tmp/answer -w '%{http_code}' -X $method $send-H "x-amz-content-sha256: $$hash" --aws-sigv4 "aws:amz:$$ANKKA_S3_REGION:s3" """ +
        s"""--user "$$ANKKA_S3_ACCESS_KEY:$$ANKKA_S3_SECRET_KEY" "$$ANKKA_S3_ENDPOINT$path"; echo; cat /tmp/answer"""
    )
    val lines = out.linesIterator.toVector
    (lines.headOption.flatMap(_.trim.toIntOption).getOrElse(0), lines.drop(1).mkString("\n"))

  /** The storage access key one instance of a service was started with. */
  def accessKeyIn(project: String, name: String, pod: Pod): Option[String] =
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      namespace(project),
      pod.getMetadata.getName,
      "-c",
      name,
      "--",
      "printenv",
      "ANKKA_S3_ACCESS_KEY"
    )
    Option.when(r.getExitCode == 0)(r.getStdout.trim).filter(_.nonEmpty)

object ServicePods:

  def running(pod: Pod): Boolean =
    Option(pod.getStatus)
      .flatMap(s => Option(s.getContainerStatuses))
      .exists(_.asScala.exists(c => Option(c.getState).exists(_.getRunning != null)))

  /**
   * An S3 client on the host at `endpoint`, holding `key`, with the settings the object storage
   * page gives every client: path-style, and checksums only when an operation needs one.
   */
  def s3Client(endpoint: String, region: String, key: (String, String)): S3Client =
    S3Client
      .builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.of(region))
      .forcePathStyle(true)
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(key._1, key._2))
      )
      .build()
