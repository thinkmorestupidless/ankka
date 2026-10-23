package com.thinkmorestupidless.ankka.operator

import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.{
  ConcurrentHashMap,
  Executors,
  LinkedBlockingQueue,
  ScheduledThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.*

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
 *
 * A worker outlives whatever its pass throws. A worker's loop that ended on an `Error` ended
 * silently: a virtual thread's task keeps its throwable in a `Future` nobody reads, the pass was
 * never retried, and an operator whose workers had all gone that way still watched, resynced and
 * logged, and reconciled nothing. `problems` is what the operator's probe reads, so a queue that
 * has stopped taking work is restarted rather than left idling.
 *
 * `clock` is nanoseconds, `System.nanoTime` unless a test supplies another.
 */
final class WorkQueue(
    settings: Settings,
    reconcile: ServiceRef => Unit,
    val name: String = "services",
    clock: () => Long = () => System.nanoTime()
):

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.queue")

  private val pending = new LinkedBlockingQueue[ServiceRef]()

  /** What waits to be taken, and since when (the clock's nanoseconds). */
  private val queued = new ConcurrentHashMap[ServiceRef, java.lang.Long]()

  /** What a worker holds, and since when. */
  private val inFlight = new ConcurrentHashMap[ServiceRef, java.lang.Long]()
  private val dirty    = ConcurrentHashMap.newKeySet[ServiceRef]()
  private val attempts = new ConcurrentHashMap[ServiceRef, Integer]()

  /**
   * The one later pass each resource has asked for, as the clock's nanoseconds it is due at. A pass
   * that ends waiting asks again, and so does every pass an event causes while it waits: without
   * this each such pass added a timer, every timer's pass added the next, and a service that waited
   * for hours was reconciled more often the longer it waited.
   */
  private val scheduled = new ConcurrentHashMap[ServiceRef, java.lang.Long]()

  private val running     = new AtomicBoolean(false)
  private val liveWorkers = new AtomicInteger(0)

  private val scheduler = new ScheduledThreadPoolExecutor(
    1,
    r =>
      val t = new Thread(r, "ankka-operator-scheduler")
      t.setDaemon(true)
      t
  )

  /** Virtual threads: every reconcile blocks on the API server, and parking one is free. */
  private val workers = Executors.newVirtualThreadPerTaskExecutor()

  def enqueue(ref: ServiceRef): Unit = synchronized {
    if inFlight.containsKey(ref) then dirty.add(ref): Unit
    else if queued.putIfAbsent(ref, clock()) == null then pending.put(ref)
  }

  /**
   * A pass for `ref` after `delay`, unless one is already due no later: level-triggered, so the
   * earlier pass sees everything the later one would have.
   */
  def enqueueAfter(ref: ServiceRef, delay: FiniteDuration): Unit =
    val due = java.lang.Long.valueOf(clock() + delay.toNanos)
    scheduled.compute(
      ref,
      (_, existing) =>
        if existing != null && existing.longValue <= due.longValue then existing
        else
          val _ = scheduler.schedule(
            (() =>
              scheduled.remove(ref, due): Unit
              enqueue(ref)
            ): Runnable,
            delay.toMillis,
            TimeUnit.MILLISECONDS
          )
          due
    ): Unit

  /** Everything currently known, re-examined. The backstop for changes no watch announced. */
  def enqueueAll(refs: Iterable[ServiceRef]): Unit = refs.foreach(enqueue)

  def start(): Unit =
    if running.compareAndSet(false, true) then
      (1 to settings.maxConcurrentReconciles).foreach(_ => workers.submit((() => loop()): Runnable))
      log.info(
        "{} reconcile workers started ({} concurrent, backoff {}..{})",
        name,
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

  /**
   * What is wrong with this queue, empty when nothing is: a worker gone, a resource waiting longer
   * than `stallBound` to be taken, or a pass running longer than it. Read by the health check.
   */
  def problems: Vector[String] =
    if !running.get() then Vector(s"the $name queue is not running")
    else
      val now   = clock()
      val bound = settings.stallBound.toNanos
      def oldest(of: ConcurrentHashMap[ServiceRef, java.lang.Long]) =
        of.asScala.toVector.map((ref, since) => (ref, now - since.longValue)).maxByOption(_._2)
      val alive = liveWorkers.get()
      Vector(
        Option.when(alive < settings.maxConcurrentReconciles)(
          s"the $name queue has $alive of ${settings.maxConcurrentReconciles} workers"
        ),
        oldest(queued)
          .filter(_._2 > bound)
          .map((ref, age) => s"$ref has waited ${age.nanos.toSeconds}s in the $name queue"),
        oldest(inFlight)
          .filter(_._2 > bound)
          .map((ref, age) => s"the pass for $ref has run ${age.nanos.toSeconds}s")
      ).flatten

  private def loop(): Unit =
    liveWorkers.incrementAndGet(): Unit
    try
      while running.get() do
        try
          val ref = pending.take()
          synchronized {
            queued.remove(ref): Unit
            inFlight.put(ref, clock()): Unit
          }
          try
            reconcile(ref)
            attempts.remove(ref): Unit
          catch
            case _: InterruptedException if !running.get() => ()
            // Any throwable, not only a non-fatal one: one failing resource must not delay any
            // other, nor take its worker with it, so the backoff is per resource and the worker
            // returns to the queue immediately. An exhausted heap is the JVM's to end
            // (`-XX:+ExitOnOutOfMemoryError` on the Deployment) before this sees it.
            case failure: Throwable =>
              val attempt = attempts.merge(ref, 1, (a, b) => a + b).intValue
              val delay   = settings.backoffFor(attempt)
              val message = s"reconcile of $ref failed (attempt $attempt); retrying in $delay"
              if scala.util.control.NonFatal(failure) then log.warn(message, failure)
              else log.error(message, failure)
              enqueueAfter(ref, delay)
          finally
            synchronized {
              inFlight.remove(ref): Unit
              if dirty.remove(ref) then enqueue(ref)
            }
        catch
          // `stop` interrupts a worker waiting for work; anything else that interrupts one is not
          // a reason to stop taking it, and setting the flag again here would make every later
          // `take` throw at once: a worker spinning with nothing done.
          case _: InterruptedException => ()
    finally liveWorkers.decrementAndGet(): Unit

  /** Test seam: how many consecutive failures a resource has accumulated. */
  private[operator] def attemptsFor(ref: ServiceRef): Int =
    Option(attempts.get(ref)).map(_.intValue).getOrElse(0)

  private[operator] def snapshotQueued: Set[ServiceRef] = queued.keySet.asScala.toSet

  /** Test seam: the timers waiting to fire, one per later pass scheduled. */
  private[operator] def timersPending: Int = scheduler.getQueue.size

  /** Test seam: the workers whose loop is running. */
  private[operator] def workersAlive: Int = liveWorkers.get()
