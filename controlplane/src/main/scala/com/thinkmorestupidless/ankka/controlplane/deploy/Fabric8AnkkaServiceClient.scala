package com.thinkmorestupidless.ankka.controlplane.deploy

import io.fabric8.kubernetes.api.model.{NamespaceBuilder, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.informers.{ResourceEventHandler, SharedIndexInformer}
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, AnkkaService, AnkkaServiceSpec}
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The real thing.
 *
 * The only file in the control plane that talks to Kubernetes, and it only ever names one resource
 * kind.
 */
final class Fabric8AnkkaServiceClient(
    client: KubernetesClient,
    namespacePrefix: String,
    resyncMillis: Long = 60000L
) extends AnkkaServiceClient:

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.k8s")

  /** Distinct from the operator's `ankka-operator`, so each reverts only its own fields. */
  private val FieldManager = "ankka-controlplane"

  private var informer: Option[SharedIndexInformer[AnkkaService]] = None

  private def watched(namespace: String): Boolean =
    namespace != null && namespace.startsWith(s"$namespacePrefix-")

  def ensureNamespace(namespace: String): Unit =
    if client.namespaces().withName(namespace).get() == null then
      val ns = new NamespaceBuilder()
        .withMetadata(
          new ObjectMetaBuilder()
            .withName(namespace)
            .withLabels(java.util.Map.of("app.kubernetes.io/managed-by", "ankka"))
            .build()
        )
        .build()
      val _ = client.resource(ns).fieldManager(FieldManager).forceConflicts().serverSideApply()
      log.debug("created namespace {}", namespace)

  def put(namespace: String, name: String, spec: AnkkaServiceSpec): Unit =
    val resources = client.resources(classOf[AnkkaService]).inNamespace(namespace).withName(name)
    val existing  = Option(resources.get())

    // Idempotence is checked here rather than left to the API server: server-side apply on an
    // unchanged object is still a request, and a steady-state service performing one write per
    // sweep per service is a load nobody asked for.
    if existing.exists(r => Option(r.getSpec).contains(spec)) then
      log.debug("spec for {}/{} unchanged; no write", namespace, name)
    else
      val resource = AnkkaService(namespace, name, spec)
      // forceConflicts because the control plane's record is authoritative: an out-of-band
      // `kubectl edit` of the spec is reverted, not merged.
      val _ = client
        .resource(resource)
        .fieldManager(FieldManager)
        .forceConflicts()
        .serverSideApply()
      log.debug("projected {}/{} at generation {}", namespace, name, spec.generation)

  def delete(namespace: String, name: String): Unit =
    val _ = client.resources(classOf[AnkkaService]).inNamespace(namespace).withName(name).delete()
    log.debug("deleted resource {}/{}", namespace, name)

  def list(): Vector[AnkkaServiceResource] =
    client
      .resources(classOf[AnkkaService])
      .inAnyNamespace()
      .list()
      .getItems
      .asScala
      .toVector
      .filter(r => watched(r.getMetadata.getNamespace))
      .map(toResource)

  def watch(onChange: AnkkaServiceResource => Unit): AutoCloseable =
    val handler = new ResourceEventHandler[AnkkaService]:
      private def deliver(resource: AnkkaService): Unit =
        if watched(resource.getMetadata.getNamespace) then
          try onChange(toResource(resource))
          catch
            case NonFatal(failure) =>
              // A handler that throws must not kill the informer, or the control plane
              // stops seeing status for every service because one was malformed.
              log.warn("status handler failed; continuing to watch", failure)

      def onAdd(resource: AnkkaService): Unit                      = deliver(resource)
      def onUpdate(old: AnkkaService, updated: AnkkaService): Unit = deliver(updated)
      def onDelete(resource: AnkkaService, unknown: Boolean): Unit = deliver(resource)

    val started = client
      .resources(classOf[AnkkaService])
      .inAnyNamespace()
      .inform(handler, resyncMillis)

    informer = Some(started)
    () => started.close()

  /**
   * Whether the informer is running and has a populated cache.
   *
   * `hasSynced` is the honest question: an informer that is running but has never listed knows
   * nothing, and treating that as connected would report every service as confirmed on the strength
   * of an empty cache.
   */
  def connected: Boolean = informer.exists(i => i.isRunning && i.hasSynced)

  override def close(): Unit =
    informer.foreach(_.close())
    informer = None

  private def toResource(resource: AnkkaService): AnkkaServiceResource =
    AnkkaServiceResource(
      namespace = resource.getMetadata.getNamespace,
      name = resource.getMetadata.getName,
      spec = Option(resource.getSpec).getOrElse(AnkkaServiceSpec()),
      status = Option(resource.getStatus),
      uid = Option(resource.getMetadata.getUid).getOrElse("")
    )

object Fabric8AnkkaServiceClient:

  /** Builds a client from the ambient credentials: service account, `KUBECONFIG`, `~/.kube`. */
  def apply(namespacePrefix: String): Fabric8AnkkaServiceClient =
    val client = new KubernetesClientBuilder()
      .withKubernetesSerialization(AnkkaSerialization())
      .build()
    new Fabric8AnkkaServiceClient(client, namespacePrefix)
