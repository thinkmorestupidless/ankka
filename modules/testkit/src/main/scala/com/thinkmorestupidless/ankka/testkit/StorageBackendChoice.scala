package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.storage.{LocalBuckets, MemoryObjectStorage}
import com.thinkmorestupidless.ankka.sdk.BucketExposure

/**
 * What stands behind a test's storage client (feature 055).
 *
 * `Memory`, the default, is a store in memory the kit hands to the service: no object store, no
 * Docker, and a store of its own for every kit. It holds the buckets `buckets` names; with none
 * named, those the `service.json` beside the test declares; with neither, the unnamed bucket alone.
 * A name the service does not declare is refused as the runtime's store would refuse it.
 *
 * `FromEnvironment` lets the runtime choose from its variables and settings, as a deployed service
 * does: for a test against a real store. `variables` are laid over the JVM's environment for this
 * service alone (`ANKKA_S3_ENDPOINT` and the rest), so two kits in one JVM can reach two stores.
 */
enum StorageBackendChoice:
  case Memory(unnamed: Boolean = true, buckets: Map[String, BucketExposure] = Map.empty)
  case FromEnvironment(variables: Map[String, String] = Map.empty)

object StorageBackendChoice:

  /** The store a `Memory` choice holds, or `None` for `FromEnvironment`. */
  private[testkit] def store(choice: StorageBackendChoice): Option[MemoryObjectStorage] =
    choice match
      case Memory(unnamed, buckets) if buckets.nonEmpty =>
        Some(MemoryObjectStorage(buckets, unnamed))
      case Memory(unnamed, _) =>
        LocalBuckets.read() match
          case Right(Some(declared)) =>
            Some(MemoryObjectStorage(declared.buckets, declared.unnamed))
          case Right(None)   => Some(MemoryObjectStorage(Map.empty, unnamed))
          case Left(problem) => throw IllegalArgumentException(problem)
      case FromEnvironment(_) => None

  /**
   * The configuration a choice adds: a `FromEnvironment` choice's variables, for this service only.
   */
  private[testkit] def settings(choice: StorageBackendChoice): com.typesafe.config.Config =
    choice match
      case FromEnvironment(variables) if variables.nonEmpty =>
        import scala.jdk.CollectionConverters.*
        com.typesafe.config.ConfigFactory.parseMap(
          java.util.Map.of("ankka.storage.variables", variables.asJava)
        )
      case _ => com.typesafe.config.ConfigFactory.empty()
