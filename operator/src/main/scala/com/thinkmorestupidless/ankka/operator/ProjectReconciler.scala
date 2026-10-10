package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaProjectSpec,
  BackupsStatus,
  ProjectClusterStatus,
  ProjectDatabaseStatus,
  RestoreStatus,
  ServiceVerification
}
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant

/**
 * One pass for one project: its topics (feature 027), and since feature 041 what its status says of
 * its backups and its database. Read the declarations, make each topic on the installation's
 * broker, report how far each has got, and what the project database's archive is doing.
 *
 * Level-triggered, as a service's pass is. A topic no longer declared is neither rendered nor
 * reported, and its `KafkaTopic` is left where it is: nothing the platform made on the broker is
 * removed by it. The archiving objects are rendered by a service's pass, beside the cluster it
 * ensures, so a project with no resource of its own is backed up all the same.
 */
final class ProjectReconciler(
    client: KubernetesClient,
    settings: Settings,
    executor: Executor,
    clock: () => Instant = () => Instant.now()
) extends Reconciler:

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.projects")

  def reconcile(ref: ServiceRef): Unit =
    Option(
      client.resources(classOf[AnkkaProject]).inNamespace(ref.namespace).withName(ref.name).get()
    ) match
      case None => log.debug("project {} no longer exists; nothing to do", ref)
      case Some(project) =>
        val spec  = Option(project.getSpec).getOrElse(AnkkaProjectSpec())
        val names = spec.topics.toVector.map(t => BrokerNames.topic(spec.projectId, t.name))
        val observed =
          settings.broker.fold(Map.empty)(b => executor.observeTopics(b.namespace, names))
        val services = executor.servicesIn(ref.namespace)
        val current  = Option(project.getStatus)
        val restores =
          ProjectReconciler.restores(
            ref.namespace,
            spec,
            settings,
            executor,
            services,
            current,
            clock()
          )
        val backups =
          ProjectReconciler.backups(ref.namespace, spec, settings, executor, clock(), services)
        val rehearsals = ProjectReconciler.rehearsals(
          spec,
          settings,
          executor,
          services,
          current,
          backups.status,
          clock()
        )
        (restores.actions ++ rehearsals.actions ++ ProjectReconciler.actions(
          ref,
          spec,
          settings.broker,
          observed,
          current,
          backups.copy(
            restores = restores.statuses,
            clusters = restores.clusters,
            rehearsals = rehearsals.statuses
          )
        )).foreach(executor.execute)

