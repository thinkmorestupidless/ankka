package nakka.operator

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.{ResourceEventHandler, SharedIndexInformer}
import nakka.crd.NakkaService
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters.*

/**
 * What the operator does for one service. Supplied separately so the loop stays about scheduling.
 */
trait Reconciler:
  def reconcile(ref: ServiceRef): Unit

/**
 * Watches, and schedules work.
 *
 * Two informers, and the second is the one that is easy to leave out. Watching `NakkaService`
 * catches every change an operator makes. Watching the **Deployments the operator owns** is what
 * catches a change nobody announced — someone deleting a workload by hand — within a second, rather
 * than at the next resync. Without it, self-healing is only as fast as the sweep.
 *
 * The informers also resync on a timer, which is the backstop for anything a watch missed during a
 * disconnect. Level-triggered throughout: an event says only "look at this resource again", never
 * what changed, so a lost event costs latency and never correctness.
 */
final class Operator(client: KubernetesClient, settings: Settings, reconciler: Reconciler)
    extends AutoCloseable:

  private val log: Logger = LoggerFactory.getLogger("nakka.operator")

  private val queue = new WorkQueue(settings, reconciler.reconcile)

  private var informers: Vector[SharedIndexInformer[?]] = Vector.empty

  /** Only namespaces this operator is responsible for. */
  private def watched(namespace: String): Boolean =
    namespace != null && namespace.startsWith(s"${settings.namespacePrefix}-")

  private def refOf(resource: NakkaService): Option[ServiceRef] =
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
      labels    <- Option(meta.getLabels).map(_.asScala.toMap) if Labels.ownedByNakka(labels)
      service   <- labels.get(Labels.ServiceKey)
    yield ServiceRef(namespace, service)

  private def handler[T](toRef: T => Option[ServiceRef]): ResourceEventHandler[T] =
    new ResourceEventHandler[T]:
      def onAdd(obj: T): Unit                = toRef(obj).foreach(queue.enqueue)
      def onUpdate(old: T, updated: T): Unit = toRef(updated).foreach(queue.enqueue)
      def onDelete(obj: T, deletedFinalStateUnknown: Boolean): Unit =
        toRef(obj).foreach(queue.enqueue)

  def start(): Unit =
    val resyncMillis = settings.resyncInterval.toMillis

    val services = client
      .resources(classOf[NakkaService])
      .inAnyNamespace()
      .inform(handler[NakkaService](refOf), resyncMillis)

    val deployments = client
      .apps()
      .deployments()
      .inAnyNamespace()
      .withLabel(Labels.ManagedByKey, Labels.ManagedByNakka)
      .inform(handler[Deployment](refOf), resyncMillis)

    informers = Vector(services, deployments)
    queue.start()

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

  /** Test seam. */
  private[operator] def workQueue: WorkQueue = queue
