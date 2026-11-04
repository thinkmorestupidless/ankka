package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.DataSubject

import scala.concurrent.duration.FiniteDuration

/**
 * What a service's erasure handler is given (feature 042): the service's clients and its buckets,
 * as a timed action's are, and which application of an erasure this run is.
 */
trait ErasureContext:
  /** The service's own components. */
  def client: ComponentClient

  /** The service's secret store. */
  def secrets: SecretStore

  /** Other services, called as this one. */
  def services: ServiceClients

  /** The service's buckets, where a handler erases the objects it keeps of the data subject. */
  def storage: ObjectStorage

  /** The erasure's sequence in the installation's erasure log. */
  def erasureSeq: Long

  /** Whether this run is a re-run of an erasure already applied (FR-015). */
  def rerun: Boolean

/** When what a handler erased is gone for good. */
enum Finality:
  /** Now. */
  case Immediate

  /** After `within`: a store that keeps deleted objects, a bucket with soft delete. */
  case After(within: FiniteDuration)

  /** As the keyring is told it: `immediate`, or `after:` and an ISO-8601 duration (`after:P7D`). */
  def wire: String = this match
    case Immediate     => "immediate"
    case After(within) => "after:" + java.time.Duration.ofMillis(within.toMillis).toString

/** What a run of the handler erased. */
final case class ErasureReport(objectsErased: Long, finality: Finality = Finality.Immediate)

/**
 * The service's own part of an erasure (feature 042): what the platform cannot see, such as the
 * objects it keeps in a bucket. Run once for each application of an erasure, on one instance of the
 * service, and again on each re-run, so it must do nothing the second time that it did not do the
 * first. A run that throws is recorded as failed and run again on the next pass; the platform's own
 * duties do not wait for it.
 *
 * {{{
 * Ankka.service.withErasureHandler(ErasureHandler { ctx => subject =>
 *   val removed = ctx.storage.bucket("kyc").erase(subject)
 *   ErasureReport(removed.count, removed.finality)
 * })
 * }}}
 */
final class ErasureHandler private (val run: ErasureContext => DataSubject => ErasureReport)

object ErasureHandler:
  def apply(run: ErasureContext => DataSubject => ErasureReport): ErasureHandler =
    new ErasureHandler(run)
