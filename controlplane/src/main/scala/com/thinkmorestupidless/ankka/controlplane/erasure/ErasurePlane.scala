package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.crd.Buckets
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, Database, RuntimeExtension}

import java.util.concurrent.atomic.AtomicReference

/**
 * The control plane's erasure machinery (feature 042), made once the service exists: the log over
 * the control plane's database and the platform bucket, and the application of requests through the
 * keyring. The erasure endpoint reads it from here; until the service has started it is none, and a
 * request asked then is applied by the sweep.
 */
final class ErasurePlane(
    keyring: => KeyringAdmin,
    /** The log's second copy; the platform bucket of the service's storage when none is given. */
    logBucket: Option[ErasureLogBucket] = None
) extends RuntimeExtension:

  private val made = AtomicReference[Option[ErasureApplication]](None)

  def name: String = "erasure"

  def application: Option[ErasureApplication] = made.get

  /**
   * The restore hold's reconciler (feature 042, R22): the log's table brought up to date from the
   * bucket before the hold is released, and the line the hold lists. Run as the control plane
   * starts held, before this extension has; a failure is listed, never thrown: whoever releases the
   * hold reads it first.
   */
  val reconciler: AnkkaService => String = service =>
    given org.apache.pekko.actor.typed.ActorSystem[?] = service.system
    given scala.concurrent.ExecutionContext           = service.system.executionContext
    try
      val bucket = logBucket.getOrElse(
        ErasureLogBucket.over(service.storage.bucket(Buckets.ErasureLog))
      )
      val back = scala.concurrent.Await.result(
        ErasureLog.reconcile(Database(), bucket),
        scala.concurrent.duration.DurationInt(60).seconds
      )
      if back.isEmpty then "the erasure log: the table holds every entry its bucket holds"
      else
        s"the erasure log: ${back.size} entr${if back.size == 1 then "y" else "ies"} " +
          s"(${back.map(_.seq).mkString(", ")}) written back from its bucket"
    catch
      case scala.util.control.NonFatal(e) =>
        s"the erasure log was not brought up to date from its bucket: ${e.getMessage}; " +
          "release the hold only once it is"

  def start(service: AnkkaService): Unit =
    given org.apache.pekko.actor.typed.ActorSystem[?] = service.system
    given scala.concurrent.ExecutionContext           = service.system.executionContext
    val bucket                                        =
      logBucket.getOrElse(ErasureLogBucket.over(service.storage.bucket(Buckets.ErasureLog)))
    made.set(
      Some(ErasureApplication(service.componentClient, ErasureLog(Database(), bucket), keyring))
    )
