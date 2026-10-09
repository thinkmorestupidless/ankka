package com.thinkmorestupidless.ankka.controlplane.secrets

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService, RuntimeExtension}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.slf4j.LoggerFactory

import java.time.{Clock, Instant}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/** How long the record of a read is kept, and how often old ones are looked for. */
final case class SecretRecordsConfig(retention: FiniteDuration, sweepInterval: FiniteDuration):
  /** The retention as the installation wrote it, for its status: `365d`. */
  def retentionText: String =
    val days = retention.toDays
    if retention == FiniteDuration(days, "days") then s"${days}d" else retention.toString

object SecretRecordsConfig:

  /**
   * What a retention left blank means: the manifest's placeholder is `""`, as for every setting.
   */
  val DefaultRetention: FiniteDuration = FiniteDuration(365, "days")

  def from(config: Config): SecretRecordsConfig =
    def duration(key: String) =
      FiniteDuration(config.getDuration(s"ankka.controlplane.secret-records.$key").toMillis, "ms")
    val retention =
      if config.getString("ankka.controlplane.secret-records.retention").trim.isEmpty then
        DefaultRetention
      else duration("retention")
    if retention.toMillis <= 0 then
      throw IllegalArgumentException("ANKKA_SECRET_RECORD_RETENTION must be a positive duration")
    SecretRecordsConfig(retention, duration("sweep-interval"))

/**
 * The control plane's record of secret reads, as an extension: its store is opened when the service
 * starts, on the actor system the pool needs, and its retention swept by a cluster singleton, one
 * batch at a time.
 *
 * Until it has started — or with no store at all — every call is `Unavailable`, which a service
 * reads as "not acknowledged" and refuses the read it was recording. A record is never silently
 * dropped.
 */
final class SecretRecords(
    config: SecretRecordsConfig,
    /** The store, or how to open it on the started service's actor system. */
    opening: AnkkaService => ReadRecordStore,
    clock: Clock = Clock.systemUTC()
) extends RuntimeExtension
    with ReadRecordStore:

  private val log = LoggerFactory.getLogger(classOf[SecretRecords])
  @volatile private var store: Option[ReadRecordStore] = None

  val name: String = "secret-records"

  def start(service: AnkkaService): Unit =
    store = Some(opening(service))
    val _ = ClusterSingleton(service.system).init(
      SingletonActor(RetentionSweeper(() => sweep(), config.sweepInterval), "ankka-secret-records")
    )
    log.info("secret read records kept for {}", config.retentionText)

  override def readiness: Option[() => Boolean] = Some(() => store.isDefined)

  /** Removes what is older than the retention; how many. */
  def sweep(): Long =
    val removed = current.deleteOlderThan(clock.instant().minusMillis(config.retention.toMillis))
    if removed > 0 then log.info("removed {} secret read records past their retention", removed)
    removed

  private def current: ReadRecordStore =
    store.getOrElse(
      throw CommandError("the record of secret reads is not open yet", ErrorCode.Unavailable)
    )

  def insert(record: ReadRecord): Unit = current.insert(record)

  def list(
      project: String,
      service: Option[String],
      name: Option[String],
      from: Option[Instant],
      to: Option[Instant],
      limit: Int
  ): Vector[ReadRecord] = current.list(project, service, name, from, to, limit)

  def deleteOlderThan(before: Instant): Long = current.deleteOlderThan(before)

object SecretRecords:

  /** Over the record's own database, at `ReadRecordStore.ConnectionFactoryPath`. */
  def postgres(config: SecretRecordsConfig): SecretRecords =
    SecretRecords(config, service => PostgresReadRecordStore.open()(using service.system))

  /** Over `store`, already open: what a suite uses. */
  def over(store: ReadRecordStore, config: SecretRecordsConfig, clock: Clock): SecretRecords =
    SecretRecords(config, _ => store, clock)

/** The singleton that sweeps: one batch at a time, a tick mid-sweep dropped. */
private[secrets] object RetentionSweeper:

  private sealed trait Command
  private case object Tick                            extends Command
  private case object Finished                        extends Command
  private final case class Failed(failure: Throwable) extends Command

  def apply(sweep: () => Long, interval: FiniteDuration): Behavior[Nothing] =
    Behaviors
      .setup[Command] { ctx =>
        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Tick, interval)

          def idle: Behavior[Command] = Behaviors.receiveMessage {
            case Tick =>
              ctx.pipeToSelf(Future(sweep())(using AnkkaExecutors.virtual)) {
                case Success(_)       => Finished
                case Failure(failure) => Failed(failure)
              }
              busy
            case _ => Behaviors.same
          }

          def busy: Behavior[Command] = Behaviors.receiveMessage {
            case Tick     => Behaviors.same
            case Finished => idle
            case Failed(failure) =>
              ctx.log.warn(
                "the secret read record sweep failed; retrying on the next tick",
                failure
              )
              idle
          }

          idle
        }
      }
      .narrow
