package com.thinkmorestupidless.ankka.runtime

import munit.FunSuite

import scala.concurrent.duration.*
import scala.concurrent.{Await, Promise}

/**
 * A `main` that throws after `start()` ends the service, so the process exits rather than idling
 * with its ports bound (issue #108).
 */
final class StarterDiesSuite extends FunSuite:

  override val munitTimeout = 2.minutes

  test(
    "a service whose starting thread dies of an exception terminates, and the thread's own handler still runs"
  ) {
    val started = Promise[AnkkaService]()
    val seen    = Promise[Throwable]()
    // An explicit Runnable: a lambda whose body ends in `throw` is typed Nothing, which the lambda
    // bootstrap refuses.
    val thread = new Thread(
      new Runnable:
        def run(): Unit =
          val service = Ankka.service.start("starter-dies-suite", ClusterConfig.load())
          started.success(service)
          throw IllegalStateException("main failed after start")
    )
    thread.setUncaughtExceptionHandler((_, failure) => seen.success(failure): Unit)
    thread.start()
    thread.join(60.seconds.toMillis)
    val service = Await.result(started.future, 30.seconds)
    Await.result(service.whenTerminated, 30.seconds): Unit
    assertEquals(Await.result(seen.future, 5.seconds).getMessage, "main failed after start")
  }

  test("a starting thread that returns normally leaves the service running") {
    val started = Promise[AnkkaService]()
    val thread  = new Thread(
      new Runnable:
        def run(): Unit =
          started.success(Ankka.service.start("starter-lives-suite", ClusterConfig.load())): Unit
    )
    thread.start()
    thread.join(60.seconds.toMillis)
    val service = Await.result(started.future, 30.seconds)
    try assert(!service.whenTerminated.isCompleted)
    finally service.terminate()
  }
