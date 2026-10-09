package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService, RuntimeExtension}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}

import java.time.{Clock, Instant, LocalDate, ZoneOffset}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** How often the sweeper runs, how often an applied erasure is applied again, and for how long. */
final case class ErasureSettings(
    sweepInterval: FiniteDuration = 30.seconds,
    reapplyInterval: FiniteDuration = 15.minutes,
    reapplyGrace: FiniteDuration = 30.days
)

object ErasureSettings:
  def from(config: com.typesafe.config.Config): ErasureSettings =
    def d(key: String, default: FiniteDuration) =
      if config.hasPath(key) then config.getDuration(key).toMillis.millis else default
    ErasureSettings(
      d("ankka.controlplane.erasure.sweep-interval", 30.seconds),
      d("ankka.controlplane.erasure.reapply-interval", 15.minutes),
      d("ankka.controlplane.erasure.reapply-grace", 30.days)
    )

/**
 * Drives every erasure request that is not done (FR-009, FR-015, R21): a held one released when its
 * date has passed; an applying one written to both copies of the log, then destroyed in the
 * keyring, then followed until every service of the project has completed; an applied one applied
 * again on the reapplication interval, final once its last deletion is, and settled once past the
 * grace. A cluster singleton, so one sweeper runs at a time; each step is recorded by the request's
 * own entity, so a sweep cut short is resumed by the next.
 */
