package com.thinkmorestupidless.ankka.core.secrets

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs

import java.time.Instant

/**
 * The record of one read, keep or removal of a service secret: what the platform keeps so a person
 * can find out afterwards who read a secret, and when.
 *
 * It names the secret and never holds its value: there is no field a value could go in. A service
 * writes it to the control plane, over its own identity, before the value it read is returned, so a
 * value is never returned without one. The control plane keeps it apart from every service's
 * database, so a service cannot remove the record of its own reads and a restore of its database
 * does not rewind it.
 *
 * In `core` because both ends hold the one shape: the runtime writes it and the control plane's API
 * lists it.
 */
final case class ReadRecord(
    at: Instant,
    project: String,
    service: String,
    /** How the service's program is hosted: `embedded`, `process` or `module`. */
    hosting: String,
    /** The secret's name. */
    name: String,
    /** `get`, `put` or `delete`. */
    operation: String,
    /** `read`, `none`, `refused`, `unavailable`, `written` or `removed`. */
    outcome: String,
    /** `postgres` or `secret-manager`. */
    backend: String,
    traceId: Option[String] = None,
    /** The span of the handler that asked: the request, within its trace. */
    spanId: Option[String] = None,
    /** The component that asked, where it can be known: never through a process or a module. */
    component: Option[String] = None,
    componentKind: Option[String] = None,
    /** On Secret Manager: a newer version had been disabled, and was not the one read. */
    latestSkipped: Boolean = false
)

object ReadRecord:

  /** What a record's `operation` may be. */
  object Operation:
    val Get: String    = "get"
    val Put: String    = "put"
    val Delete: String = "delete"

  /** What a record's `outcome` may be. */
  object Outcome:
    val Read: String        = "read"
    val Absent: String      = "none"
    val Refused: String     = "refused"
    val Unavailable: String = "unavailable"
    val Written: String     = "written"
    val Removed: String     = "removed"

    val All: Set[String] = Set(Read, Absent, Refused, Unavailable, Written, Removed)

  given codec: JsonValueCodec[ReadRecord] = Codecs.make[ReadRecord]
