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

  def start(service: AnkkaService): Unit =
    given org.apache.pekko.actor.typed.ActorSystem[?] = service.system
    given scala.concurrent.ExecutionContext           = service.system.executionContext
    val bucket                                        =
      logBucket.getOrElse(ErasureLogBucket.over(service.storage.bucket(Buckets.ErasureLog)))
    made.set(
      Some(ErasureApplication(service.componentClient, ErasureLog(Database(), bucket), keyring))
    )
