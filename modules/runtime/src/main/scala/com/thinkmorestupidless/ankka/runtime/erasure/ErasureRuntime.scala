package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaExecutors,
  AnkkaService,
  Database,
  NoDatabase,
  RuntimeExtension
}
import com.thinkmorestupidless.ankka.runtime.remote.{
  RemoteKeyedViewDescriptor,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * What an extension erases of its own for a data subject — the agent module's sessions and
 * instances.
 */
trait ErasureDuty:
  def eraseSubject(project: String, subject: String): ErasureDuty.Result

object ErasureDuty:
  final case class Result(sessionsMarked: Int = 0, instancesStopped: Int = 0)

/**
 * The service's side of an erasure (FR-009, R16): one channel to the keyring, opened when the
 * service starts; every erasure the keyring's log holds past the highest this service applied,
 * applied before the service is ready; and every later one applied as the keyring orders it.
 *
 * Applying is, in order: drop the key, redact every view's rows, let every extension with a duty do
 * its own (agent sessions and instances), run the service's erasure handler, record the erasure in
 * `ankka_erasures_applied`, and tell the keyring. One erasure at a time, on one thread of its own.
 */
final class ErasureRuntime(
    connection: KeyringConnection,
    keyring: ServiceKeyring,
    project: String,
    serviceName: String,
    handler: Option[ErasureHandler],
    handlerTimeout: FiniteDuration = 5.minutes,
    objects: String => ObjectErasure = _ => ObjectErasure.noBucket
) extends RuntimeExtension:

  def name: String = "erasure"

  @volatile private var ready                 = false
  @volatile private var service: AnkkaService = scala.compiletime.uninitialized
  private val worker =
    Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ankka-erasure").factory())
  private given ExecutionContext = AnkkaExecutors.virtual

  override def readiness: Option[() => Boolean] = Some(() => ready)

  /** Whether the keyring's log has been applied, so the service may be ready. */
  def applied: Boolean = ready

  def start(service: AnkkaService): Unit =
    this.service = service
    given ActorSystem[?] = service.system
    val database = Option.unless(NoDatabase.declared(service.system.settings.config))(Database())
    val highest =
      database.flatMap(db => Await.result(AppliedErasures.highest(db), 30.seconds))
    connection.open(
      Hello(project, serviceName, service.system.address.toString, Set.empty, highest),
      listener(database)
    )

  override def stop(): Unit =
    try connection.close()
    finally worker.shutdown(): Unit

  private def listener(database: Option[Database]): KeyringListener = new KeyringListener:
    def log(entries: Vector[LogEntry]): Unit =
      submit {
        entries.sortBy(_.sequence).foreach { entry =>
          applyOne(
            database,
            ErasureOrder(entry.erasureId, entry.sequence, entry.subject, reapply = false)
          )
        }
        ready = true
      }

    def destroyed(project: String, subject: String, erasureId: String): Unit =
      keyring.cache.destroyed(project, subject, erasureId)
      connection.ack(erasureId)

    def apply(order: ErasureOrder): Unit = submit(applyOne(database, order))

    def closed(reason: String): Unit =
      if reason == "unacknowledged" then keyring.cache.clear()

  private def submit(work: => Unit): Unit =
    worker.execute { () =>
      try work
      catch
        case NonFatal(failure) =>
          service.system.log.warn("erasure: applying failed and will be applied again", failure)
    }

  /**
   * What this instance completed, by erasure: a first application sent again — the control plane
   * asks until it hears, and a channel that reconnects replays the log — is answered with it, and
   * nothing runs twice. A reapplication always runs.
   */
  private val completed = java.util.concurrent.ConcurrentHashMap[String, Completion]()

  private def applyOne(database: Option[Database], order: ErasureOrder): Unit =
    Option(completed.get(order.erasureId)).filterNot(_ => order.reapply) match
      case Some(done) => connection.completed(done)
      case None       => applyFully(database, order)

  private def applyFully(database: Option[Database], order: ErasureOrder): Unit =
    val system = service.system
    keyring.cache.destroyed(project, order.subject, order.erasureId)
    val (viewsRedacted, rows) =
      database.fold((Vector.empty[String], 0L))(db =>
        Await.result(ViewRedaction.redact(db, views, project, order.subject), 60.seconds)
      )
    val extensionDuties =
      service.extensionsOf[ErasureDuty].map(_.eraseSubject(project, order.subject))
    val duties = Duties(
      keyDropped = true,
      viewsRedacted = viewsRedacted,
      rowsRedacted = rows,
      sessionsMarked = extensionDuties.map(_.sessionsMarked).sum,
      instancesStopped = extensionDuties.map(_.instancesStopped).sum
    )
    val outcome    = handler.map(run(_, order))
    val completion = Completion(order.erasureId, order.sequence, duties, outcome)
    database.foreach(db =>
      Await.result(AppliedErasures.record(db, project, completion, order.subject), 30.seconds): Unit
    )
    system.log.info(
      "erasure applied: {} sequence {}{}: {} rows redacted in {} views{}",
      order.erasureId,
      order.sequence,
      if order.reapply then " (again)" else "",
      rows,
      viewsRedacted.size,
      outcome.fold("")(o => s"; handler ${if o.ok then "done" else "failed"}")
    )
    // A failed handler is not complete: the next order of the same erasure runs it again.
    if outcome.forall(_.ok) then completed.put(order.erasureId, completion): Unit
    connection.completed(completion)

  private def run(handler: ErasureHandler, order: ErasureOrder): HandlerOutcome =
    val context = new ErasureContext:
      def subject: String          = order.subject
      def erasureId: String        = order.erasureId
      def reapply: Boolean         = order.reapply
      def objects: ObjectErasure   = ErasureRuntime.this.objects(order.subject)
      def services: ServiceClients = service.services
      def secrets: SecretStore     = service.secrets
    try
      Await.result(Future(handler(context)), handlerTimeout) match
        case ErasureOutcome.Done(detail, objects) =>
          HandlerOutcome(ok = true, detail, objects.map(_.count), objects.map(_.finalAt))
        case ErasureOutcome.Failed(reason) => HandlerOutcome(ok = false, reason, None, None)
    catch
      case error: CommandError => HandlerOutcome(ok = false, error.message, None, None)
      case _: java.util.concurrent.TimeoutException =>
        HandlerOutcome(
          ok = false,
          s"the erasure handler did not finish within $handlerTimeout",
          None,
          None
        )
      case NonFatal(failure) =>
        HandlerOutcome(ok = false, s"the erasure handler threw: $failure", None, None)

  /** Every view of the service, as (component id, table). */
  private def views: Vector[(String, String)] =
    service.registry.components.toVector.collect {
      case v: ViewDescriptor[?, ?, ?]   => (v.componentId: String, v.tableName)
      case v: KeyedViewDescriptor[?, ?] => (v.componentId: String, v.tableName)
      case v: RemoteViewDescriptor =>
        (v.componentId: String, ViewDescriptor.tableFor(v.componentId))
      case v: RemoteKeyedViewDescriptor =>
        (v.componentId: String, ViewDescriptor.tableFor(v.componentId))
    }

  /** Waits until the log is applied — for a test kit, which reads nothing until then. */
  def awaitApplied(timeout: FiniteDuration): Boolean =
    val deadline = System.nanoTime() + timeout.toNanos
    while !ready && System.nanoTime() < deadline do Thread.sleep(20)
    ready

  private[ankka] def drain(timeout: FiniteDuration): Unit =
    val done = java.util.concurrent.CountDownLatch(1)
    worker.execute(() => done.countDown())
    done.await(timeout.toMillis, TimeUnit.MILLISECONDS): Unit
