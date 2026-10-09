package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.{ResourceEventHandler, SharedIndexInformer}
import com.thinkmorestupidless.ankka.crd.{AnkkaProject, AnkkaService, CloudResource}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * What the operator does for one service. Supplied separately so the loop stays about scheduling.
 */
trait Reconciler:
  def reconcile(ref: ServiceRef): Unit

  /**
   * How a reconciler asks for another pass later, for a wait no event will end: a cloud request
   * nobody acknowledges (feature 044) is reported when the bound passes, not at the next resync.
   * The operator hands each reconciler its own queue's `enqueueAfter`; by default nothing asks.
   */
  def requeueWith(@scala.annotation.unused later: (ServiceRef, FiniteDuration) => Unit): Unit = ()

/**
 * Watches, and schedules work.
 *
 * Two informers, and the second is the one that is easy to leave out. Watching `AnkkaService`
 * catches every change an operator makes. Watching the **Deployments the operator owns** is what
 * catches a change nobody announced — someone deleting a workload by hand — within a second, rather
 * than at the next resync. Without it, self-healing is only as fast as the sweep.
 *
 * The informers also resync on a timer, which is the backstop for anything a watch missed during a
 * disconnect. Level-triggered throughout: an event says only "look at this resource again", never
 * what changed, so a lost event costs latency and never correctness.
 */
