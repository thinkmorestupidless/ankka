package com.thinkmorestupidless.ankka.sdk

import java.time.Instant

/**
 * What a service does of its own when a data subject of its project is erased (feature 042).
 *
 * The platform does everything it can see by itself before the handler runs: the subject's key is
 * dropped from every cache, its view rows are redacted and their lookup tokens removed, its agent
 * sessions are forgotten. The handler is for what the platform cannot see — chiefly the subject's
 * objects in the service's bucket, which any S3 client writes directly.
 *
 * It is run on every application of an erasure in the project, and again on each later one, so a
 * late write is removed on the next pass; it must therefore be safe to run again. What the platform
 * does of its own does not wait for it, and a handler that throws or times out is run again on the
 * next application.
 */
type ErasureHandler = ErasureContext => ErasureOutcome

trait ErasureContext:
  /** The data subject being erased. */
  def subject: String

  /** The erasure request this application is for. */
  def erasureId: String

  /** Whether the handler has run for this erasure before. */
  def reapply: Boolean

  /** The subject's objects in this service's bucket. */
  def objects: ObjectErasure

  def services: ServiceClients
  def secrets: SecretStore

enum ErasureOutcome:
  /** Done; `detail` is recorded with the completion and shown on the certificate. */
  case Done(detail: String = "", objects: Option[ErasedObjects] = None)

  /** Not done; run again on the next application. */
  case Failed(reason: String)

/** How many objects an erasure deleted, and when their deletion is final on the object store. */
final case class ErasedObjects(count: Long, finalAt: Instant)

/**
 * One call that erases a data subject's objects whatever the object store keeps: every object under
 * the subject's prefix, `subjects/<subject>/`, every version where the store keeps versions. An
 * object stored outside the prefix is not erased by it.
 */
trait ObjectErasure:
  def erase(): ErasedObjects

object ObjectErasure:
  /** The prefix a service keeps a data subject's objects under. */
  def prefix(subject: String): String = s"subjects/$subject/"

  /** For a service with no bucket: every call refused, naming the missing bucket. */
  val noBucket: ObjectErasure = () =>
    throw com.thinkmorestupidless.ankka.core.CommandError(
      "this service has no bucket: it cannot erase objects (provisionObjectStorage is not set)",
      com.thinkmorestupidless.ankka.core.ErrorCode.BadRequest
    )