final class ErasureSweeper(
    keyring: KeyringCaller,
    bucket: ErasureLogBucket,
    settings: ErasureSettings,
    clock: Clock = Clock.systemUTC()
) extends RuntimeExtension:

  def name: String = "erasure-sweeper"

  @volatile private var service: AnkkaService = scala.compiletime.uninitialized
  private val lastReapplied = scala.collection.concurrent.TrieMap.empty[String, Instant]

  def start(service: AnkkaService): Unit =
    this.service = service
    val behavior: Behavior[ErasureSweeper.Tick.type] = Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay(ErasureSweeper.Tick, settings.sweepInterval)
      Behaviors.receiveMessage { _ =>
        AnkkaExecutors.virtual.execute(() => sweep())
        Behaviors.same
      }
    }
    ClusterSingleton(service.system).init(
      SingletonActor(
        Behaviors.supervise(behavior).onFailure(SupervisorStrategy.restart),
        "ankka-erasure-sweeper"
      )
    ): Unit

  private def client: ComponentClient = service.componentClient

  /** One pass over every request not yet settled. Public for a test that drives it by hand. */
  def sweep(): Unit = synchronized {
    val open = service.viewClient
      .forView(ErasureRows)
      .where(jsonText("state") ++ sql" IN ('held', 'applying', 'applied', 'final', 'failed')")
    open.foreach { request =>
      try drive(request)
      catch
        case NonFatal(failure) =>
          service.system.log.warn(s"erasure ${request.id}: ${failure.getMessage}")
    }
  }

  private def entity(r: ErasureRequest) =
    client.forEventSourcedEntity(EntityId(s"${r.projectId}/${r.id}"))
  private def now: Instant = clock.instant()

  private def drive(r: ErasureRequest): Unit = r.state match
    case ErasureState.Held =>
      if r.notBefore.exists(d => !LocalDate.ofInstant(now, ZoneOffset.UTC).isBefore(d)) then
        apply(entity(r).call(ErasureEntity.release).invoke(Step(now)))
    case ErasureState.Applying => apply(r)
    case ErasureState.Failed   => apply(entity(r).call(ErasureEntity.retry).invoke(Step(now)))
    case ErasureState.Applied | ErasureState.Final =>
      follow(r)
      reapplyWhenDue(r)
    case _ => ()

  /** Log, both copies; then the key; then completions. A failure records why and stops here. */
  private def apply(initial: ErasureRequest): Unit =
    var r = initial
    def fail(why: String): Unit =
      entity(r).call(ErasureEntity.failed).invoke(Step(now, reason = Some(why))): Unit
    try
      if r.sequence.isEmpty then
        val entry = client
          .forEventSourcedEntity(EntityId(ErasureLogEntity.Id))
          .call(ErasureLogEntity.append)
          .invoke(LogAppend(r.id, r.projectId, r.subject, now))
        bucket.append(
          KeyringApi.LogEntry(r.id, r.projectId, r.subject, entry.sequence, entry.at.toEpochMilli)
        )
        r = entity(r)
          .call(ErasureEntity.logWritten)
          .invoke(Step(now, sequence = Some(entry.sequence)))
      if r.keyDestroyedAt.isEmpty then
        val status =
          keyring.apply(r.projectId, KeyringApi.ApplyErasure(r.id, r.subject, r.sequence.get))
        r = entity(r)
          .call(ErasureEntity.keyDestroyed)
          .invoke(Step(Instant.ofEpochMilli(status.keyDestroyedAt)))
      follow(r)
    catch
      case error: CommandError => fail(error.message)
      case NonFatal(failure)   => fail(String.valueOf(failure.getMessage))

  /**
   * Records each service's completion as the keyring reports it; a service of the project with no
   * running instance is complete by absence and applies the log at its next start. Applied once
   * every service of the project is; final once the last deletion it made is.
   */
  private def follow(r: ErasureRequest): Unit =
    val status   = keyring.status(r.projectId, r.id)
    val services = projectServices(r.projectId)
    val reported =
      status.completedServices.flatMap(s => status.completions.filter(_.service == s).lastOption)
    reported.foreach { c =>
      entity(r)
        .call(ErasureEntity.completed)
        .invoke(
          ErasureServiceCompletion(
            c.service,
            Instant.ofEpochMilli(c.completedAt),
            c.handlerOk.map(ok =>
              (if ok then "done" else "failed") + c.handlerDetail
                .filter(_.nonEmpty)
                .fold("")(d => s": $d")
            ),
            c.objectsErased,
            c.objectsFinalAt.map(Instant.ofEpochMilli)
          )
        ): Unit
    }
    val told = status.channels.map(_.service).toSet ++ reported.map(_.service)
    services.filterNot((name, running) => running || told(name)).foreach { (name, _) =>
      entity(r)
        .call(ErasureEntity.completed)
        .invoke(ErasureServiceCompletion(name, now, byAbsence = true)): Unit
    }
    val current  = entity(r).call(ErasureEntity.get).invoke()
    val complete = services.keySet.forall(s => current.completions.exists(_.service == s))
    if current.state == ErasureState.Applying && complete then
      entity(r).call(ErasureEntity.applied).invoke(Step(now)): Unit
    val after     = entity(r).call(ErasureEntity.get).invoke()
    val lastFinal = after.completions.flatMap(_.objectsFinalAt).maxOption
    if after.state == ErasureState.Applied && lastFinal.forall(!_.isAfter(now)) then
      entity(r)
        .call(ErasureEntity.finalised)
        .invoke(
          Step(lastFinal.filter(_.isAfter(after.appliedAt.getOrElse(now))).getOrElse(now))
        ): Unit
    val settledAt = after.finalAt.map(_.plusMillis(settings.reapplyGrace.toMillis))
    if after.state == ErasureState.Final && settledAt.exists(!_.isAfter(now)) then
      entity(r).call(ErasureEntity.settled).invoke(Step(now)): Unit

  private def reapplyWhenDue(r: ErasureRequest): Unit =
    val last = lastReapplied.getOrElse(r.id, r.appliedAt.getOrElse(Instant.EPOCH))
    if !last.plusMillis(settings.reapplyInterval.toMillis).isAfter(now) then
      lastReapplied.put(r.id, now): Unit
      keyring.reapply(r.projectId, r.id): Unit

  /** Applies a settled erasure again, on a member's request. */
  def reapplyNow(r: ErasureRequest): Unit =
    lastReapplied.put(r.id, now): Unit
    keyring.reapply(r.projectId, r.id): Unit

  /** The project's services, each with whether it has an instance running. */
  private def projectServices(projectId: String): Map[String, Boolean] =
    service.viewClient
      .forView(ServiceRows)
      .where(jsonText("projectId") ++ sql" = $projectId")
      .filter(_.lifecycle != ServiceLifecycle.NotDeployed)
      .map(s => s.name -> (s.desiredInstances > 0 && !s.paused))
      .toMap

object ErasureSweeper:
  case object Tick
