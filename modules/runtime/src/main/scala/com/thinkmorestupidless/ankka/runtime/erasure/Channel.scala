package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.core.personal.KeyResult

import java.time.Instant

/**
 * The messages between a service instance and the keyring (`contracts/keyring-channel.md`), as
 * values. The deployed service speaks them over one WebSocket (`KeyringClient`); the test kit's
 * keyring answers them in memory. `ErasureRuntime` sees only `KeyringConnection`, so what it does
 * with them is the same in both.
 */
final case class Hello(
    project: String,
    service: String,
    instance: String,
    reads: Set[String],
    appliedUpTo: Option[Long]
)

/** One applied erasure in a project's erasure log, in the order the keyring applied them. */
final case class LogEntry(erasureId: String, sequence: Long, subject: String, destroyedAt: Instant)

/** What the keyring asks of a service: apply an erasure to its own tables. */
final case class ErasureOrder(erasureId: String, sequence: Long, subject: String, reapply: Boolean)

final case class Duties(
    keyDropped: Boolean,
    viewsRedacted: Vector[String],
    rowsRedacted: Long,
    sessionsMarked: Int,
    instancesStopped: Int
)

final case class HandlerOutcome(
    ok: Boolean,
    detail: String,
    objectsErased: Option[Long],
    objectsFinalAt: Option[Instant]
)

final case class Completion(
    erasureId: String,
    sequence: Long,
    duties: Duties,
    handler: Option[HandlerOutcome]
)

/** What the keyring tells a service, besides answering its fetches. */
trait KeyringListener:
  /** Every erasure of the project after the service's `appliedUpTo`, answering `hello`. */
  def log(entries: Vector[LogEntry]): Unit

  /** A key destroyed: drop it at once, then `ack`. */
  def destroyed(project: String, subject: String, erasureId: String): Unit

  /** Apply an erasure to this service's tables, then answer `completed`. */
  def apply(order: ErasureOrder): Unit

  /** The keyring closed the channel; `unacknowledged` means the whole cache must go. */
  def closed(reason: String): Unit

/**
 * A service instance's line to the keyring. Every method may block; `fetch` and `lookupKey` throw a
 * `CommandError(Unavailable)` when the keyring cannot be reached.
 */
trait KeyringConnection:
  def fetch(project: String, subject: String, create: Boolean): KeyResult
  def lookupKey(project: String): Array[Byte]
  def open(hello: Hello, listener: KeyringListener): Unit
  def ack(erasureId: String): Unit
  def completed(completion: Completion): Unit
  def close(): Unit
