package com.thinkmorestupidless.ankka.operator

import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.{
  ConcurrentHashMap,
  Executors,
  LinkedBlockingQueue,
  ScheduledExecutorService,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Which service a piece of work is about. */
final case class ServiceRef(namespace: String, name: String):
  override def toString: String = s"$namespace/$name"

/**
 * De-duplicating work queue with per-resource serialization and per-resource backoff.
 *
 * The three sets are what make the guarantees hold, and each earns its place:
 *
 *   - `queued` — a resource that changes five times before anyone looks at it is reconciled once,
 *     against its latest state. Reconciliation is level-triggered, so there is nothing to catch up
 *     on.
 *   - `inFlight` — one reconcile per resource at a time. Two workers acting on one service would
 *     race each other's writes to the same Deployment.
 *   - `dirty` — a change arriving *while* a resource is in flight must not be lost, which it would
 *     be if `queued` alone guarded re-entry. It is re-queued when the pass finishes.
 *
 * Without `dirty`, the common case of "apply, then the operator's own status write arrives
 * mid-pass" silently drops the second event, and the service settles one generation behind.
 */
final class WorkQueue(settings: Settings, reconcile: ServiceRef => Unit):

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.queue")

  private val pending  = new LinkedBlockingQueue[ServiceRef]()
  private val queued   = ConcurrentHashMap.newKeySet[ServiceRef]()
  private val inFlight = ConcurrentHashMap.newKeySet[ServiceRef]()
  private val dirty    = ConcurrentHashMap.newKeySet[ServiceRef]()
  private val attempts = new ConcurrentHashMap[ServiceRef, Integer]()

  private val running = new AtomicBoolean(false)

  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(r =>
      val t = new Thread(r, "ankka-operator-scheduler")
      t.setDaemon(true)
      t
    )

  /** Virtual threads: every reconcile blocks on the API server, and parking one is free. */
  private val workers = Executors.newVirtualThreadPerTaskExecutor()

  def enqueue(ref: ServiceRef): Unit = synchronized {
    if inFlight.contains(ref) then dirty.add(ref): Unit
    else if queued.add(ref) then pending.put(ref)
  }

  def enqueueAfter(ref: ServiceRef, delay: FiniteDuration): Unit =
    val _ = scheduler.schedule(
      (() => enqueue(ref)): Runnable,
      delay.toMillis,
      TimeUnit.MILLISECONDS
    )

  /** Everything currently known, re-examined. The backstop for changes no watch announced. */
  def enqueueAll(refs: Iterable[ServiceRef]): Unit = refs.foreach(enqueue)

  def start(): Unit =
    if running.compareAndSet(false, true) then
      (1 to settings.maxConcurrentReconciles).foreach(_ => workers.submit((() => loop()): Runnable))
      log.info(
        "reconcile workers started ({} concurrent, backoff {}..{})",
        settings.maxConcurrentReconciles,
        settings.retryMinBackoff,
        settings.retryMaxBackoff
      )

  def stop(): Unit =
    if running.compareAndSet(true, false) then
      scheduler.shutdownNow(): Unit
      workers.shutdownNow(): Unit

  /** For tests and for the resync sweep: what the queue has not yet dealt with. */
  def depth: Int = pending.size

  private def loop(): Unit =
    while running.get() do
      try
        val ref = pending.take()
        synchronized {
          queued.remove(ref): Unit
          inFlight.add(ref): Unit
        }
        try
          reconcile(ref)
          attempts.remove(ref): Unit
        catch
          case NonFatal(failure) =>
            // One failing resource must not delay any other, so the backoff is per
            // resource and the worker returns to the queue immediately.
            val attempt = attempts.merge(ref, 1, (a, b) => a + b).intValue
            val delay   = settings.backoffFor(attempt)
            log.warn(
              s"reconcile of $ref failed (attempt $attempt); retrying in $delay",
              failure
            )
            enqueueAfter(ref, delay)
        finally
          synchronized {
            inFlight.remove(ref): Unit
            if dirty.remove(ref) then enqueue(ref)
          }
      catch case _: InterruptedException => Thread.currentThread().interrupt()

  /** Test seam: how many consecutive failures a resource has accumulated. */
  private[operator] def attemptsFor(ref: ServiceRef): Int =
    Option(attempts.get(ref)).map(_.intValue).getOrElse(0)

  private[operator] def snapshotQueued: Set[ServiceRef] = queued.asScala.toSet
