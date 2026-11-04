package com.thinkmorestupidless.ankka.core.keyring

/**
 * What the keyring and a service's instance tell each other about an erasure (feature 042), as
 * `contracts/keyring-and-protocol.md` gives them. In `core`, with no Pekko, so the keyring's server
 * and the runtime's client read and write one definition.
 */

/** An erasure the keyring applied: every instance of every service of `project` carries it out. */
final case class KeyringNotice(seq: Long, project: String, subject: String, rerun: Boolean)

/** What an erasure handler reported: how many objects it erased, and when that is final. */
final case class HandlerReport(objectsErased: Long, finality: String)

object HandlerReport:
  /** The objects are gone now. */
  val Immediate: String = "immediate"

/** What one instance did for a notice. */
final case class Completion(
    rowsRedacted: Long,
    tokensDropped: Long,
    sessionsEnded: Long,
    handler: Option[HandlerReport] = None
)

object Completion:
  val nothing: Completion = Completion(0, 0, 0, None)

/** An instance's acknowledgement that it carried out notice `seq`. */
final case class KeyringAck(seq: Long, service: String, instance: String, completion: Completion)
