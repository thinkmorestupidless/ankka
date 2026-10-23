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

  /**
   * Registered machines' broker users (feature 040), on a queue of their own, keyed by the
   * machine's resource name with no namespace: the resource is cluster-scoped.
   */
  private val machineReconciler =
    new MachineReconciler(client, settings, new Fabric8Executor(client))
  private val machineQueue = new WorkQueue(settings, machineReconciler.reconcile)

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

  /**
   * A project's pass writes its status, and since feature 041 the status carries the archive's lag,
   * which differs every pass: were a status write a reason to reconcile, each pass would ask for
   * the next one. So an update is passed on only when the spec changed (the generation moved) or
   * when nothing changed at all, which is the informer's resync.
   */
  private def specChanges[T <: io.fabric8.kubernetes.api.model.HasMetadata](
      inner: ResourceEventHandler[T]
  ): ResourceEventHandler[T] =
    new ResourceEventHandler[T]:
      def onAdd(obj: T): Unit                = inner.onAdd(obj)
      def onUpdate(old: T, updated: T): Unit =
        val resync = old.getMetadata.getResourceVersion == updated.getMetadata.getResourceVersion
        val generation = old.getMetadata.getGeneration != updated.getMetadata.getGeneration
        if resync || generation then inner.onUpdate(old, updated)
      def onDelete(obj: T, deletedFinalStateUnknown: Boolean): Unit =
        inner.onDelete(obj, deletedFinalStateUnknown)

  /**
   * A project's resource changed: the project is reconciled, and so is every service of it, whose
   * declared brokers (feature 037) are mounted from it. So is every service of another project its
   * topic grants name, before or after the change (feature 040): a grant made or revoked is an
   * entry on that service's broker user, which must not wait for a resync.
   */
  private def projectChanged(old: Option[AnkkaProject], now: AnkkaProject): Unit =
    Option(now.getMetadata).filter(m => watched(m.getNamespace)).foreach { m =>
      client
        .resources(classOf[AnkkaService])
        .inNamespace(m.getNamespace)
        .list()
        .getItems
        .asScala
        .foreach(s => queue.enqueue(ServiceRef(m.getNamespace, s.getMetadata.getName)))
      val grantees = (old.toVector :+ now)
        .flatMap(p => Option(p.getSpec).toVector)
        .flatMap(_.grants)
        .filter(_.kind == "topic")
        .flatMap(g => Operator.granteeService(g.grantee))
        .distinct
      grantees.foreach { (project, service) =>
        val namespace = Names.namespace(settings.namespacePrefix, project)
        if watched(namespace) then queue.enqueue(ServiceRef(namespace, service))
      }
      // A machine's grants are entries on its broker user too.
      (old.toVector :+ now)
        .flatMap(p => Option(p.getSpec).toVector)
        .flatMap(_.grants)
        .filter(_.kind == "topic")
        .flatMap(g => Operator.granteeMachine(g.grantee))
        .distinct
        .foreach((organization, name) =>
          machineQueue.enqueue(
            ServiceRef(
              "",
              com.thinkmorestupidless.ankka.crd.AnkkaMachine.nameOf(organization, name)
            )
          )
        )
      projectQueue.enqueue(ServiceRef(m.getNamespace, m.getName))
    }

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
              specChanges(new ResourceEventHandler[AnkkaProject] {
                def onAdd(obj: AnkkaProject): Unit = projectChanged(None, obj)
                def onUpdate(old: AnkkaProject, updated: AnkkaProject): Unit =
                  projectChanged(Some(old), updated)
                def onDelete(obj: AnkkaProject, deletedFinalStateUnknown: Boolean): Unit =
                  projectChanged(None, obj)
              }),
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

    // Feature 041: a project's status says what its database's archive and instances are doing,
    // so a change to its cluster or a new base backup reports it now rather than at the next
    // resync: that is what puts a failing archive on the status within five minutes (SC-003).
    // The project's resource is named for the project, in the project's namespace.
    def projectOf(meta: io.fabric8.kubernetes.api.model.ObjectMeta): Option[ServiceRef] =
      Option(meta)
        .map(_.getNamespace)
        .filter(watched)
        // A rehearsal's cluster is its project's to report: its namespace is the project's own
        // with the suffix, and nothing else may end so (`Recovery.projectProblems`).
        .map(_.stripSuffix(com.thinkmorestupidless.ankka.crd.Recovery.RehearsalSuffix))
        .map(ns => ServiceRef(ns, ns.stripPrefix(s"${settings.namespacePrefix}-")))
    def databaseInformer[T <: io.fabric8.kubernetes.api.model.HasMetadata](kind: Class[T]) =
      try
        Some(
          client
            .resources(kind)
            .inAnyNamespace()
            .inform(handler[T](r => projectOf(r.getMetadata))(using projectQueue), resyncMillis)
        )
      catch
        case scala.util.control.NonFatal(e) =>
          log.warn(
            "no {} type in this cluster; backups are not reported: {}",
            kind.getSimpleName,
            e.getMessage
          )
          None
    val databaseInformers =
      Vector(
        databaseInformer(classOf[cnpg.PostgresCluster]),
        databaseInformer(classOf[cnpg.PostgresBackup])
      ).flatten

    // As for AnkkaProject: a cluster without the type has its services reconciled all the same.
    val machineInformer =
      try
        Some(
          client
            .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaMachine])
            .inform(
              handler[com.thinkmorestupidless.ankka.crd.AnkkaMachine](m =>
                Option(m.getMetadata).map(meta => ServiceRef("", meta.getName))
              )(using machineQueue),
              resyncMillis
            )
        )
      catch
        case scala.util.control.NonFatal(e) =>
          log.warn(
            "no AnkkaMachine type in this cluster; machines are given nothing: {}",
            e.getMessage
          )
          None

    informers = Vector(services, deployments) ++ projectInformer ++ cloudInformer ++
      databaseInformers ++ machineInformer
    queue.start()
    projectQueue.start()
    machineQueue.start()

    log.info(
      "operator watching namespaces '{}-*' (resync every {})",
      settings.namespacePrefix,
      settings.resyncInterval
    )

  /** Waits until the process is interrupted. The informers do the work on their own threads. */
  def awaitTermination(): Unit =
    try Thread.currentThread().join()
    catch case _: InterruptedException => Thread.currentThread().interrupt()

  /**
   * The platform's own databases' backup buckets and credentials (feature 041), ensured at start
   * and on every resync by an executor of their own, which holds the store. A failure is logged and
   * tried again at the next resync: it never stops the operator reconciling services.
   */
  private val platform: Option[java.util.concurrent.ScheduledExecutorService] =
    Option.when(PlatformBackups.actions(settings).nonEmpty) {
      val executor  = Fabric8Executor.of(client, settings)
      val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r =>
        val thread = new Thread(r, "ankka-operator-platform-backups")
        thread.setDaemon(true)
        thread
      }
      scheduler.scheduleWithFixedDelay(
        () =>
          try PlatformBackups.actions(settings).foreach(executor.execute)
          catch
            // An installation whose control plane is not in this cluster has no namespace for it.
            case e: io.fabric8.kubernetes.client.KubernetesClientException if e.getCode == 404 =>
              log.debug("no namespace for the control plane's backup credential: {}", e.getMessage)
            case scala.util.control.NonFatal(e) =>
              log.warn("could not ensure the platform's backup credentials: {}", e.getMessage)
        ,
        0L,
        settings.resyncInterval.toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      ): Unit
      scheduler
    }

  def close(): Unit =
    platform.foreach(_.shutdownNow()): Unit
    informers.foreach(_.close())
    informers = Vector.empty
    queue.stop()
    projectQueue.stop()
    machineQueue.stop()

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
      meta  <- Option(request.getMetadata)
      ns    <- Option(meta.getNamespace)
      owner <- Option(meta.getOwnerReferences).toVector
        .flatMap(_.asScala)
        .find(o => Option(o.getController).exists(_.booleanValue))
      kind <- owner.getKind match
        case "AnkkaService" => Some(Owner.Service)
        case "AnkkaProject" => Some(Owner.Project)
        case _              => None
    yield kind -> ServiceRef(ns, owner.getName)

  /** The organization and name a grantee word names, when it names a machine (`machine:o/n`). */
  def granteeMachine(grantee: String): Option[(String, String)] =
    grantee.stripPrefix("machine:") match
      case rest if rest != grantee && rest.count(_ == '/') == 1 =>
        val (organization, name) = rest.span(_ != '/')
        Option.when(organization.nonEmpty && name.length > 1)((organization, name.drop(1)))
      case _ => None

  /** The project and service a grantee word names, when it names a service (`service:p/n`). */
  def granteeService(grantee: String): Option[(String, String)] =
    grantee.stripPrefix("service:") match
      case rest if rest != grantee && rest.count(_ == '/') == 1 =>
        val (project, service) = rest.span(_ != '/')
        Option.when(project.nonEmpty && service.length > 1)((project, service.drop(1)))
      case _ => None
