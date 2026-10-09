package com.thinkmorestupidless.ankka.operator.cloud

import com.thinkmorestupidless.ankka.crd.CloudResource
import io.fabric8.kubernetes.api.model.{ObjectMetaBuilder, SecretBuilder}
import io.fabric8.kubernetes.client.dsl.base.{PatchContext, PatchType}
import io.fabric8.kubernetes.client.informers.{ResourceEventHandler, SharedIndexInformer}
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import org.slf4j.LoggerFactory

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The scripted cloud provider as a controller (feature 044): the fulfilment of
 * `ScriptedFulfilment`, run against a cluster through whatever client it is handed. In the k3s
 * suites that client is minted from the `ankka-cloud-provider` ServiceAccount under the shipped
 * ClusterRole, so everything it does is something every provider is allowed to do, and the API
 * server refuses anything else.
 *
 * Three writes and nothing more: a request's status through the subresource, a Secret's `create`,
 * and a Secret's `patch`. It reads requests, never Secrets.
 */
final class ScriptedCloudProvider(client: KubernetesClient, val fulfilment: ScriptedFulfilment):

  private val log = LoggerFactory.getLogger(classOf[ScriptedCloudProvider])

  /** One thread: a request is answered one at a time, as `ScriptedFulfilment` expects. */
  private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  @volatile private var informer: Option[SharedIndexInformer[CloudResource]] = None

  private val secrets = new SecretWrites:
    def create(namespace: String, name: String, entries: Map[String, String]) =
      try
        client
          .secrets()
          .inNamespace(namespace)
          .resource(
            new SecretBuilder()
              .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).build())
              .withStringData(entries.asJava)
              .build()
          )
          .create(): Unit
        SecretWrites.Outcome.Created
      catch case e: KubernetesClientException if e.getCode == 409 => SecretWrites.Outcome.Exists

    def patch(namespace: String, name: String, entries: Map[String, String]): Unit =
      client
        .secrets()
        .inNamespace(namespace)
        .withName(name)
        .patch(
          PatchContext.of(PatchType.JSON_MERGE),
          new SecretBuilder().withStringData(entries.asJava).build()
        ): Unit

  def start(): Unit =
    val handler = new ResourceEventHandler[CloudResource]:
      def onAdd(r: CloudResource): Unit                        = submit(r)
      def onUpdate(old: CloudResource, r: CloudResource): Unit = submit(r)
      def onDelete(r: CloudResource, unknown: Boolean): Unit   = ()
    informer = Some(
      client.resources(classOf[CloudResource]).inAnyNamespace().inform(handler, 2000L)
    )
    worker.scheduleWithFixedDelay(() => fulfilment.endDue(): Unit, 1, 1, TimeUnit.SECONDS): Unit
    log.info("scripted cloud provider '{}' answering", fulfilment.provider)

  def stop(): Unit =
    informer.foreach(_.close())
    informer = None
    worker.shutdownNow(): Unit

  private def submit(seen: CloudResource): Unit =
    val _ = worker.submit((() => answer(seen)): Runnable)

  private def answer(seen: CloudResource): Unit =
    val meta       = seen.getMetadata
    val generation = Option(meta.getGeneration).map(_.longValue).getOrElse(0L)
    val previous   = Option(seen.getStatus)
    val mine       = Option(seen.getSpec).exists(_.provider == fulfilment.provider)
    val answered   = previous.flatMap(_.observedGeneration).contains(generation)
    if mine && !answered then
      try
        val status = fulfilment.fulfil(
          meta.getNamespace,
          meta.getName,
          seen.getSpec,
          generation,
          previous,
          secrets
        )
        client
          .resources(classOf[CloudResource])
          .inNamespace(meta.getNamespace)
          .withName(meta.getName)
          .editStatus { current =>
            current.setStatus(status)
            current
          }: Unit
      catch
        case NonFatal(e) =>
          log.warn("could not answer {}/{}: {}", meta.getNamespace, meta.getName, e.getMessage)