object ProjectReconciler:

  /** What a pass observed of a project's backups and its database (feature 041). */
  final case class Backups(
      status: Option[BackupsStatus],
      database: Option[ProjectDatabaseStatus],
      restores: List[RestoreStatus] = Nil,
      clusters: List[ProjectClusterStatus] = Nil,
      rehearsals: List[com.thinkmorestupidless.ankka.crd.RehearsalStatus] = Nil
  )

  /** What a pass does about a project's rehearsals, and what it reports of them. */
  final case class Rehearsals(
      actions: Vector[Action],
      statuses: List[com.thinkmorestupidless.ankka.crd.RehearsalStatus]
  )

  object Backups:
    val none: Backups = Backups(None, None)

  /** What a pass does about a project's restores, and what it reports of them. */
  final case class Restores(
      actions: Vector[Action],
      statuses: List[RestoreStatus],
      clusters: List[ProjectClusterStatus]
  )

  /**
   * Everything one pass does, as values: a topic for each declaration that would not shrink one the
   * broker has, then the status, unless it says what the resource already says.
   */
  def actions(
      ref: ServiceRef,
      spec: AnkkaProjectSpec,
      broker: Option[BrokerSettings],
      observed: Map[String, TopicState],
      current: Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus],
      backups: Backups = Backups.none
  ): Vector[Action] =
    val topics = broker.toVector.flatMap(b =>
      TopicProvisioning
        .topicsToRender(spec, broker, observed)
        .map(t => Action.EnsureKafkaTopic(StrimziRendering.topic(spec.projectId, t, b)))
    )
    val next = TopicProvisioning
      .status(spec, broker, observed)
      .copy(
        backups = backups.status,
        database = backups.database,
        restores = backups.restores,
        clusters = backups.clusters,
        rehearsals = backups.rehearsals
      )
    // The declarations every service of the project reads at start (feature 037), with or without
    // a broker: a contract is checked wherever the topic lives.
    (Action.EnsureProjectConfig(ProjectConfig.configMap(ref.namespace, spec)) +: topics) ++
      Option.unless(current.contains(next))(
        Action.SetProjectStatus(ref.namespace, ref.name, next)
      )

  /**
   * The project database's backups and instances, as the status says them (research R9). The
   * archive's lag and the replicas a synchronous write waits for are read inside the database, on
   * its primary, by `psql`; the rest from the cluster, the plugin's ObjectStore and the newest base
   * backup. With no backup target the status says so, and only the instances are read.
   */
  def backups(
      namespace: String,
      spec: AnkkaProjectSpec,
      settings: Settings,
      executor: Executor,
      now: Instant,
      services: Vector[(String, Option[String])] = Vector.empty
  ): Backups =
    val cluster  = CnpgRendering.projectClusterName
    val observed = executor.observeBackups(namespace, cluster, CnpgRendering.BackupObjectStoreName)
    val primary  = observed.cluster.flatMap(_.currentPrimary)
    def ask(sql: String) = primary.flatMap(p => executor.query(namespace, p, "postgres", sql))
    // Feature 041: a restore a service is switched to archives as a line of history of its own.
    val inUse = services.flatMap(_._2).distinct.filter(c => spec.restores.exists(_.name == c))
    def lineOf(name: String): Option[com.thinkmorestupidless.ankka.crd.LineStatus] =
      val seen = executor.observeBackups(namespace, name, CnpgRendering.BackupObjectStoreName)
      seen.cluster.map { c =>
        val archive = c.currentPrimary
          .flatMap(p => executor.query(namespace, p, "postgres", DatabaseQueries.Archive.sql))
          .flatMap(DatabaseQueries.Archive.parse)
        BackupStatus.line(name, name, seen, archive, None, settings.backups.copyRequired, now)
      }
    val replicas    = spec.database.map(_.replicas).filter(_ > 0).getOrElse(0)
    val synchronous = spec.database.exists(_.synchronous) && replicas > 0
    val database = observed.cluster.map { status =>
      val streaming =
        if synchronous then
          ask(DatabaseQueries.Replication.sql).map(DatabaseQueries.Replication.parse)
        else None
      ProjectDatabaseStatus(
        cluster = cluster,
        instances = math.max(status.instances, 1 + replicas),
        readyInstances = status.readyInstances,
        primary = primary,
        synchronous = synchronous,
        // A synchronous write waits for a replica in sync; with none, every write waits.
        writesWaitingOn = streaming.flatMap(rows =>
          Option.when(!rows.exists((_, state) => state == "sync" || state == "quorum"))(
            if rows.isEmpty then "no replica is streaming"
            else s"no replica is in sync: ${rows.map((n, s) => s"$n $s").mkString(", ")}"
          )
        )
      )
    }
    val status =
      if !settings.backups.enabled then
        BackupsStatus(target = "none", detail = Some("the installation has no backup target"))
      else if observed.cluster.isEmpty then
        BackupsStatus(target = "object-store", detail = Some("the project has no database yet"))
      else
        val archive = ask(DatabaseQueries.Archive.sql).flatMap(DatabaseQueries.Archive.parse)
        BackupsStatus(
          target = "object-store",
          lines = BackupStatus.line(
            cluster,
            cluster,
            observed,
            archive,
            copiedAt = None,
            copyRequired = settings.backups.copyRequired,
            now = now
          ) :: inUse.flatMap(lineOf).toList
        )
    Backups(Some(status), database)

  /**
   * Whether a recovery of the project database to `targetTime` can begin (`RecoveryPoint`): the
   * source has archived a commit after the moment. Asked only before a cluster is first rendered.
   */
  private def recoverable(
      namespace: String,
      name: String,
      targetTime: String,
      executor: Executor,
      now: Instant
  ): Boolean =
    val source = executor
      .observeBackups(
        namespace,
        CnpgRendering.projectClusterName,
        CnpgRendering.BackupObjectStoreName
      )
      .cluster
      .flatMap(_.currentPrimary)
    scala.util.Try(Instant.parse(targetTime)).toOption.exists { moment =>
      RecoveryPoint.reached(
        s"$namespace/$name",
        moment,
        now,
        sql => source.flatMap(p => executor.query(namespace, p, "postgres", sql))
      )
    }

  /**
   * Every restore the project has asked for (feature 041, research R12): its cluster rendered,
   * verified once it is healthy — what each service's database holds, read by `psql` on its primary
   * — and in use once a service is switched to it. A verified restore keeps its report: it is read
   * once, not every pass. And the project's clusters, with the services on each.
   */
  def restores(
      namespace: String,
      spec: AnkkaProjectSpec,
      settings: Settings,
      executor: Executor,
      services: Vector[(String, Option[String])],
      current: Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus],
      now: Instant = Instant.now()
  ): Restores =
    val previous  = current.toList.flatMap(_.restores).map(r => r.name -> r).toMap
    val onCluster = services.groupMap(_._2.getOrElse(CnpgRendering.projectClusterName))(_._1)
    val rendered = spec.restores.toVector.map { entry =>
      val inUse = onCluster.contains(entry.name)
      val exists = executor
        .observeBackups(namespace, entry.name, CnpgRendering.BackupObjectStoreName)
        .cluster
        .isDefined
      val actions =
        if exists || recoverable(namespace, entry.name, entry.targetTime, executor, now) then
          RestoreRendering.actions(namespace, entry, settings, spec.database, inUse)
        else Vector.empty
      val before = previous.get(entry.name)
      val status =
        before.filter(b => b.phase == "Verified" || b.phase == "InUse" || b.phase == "Failed") match
          case Some(done) if done.phase == "Failed" => done
          case Some(done) => done.copy(phase = if inUse then "InUse" else "Verified")
          case None =>
            val cluster = executor
              .observeBackups(namespace, entry.name, CnpgRendering.BackupObjectStoreName)
              .cluster
            val phase = cluster.flatMap(_.phase).getOrElse("")
            if phase == "Cluster in healthy state" then
              verify(namespace, entry, cluster.flatMap(_.currentPrimary), services, executor, inUse)
            else if phase.toLowerCase.contains("fail") then
              RestoreStatus(
                entry.name,
                entry.line,
                entry.targetTime,
                "Failed",
                detail = cluster.flatMap(_.phaseReason).filter(_.nonEmpty).orElse(Some(phase))
              )
            else if overdue(entry.requestedAt, settings, now) then
              RestoreStatus(
                entry.name,
                entry.line,
                entry.targetTime,
                "Failed",
                detail = Some(timedOut(settings, phase))
              )
            else
              RestoreStatus(
                entry.name,
                entry.line,
                entry.targetTime,
                "Restoring",
                detail = Option(phase).filter(_.nonEmpty)
              )
      actions -> status
    }
    val clusters =
      val live         = CnpgRendering.projectClusterName
      val liveServices = onCluster.getOrElse(live, Vector.empty)
      val inUse        = rendered.map(_._2).filter(_.phase == "InUse").map(r => r.name -> r.line)
      // When the last service left, kept from the pass that first saw it gone.
      val wasLeft =
        current.toList.flatMap(_.clusters).find(c => c.name == live && c.phase == "left")
      val left = liveServices.isEmpty && inUse.nonEmpty
      Option
        .when(liveServices.nonEmpty || inUse.nonEmpty)(
          ProjectClusterStatus(
            live,
            live,
            if left then "left" else "live",
            liveServices.sorted.toList,
            leftAt = Option.when(left)(wasLeft.flatMap(_.leftAt).getOrElse(now.toString))
          )
        )
        .toList ++
        rendered.map(_._2).zip(spec.restores).map { (r, entry) =>
          ProjectClusterStatus(
            r.name,
            r.line,
            "restore",
            onCluster.getOrElse(r.name, Vector.empty).sorted.toList,
            // A restore's age is from when it was asked for.
            since = Option(entry.requestedAt).filter(_.nonEmpty)
          )
        }
    Restores(rendered.flatMap(_._1), rendered.map(_._2).toList, clusters)

  /** Whether a restore or rehearsal begun at `begun` has had longer than it may to be healthy. */
  private def overdue(begun: String, settings: Settings, now: Instant): Boolean =
    scala.util
      .Try(Instant.parse(begun))
      .toOption
      .exists(_.plusMillis(settings.backups.restoreTimeout.toMillis).isBefore(now))

  private def timedOut(settings: Settings, phase: String): String =
    s"not healthy within ${settings.backups.restoreTimeout.toMinutes} minutes" +
      Option(phase).filter(_.nonEmpty).fold("")(p => s": $p")

  /**
   * The rehearsals a project keeps on its status: the latest, as many as the control plane does.
   */
  val KeptRehearsals: Int = 100

  /**
   * Every rehearsal of the project (feature 041, research R15): asked for by a member, or due on
   * the project's schedule. Each is rendered in the rehearsal namespace, verified once healthy as a
   * restore is, timed from when it began, and removed; one that could not be removed is reported
   * `NotRemoved`, and every cluster in the namespace past its time to live is removed on every
   * pass. A finished rehearsal's report is kept and never rendered again.
   */
  def rehearsals(
      spec: AnkkaProjectSpec,
      settings: Settings,
      executor: Executor,
      services: Vector[(String, Option[String])],
      current: Option[com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus],
      backups: Option[BackupsStatus],
      now: Instant
  ): Rehearsals =
    import com.thinkmorestupidless.ankka.crd.{RehearsalEntry, RehearsalStatus, RestoreEntry}
    if !settings.backups.enabled then Rehearsals(Vector.empty, current.toList.flatMap(_.rehearsals))
    else
      val ns                       = RehearsalRendering.namespace(settings, spec.projectId)
      val previous                 = current.toList.flatMap(_.rehearsals)
      val byName                   = previous.map(r => r.name -> r).toMap
      def done(r: RehearsalStatus) = Set("Completed", "Failed", "NotRemoved").contains(r.outcome)
      // A scheduled rehearsal is the operator's own: due when none began within the period, to
      // the latest moment the project database's line can be restored to.
      val period = spec.database.flatMap(_.rehearsalSchedule).collect {
        case "daily"  => java.time.Duration.ofDays(1)
        case "weekly" => java.time.Duration.ofDays(7)
      }
      val lastBegun = previous
        .flatMap(_.startedAt)
        .flatMap(t => scala.util.Try(Instant.parse(t)).toOption)
        .maxOption
      val running = previous.exists(r => !done(r)) || spec.rehearsals.exists(e =>
        !byName.get(e.name).exists(done)
      )
      val scheduled =
        for
          every <- period
          if !running && lastBegun.forall(_.plus(every).isBefore(now))
          line   <- backups.toList.flatMap(_.lines).find(_.line == CnpgRendering.projectClusterName)
          moment <- line.lastRestorable
        yield RehearsalEntry(
          com.thinkmorestupidless.ankka.crd.Recovery.rehearsalName(now),
          line.line,
          moment,
          now.toString
        )
      // Those still running that the spec no longer names: a scheduled one, carried by its status.
      val carried = previous
        .filterNot(done)
        .filterNot(r => spec.rehearsals.exists(_.name == r.name))
        .map(r =>
          RehearsalEntry(
            r.name,
            CnpgRendering.projectClusterName,
            r.targetTime,
            r.startedAt.getOrElse("")
          )
        )
      val entries = (spec.rehearsals ++ carried ++ scheduled).distinctBy(_.name)
      val gen     = spec.backups.map(_.credentialGeneration).getOrElse(0)

      val rendered = entries.toVector.map { entry =>
        byName.get(entry.name) match
          case Some(finished) if done(finished) => Vector.empty -> finished
          case before =>
            val startedAt = before.flatMap(_.startedAt).getOrElse(now.toString)
            val running = RehearsalStatus(entry.name, entry.targetTime, "Running", Some(startedAt))
            val cluster = executor
              .observeBackups(ns, entry.name, CnpgRendering.BackupObjectStoreName)
              .cluster
            val phase = cluster.flatMap(_.phase).getOrElse("")
            def finish(outcome: RehearsalStatus): RehearsalStatus =
              executor.deleteRehearsalCluster(ns, entry.name) match
                case Right(_) => outcome
                case Left(why) =>
                  outcome.copy(
                    outcome = "NotRemoved",
                    detail = Some(
                      s"${outcome.outcome.toLowerCase}, and its database was not removed: $why; " +
                        "it is removed when its time to live passes"
                    )
                  )
            val elapsed =
              scala.util
                .Try(Instant.parse(startedAt))
                .toOption
                .map(s => now.getEpochSecond - s.getEpochSecond)
            if phase == "Cluster in healthy state" then
              val verified = verify(
                ns,
                RestoreEntry(entry.name, entry.line, entry.targetTime, entry.requestedAt),
                cluster.flatMap(_.currentPrimary),
                services,
                executor,
                inUse = false
              )
              if verified.phase == "Verified" then
                Vector.empty -> finish(
                  running.copy(
                    outcome = "Completed",
                    elapsedSeconds = elapsed,
                    services = verified.services
                  )
                )
              else RehearsalRendering.actions(spec.projectId, entry, settings, gen) -> running
            else if phase.toLowerCase.contains("fail") then
              Vector.empty -> finish(
                running.copy(
                  outcome = "Failed",
                  elapsedSeconds = elapsed,
                  detail = cluster.flatMap(_.phaseReason).filter(_.nonEmpty).orElse(Some(phase))
                )
              )
            else if overdue(startedAt, settings, now) then
              Vector.empty -> finish(
                running.copy(
                  outcome = "Failed",
                  elapsedSeconds = elapsed,
                  detail = Some(timedOut(settings, phase))
                )
              )
            else if cluster.isEmpty &&
              !recoverable(
                s"${settings.namespacePrefix}-${spec.projectId}",
                entry.name,
                entry.targetTime,
                executor,
                now
              )
            then
              Vector.empty -> running.copy(detail =
                Some("waiting for the archive to pass the moment")
              )
            else
              RehearsalRendering.actions(spec.projectId, entry, settings, gen) ->
                running.copy(detail = Option(phase).filter(_.nonEmpty))
      }
      // A rehearsal's database past its time to live goes, whatever became of the rehearsal.
      val stillRunning = rendered.collect { case (_, r) if r.outcome == "Running" => r.name }.toSet
      executor
        .rehearsalClusters(ns)
        .filter((name, expires) => !stillRunning(name) && RehearsalRendering.expired(expires, now))
        .foreach((name, _) => executor.deleteRehearsalCluster(ns, name): Unit)
      val statuses =
        (previous.filterNot(r => entries.exists(_.name == r.name)) ++ rendered.map(_._2))
          .sortBy(r => r.startedAt.getOrElse(""))
          .takeRight(KeptRehearsals)
      Rehearsals(rendered.flatMap(_._1), statuses)

  /** A healthy restore, read once: which service databases it holds, and what each holds. */
  private def verify(
      namespace: String,
      entry: com.thinkmorestupidless.ankka.crd.RestoreEntry,
      primary: Option[String],
      services: Vector[(String, Option[String])],
      executor: Executor,
      inUse: Boolean
  ): RestoreStatus =
    def ask(database: String, sql: String) =
      primary.flatMap(p => executor.query(namespace, p, database, sql))
    val present = ask("postgres", DatabaseQueries.Presence.sql).map(DatabaseQueries.Presence.parse)
    present match
      case None =>
        RestoreStatus(
          entry.name,
          entry.line,
          entry.targetTime,
          "Restoring",
          detail = Some("healthy, and waiting to be read")
        )
      case Some(databases) =>
        val at = java.time.Instant.parse(entry.targetTime)
        val verified = services.map(_._1).sorted.map { service =>
          if !databases.contains(service) then ServiceVerification(name = service, present = false)
          else
            ask(service, DatabaseQueries.verification(at))
              .map(DatabaseQueries.parseVerification(service, _))
              .getOrElse(ServiceVerification(name = service, present = true))
        }
        val reached = verified
          .filter(_.present)
          .flatMap(v =>
            ask(v.name, DatabaseQueries.LastWrite.sql).flatMap(DatabaseQueries.LastWrite.parse)
          )
          .maxOption
        RestoreStatus(
          entry.name,
          entry.line,
          entry.targetTime,
          if inUse then "InUse" else "Verified",
          reachedAt = reached.map(_.toString),
          services = verified.toList
        )

  def apply(client: KubernetesClient, settings: Settings): ProjectReconciler =
    new ProjectReconciler(client, settings, Fabric8Executor.of(client, settings))
