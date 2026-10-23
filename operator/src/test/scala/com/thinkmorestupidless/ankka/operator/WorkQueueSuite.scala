package com.thinkmorestupidless.ankka.operator

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The queue's workers and timers, offline. Each case is one the operator met or could meet on a
 * cluster: a pass that throws an `Error` took its worker with it and was never retried; a worker
 * interrupted outside `stop` spun without taking work; and every pass that ended waiting added a
 * timer, so a long wait was reconciled more often the longer it lasted.
 */
class WorkQueueSuite extends munit.FunSuite:

  private val settings =
    Settings.default.copy(
      maxConcurrentReconciles = 1,
      retryMinBackoff = 10.millis,
      retryMaxBackoff = 50.millis,
      stallBound = 1.minute
    )

  private val a = ServiceRef("ankka-p", "a")
  private val b = ServiceRef("ankka-p", "b")

  /** Waits up to five seconds for `done`, re-reading it. */
  private def eventually(what: String)(done: => Boolean): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while !done && System.nanoTime() < deadline do Thread.sleep(10)
    assert(done, what)

  private def withQueue(reconcile: ServiceRef => Unit, clock: () => Long = () => System.nanoTime())(
      body: WorkQueue => Unit
  ): Unit =
    val queue = new WorkQueue(settings, reconcile, "services", clock)
    queue.start()
    try body(queue)
    finally queue.stop()

  test("a pass that throws an Error keeps its worker, and is retried") {
    val passes = new ConcurrentLinkedQueue[ServiceRef]()
    val first  = new java.util.concurrent.atomic.AtomicBoolean(true)
    withQueue { ref =>
      passes.add(ref): Unit
      // What a class whose initialiser failed once throws on every later use: a LinkageError,
      // which `NonFatal` does not match.
      if ref == a && first.getAndSet(false) then
        throw new NoClassDefFoundError("Could not initialize")
    } { queue =>
      queue.enqueue(a)
      eventually("a is passed twice: the failure and its retry")(
        passes.asScala.count(_ == a) == 2
      )
      queue.enqueue(b)
      eventually("the one worker is still there to take b")(passes.contains(b))
      assertEquals(queue.workersAlive, 1)
      assertEquals(queue.problems, Vector.empty)
    }
  }

  test("a worker interrupted mid-pass, while the queue runs, goes on taking work") {
    val passes = new ConcurrentLinkedQueue[ServiceRef]()
    val first  = new java.util.concurrent.atomic.AtomicBoolean(true)
    withQueue { ref =>
      passes.add(ref): Unit
      if ref == a && first.getAndSet(false) then throw new InterruptedException("stray")
    } { queue =>
      queue.enqueue(a)
      eventually("a is retried")(passes.asScala.count(_ == a) == 2)
      queue.enqueue(b)
      eventually("b is taken")(passes.contains(b))
    }
  }

  test("later passes asked for one resource are one timer, and it fires") {
    val passes = new ConcurrentLinkedQueue[ServiceRef]()
    withQueue(ref => passes.add(ref): Unit) { queue =>
      (1 to 100).foreach(_ => queue.enqueueAfter(a, 1.hour))
      queue.enqueueAfter(a, 2.hours)
      assertEquals(queue.timersPending, 1, "a later or equal pass is already covered")
      queue.enqueueAfter(b, 1.hour)
      assertEquals(queue.timersPending, 2, "each resource has its own")

      (1 to 20).foreach(_ => queue.enqueueAfter(a, 20.millis))
      eventually("the earlier pass asked for is made")(passes.contains(a))
      assertEquals(passes.asScala.count(_ == a), 1)
    }
  }

  test("problems: none while work flows; a wait or a pass over the bound is named") {
    val now     = new AtomicLong(0L)
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    withQueue(
      ref =>
        if ref == a then
          holding.countDown()
          release.await(5, TimeUnit.SECONDS): Unit,
      () => now.get()
    ) { queue =>
      queue.enqueue(a)
      assert(holding.await(5, TimeUnit.SECONDS))
      queue.enqueue(b) // the only worker holds a, so b waits
      assertEquals(queue.problems, Vector.empty)

      now.addAndGet(2.minutes.toNanos)
      val found = queue.problems
      assert(found.exists(_.contains("the pass for ankka-p/a has run 120s")), found)
      assert(found.exists(_.contains("ankka-p/b has waited 120s")), found)
      release.countDown()
      eventually("both clear once the pass ends and b is taken")(queue.problems.isEmpty)
    }
  }

  test("a stopped queue says so") {
    val queue = new WorkQueue(settings, _ => ())
    assertEquals(queue.problems, Vector("the services queue is not running"))
    queue.start()
    eventually("its worker runs")(queue.workersAlive == 1)
    assertEquals(queue.problems, Vector.empty)
    queue.stop()
    assertEquals(queue.problems, Vector("the services queue is not running"))
  }