final class Operator(
    client: KubernetesClient,
    settings: Settings,
    reconciler: Reconciler,
    projects: Option[Reconciler] = None
) extends AutoCloseable:

  private val log: Logger = LoggerFactory.getLogger("ankka.operator")

  private val queue = new WorkQueue(settings, reconciler.reconcile)
  reconciler.requeueWith(queue.enqueueAfter)

  /**
   * A project's topics (feature 027), on a queue of their own so a slow broker never delays a
   * service's pass. `ProjectReconciler` unless a test supplies another.
   */
  private val projectReconciler: Reconciler =
    projects.getOrElse(ProjectReconciler(client, settings))
  private val projectQueue = new WorkQueue(settings, projectReconciler.reconcile)

  private var informers: Vector[SharedIndexInformer[?]] = Vector.empty

  /** Only namespaces this operator is responsible for. */
  private def watched(namespace: String): Boolean =
    namespace != null && namespace.startsWith(s"${settings.namespacePrefix}-")

  private def refOf(resource: AnkkaService): Option[ServiceRef] =
    Option(resource.getMetadata)
      .filter(m => watched(m.getNamespace))
      .map(m => ServiceRef(m.getNamespace, m.getName))

  /**
   * Which service a Deployment belongs to, from its labels.
   *
   * By label rather than by owner reference because a Deployment that lost its owner reference —
   * the case worth noticing — still carries them.
   */
  private def refOf(deployment: Deployment): Option[ServiceRef] =
    for
      meta      <- Option(deployment.getMetadata)
      namespace <- Option(meta.getNamespace) if watched(namespace)
      labels    <- Option(meta.getLabels).map(_.asScala.toMap) if Labels.ownedByAnkka(labels)
      service   <- labels.get(Labels.ServiceKey)
    yield ServiceRef(namespace, service)

  /** Wakes whichever resource a cloud request belongs to, on that resource's own queue. */
  private def wake(request: CloudResource): Unit =
    Operator.ownerOf(request).filter((_, ref) => watched(ref.namespace)).foreach {
      case (Operator.Owner.Service, ref) => queue.enqueue(ref)
      case (Operator.Owner.Project, ref) => projectQueue.enqueue(ref)
    }

  private def handler[T](toRef: T => Option[ServiceRef])(using
      target: WorkQueue = queue
  ): ResourceEventHandler[T] =
    new ResourceEventHandler[T]:
      def onAdd(obj: T): Unit                = toRef(obj).foreach(target.enqueue)
      def onUpdate(old: T, updated: T): Unit = toRef(updated).foreach(target.enqueue)
      def onDelete(obj: T, deletedFinalStateUnknown: Boolean): Unit =
        toRef(obj).foreach(target.enqueue)

  def start(): Unit =
    val resyncMillis = settings.resyncInterval.toMillis

    val services = client
      .resources(classOf[AnkkaService])
      .inAnyNamespace()
      .inform(handler[AnkkaService](refOf), resyncMillis)

    val deployments = client
      .apps()
      .deployments()
      .inAnyNamespace()
      .withLabel(Labels.ManagedByKey, Labels.ManagedByAnkka)
      .inform(handler[Deployment](refOf), resyncMillis)

    // A cluster whose API server has no AnkkaProject type yet (an installation from before the
    // type, mid-upgrade) still has its services reconciled; its projects' topics wait for the type.
    val projectInformer =
      try
        Some(
          client
            .resources(classOf[AnkkaProject])
            .inAnyNamespace()
            .inform(
              handler[AnkkaProject](p =>
                Option(p.getMetadata)
                  .filter(m => watched(m.getNamespace))
                  .map { m =>
                    // A project's declared brokers (feature 037) are mounted on every service of
                    // the project: each is reconciled again when the project changes.
                    client
                      .resources(classOf[AnkkaService])
                      .inNamespace(m.getNamespace)
                      .list()
                      .getItems
                      .asScala
                      .foreach(s =>
                        queue.enqueue(ServiceRef(m.getNamespace, s.getMetadata.getName))
                      )
                    ServiceRef(m.getNamespace, m.getName)
                  }
              )(using projectQueue),
              resyncMillis
            )
        )
      catch
        case scala.util.control.NonFatal(e) =>
          log.warn(
            "no AnkkaProject type in this cluster; projects' topics are not made: {}",
            e.getMessage
          )
          None

    // A cloud request's answer (feature 044) wakes the resource that asked: a provider that starts
    // late is heard within a second, not at the next resync. A cluster without the type runs on.
    val cloudInformer =
      try
        Some(
          client
            .resources(classOf[CloudResource])
            .inAnyNamespace()
            .inform(
              new ResourceEventHandler[CloudResource]:
                def onAdd(r: CloudResource): Unit                        = wake(r)
                def onUpdate(old: CloudResource, r: CloudResource): Unit = wake(r)
                def onDelete(r: CloudResource, unknown: Boolean): Unit   = wake(r)
              ,
              resyncMillis
            )
        )
      catch
        case scala.util.control.NonFatal(e) =>
          log.warn(
            "no CloudResource type in this cluster; no cloud request is watched: {}",
            e.getMessage
          )
          None

    informers = Vector(services, deployments) ++ projectInformer ++ cloudInformer
    queue.start()
    projectQueue.start()

    log.info(
      "operator watching namespaces '{}-*' (resync every {})",
      settings.namespacePrefix,
      settings.resyncInterval
    )

  /** Waits until the process is interrupted. The informers do the work on their own threads. */
  def awaitTermination(): Unit =
    try Thread.currentThread().join()
    catch case _: InterruptedException => Thread.currentThread().interrupt()

  def close(): Unit =
    informers.foreach(_.close())
    informers = Vector.empty
    queue.stop()
    projectQueue.stop()

  /** Test seam. */
  private[operator] def workQueue: WorkQueue = queue

object Operator:

  /** Which kind of resource a cloud request was made for. */
  enum Owner:
    case Service, Project

  /**
   * The resource a cloud request serves, by its controlling owner reference: an `AnkkaService` is
   * reconciled on the service queue and an `AnkkaProject` on the project queue. A request with no
   * such owner belongs to nothing the operator reconciles.
   */
  def ownerOf(request: CloudResource): Option[(Owner, ServiceRef)] =
    for
      meta <- Option(request.getMetadata)
      ns   <- Option(meta.getNamespace)
      owner <- Option(meta.getOwnerReferences).toVector
        .flatMap(_.asScala)
        .find(o => Option(o.getController).exists(_.booleanValue))
      kind <- owner.getKind match
        case "AnkkaService" => Some(Owner.Service)
        case "AnkkaProject" => Some(Owner.Project)
        case _              => None
    yield kind -> ServiceRef(ns, owner.getName)
