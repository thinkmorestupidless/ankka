package com.thinkmorestupidless.ankka.sidecar.wasm

import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

/**
 * Runs a call against an instance with a deadline. Past it, the caller is answered with a fault at
 * once and the call is abandoned: its instance is marked broken and never used again, and the
 * thread it is on — a virtual one — is left to finish or not, logged as lost. A guest cannot be
 * interrupted, so waiting for it would hand its hang to every later caller.
 */
private object Deadline:
  private val log = LoggerFactory.getLogger(getClass)

  def run[A](instance: GuestInstance, what: String, timeout: FiniteDuration)(
      f: GuestInstance => Either[GuestFault, A]
  ): Either[GuestFault, A] =
    val call = Future(f(instance))(using AnkkaExecutors.virtual)
    try Await.result(call, timeout)
    catch
      case _: TimeoutException =>
        val fault = GuestFault(what, s"no reply from the module within $timeout")
        instance.markBroken(fault)
        log.warn(
          "{}: abandoned after {}; its instance is discarded and its thread lost",
          what,
          timeout
        )
        Left(fault)
      case e: InterruptedException => throw e
      case NonFatal(e) =>
        val fault = GuestFault(what, Option(e.getMessage).getOrElse(e.toString))
        instance.markBroken(fault)
        Left(fault)

/**
 * The instances that serve commands: `size` of them, built once and reused, each serving one call
 * at a time. A stateless call takes whichever is free; a stateful component's instance is pinned to
 * one slot by its key, because that guest instance is what holds its state. A broken instance is
 * replaced when its call returns, and the replacement holds nobody's state: every key that was
 * resident in the old one is forgotten, so the next call for it hands the state again.
 */
final class CommandPool(size: Int, build: () => GuestInstance, timeout: FiniteDuration):
  require(size >= 1, "the command pool needs at least one instance")

  private final class Slot(var instance: GuestInstance):
    val lock                          = new ReentrantLock()
    val resident: mutable.Set[String] = mutable.Set.empty

  private val slots = Vector.fill(size)(Slot(build()))
  private val next  = AtomicInteger(0)

  /** Any free instance; if none is free, waits for one in turn. */
  def withAny[A](what: String)(f: GuestInstance => Either[GuestFault, A]): Either[GuestFault, A] =
    val start = Math.floorMod(next.getAndIncrement(), size)
    val free = (0 until size).iterator
      .map(i => slots((start + i) % size))
      .find(_.lock.tryLock())
    val slot = free.getOrElse {
      val s = slots(start)
      s.lock.lock()
      s
    }
    try Deadline.run(slot.instance, what, timeout)(f)
    finally release(slot)

  /**
   * The instance `key` is pinned to, waiting while it is busy. `f` is told whether `key` is
   * resident in it — whether this instance already holds the key's state — and on a reply the key
   * is resident.
   */
  def withPinned[A](key: String, what: String)(
      f: (GuestInstance, Boolean) => Either[GuestFault, A]
  ): Either[GuestFault, A] =
    val slot = slots(slotOf(key))
    slot.lock.lock()
    try
      val result = Deadline.run(slot.instance, what, timeout)(f(_, slot.resident.contains(key)))
      if result.isRight && !slot.instance.broken then slot.resident += key
      result
    finally release(slot)

  /**
   * Lets go of `key`: if its instance holds its state, `f` tells it to drop it (the stateful
   * shape's `close`); either way the key is no longer resident.
   */
  def release(key: String, what: String)(f: GuestInstance => Either[GuestFault, Unit]): Unit =
    val slot = slots(slotOf(key))
    slot.lock.lock()
    try
      if slot.resident.contains(key) then Deadline.run(slot.instance, what, timeout)(f): Unit
      slot.resident -= key
    finally release(slot)

  /** `key`'s state is no longer held: the next call hands it again. */
  def evict(key: String): Unit =
    val slot = slots(slotOf(key))
    slot.lock.lock()
    try slot.resident -= key
    finally slot.lock.unlock()

  def slotOf(key: String): Int = Math.floorMod(key.hashCode, size)

  /** Whether `key`'s pinned instance holds its state now. */
  def isResident(key: String): Boolean =
    val slot = slots(slotOf(key))
    slot.lock.lock()
    try slot.resident.contains(key)
    finally slot.lock.unlock()

  private def release(slot: Slot): Unit =
    try
      if slot.instance.broken then
        slot.resident.clear()
        slot.instance = build()
    finally slot.lock.unlock()

/**
 * The instances for work that may wait — a workflow step, an agent's tool, a view, a consumer, a
 * timed action, an HTTP route: a fresh one per call, discarded after it. Nothing a slow call does
 * holds an instance a command needs, and an instance costs tens of microseconds to build.
 */
final class BlockingPool(build: () => GuestInstance):
  def withFresh[A](what: String, timeout: FiniteDuration)(
      f: GuestInstance => Either[GuestFault, A]
  ): Either[GuestFault, A] =
    val instance =
      try Right(build())
      catch case NonFatal(e) => Left(GuestFault(what, s"could not build an instance: $e"))
    instance.flatMap(i => Deadline.run(i, what, timeout)(f))
