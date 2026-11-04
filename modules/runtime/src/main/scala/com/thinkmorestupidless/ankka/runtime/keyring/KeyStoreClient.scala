package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer}
import com.thinkmorestupidless.ankka.core.keyring.{KeyringAck, KeyringNotice}

/**
 * What a service's instance asks of a keyring (feature 042): the installation's, over mutual TLS
 * (`RemoteKeyStoreClient`), a developer's machine's (`LocalKeyring`), or a test kit's in memory.
 *
 * `key` blocks its caller, which is a virtual thread or a serializer call on one: a codec cannot
 * wait any other way. It throws `CommandError` with `ErrorCode.Unavailable` when the keyring cannot
 * be reached within the fetch timeout.
 */
trait KeyStoreClient:

  /** The key of `subject` in `project`, made on a write (`create`); `Destroyed` once erased. */
  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer

  /** `project`'s lookup key, which makes a view's lookup tokens (R13). */
  def lookupKey(project: String): IArray[Byte]

  /**
   * Hands every notice for this service's project to `listener` from now on, starting after
   * `after`: what was missed while the instance was not listening is handed first. Closing the
   * result stops it.
   */
  def subscribe(after: Long)(listener: KeyringNotice => Unit): AutoCloseable

  /**
   * When the keyring last confirmed the notices channel is open: a notice, or the keyring's
   * heartbeat. An instance that has heard nothing for `notice-bound` cannot know it missed no
   * erasure, and drops every key it holds. `None` until the channel has been confirmed once.
   */
  def confirmedAt(): Option[java.time.Instant]

  /** Tells the keyring this instance carried out a notice. */
  def ack(ack: KeyringAck): Unit

  /** Releases what the client holds. */
  def close(): Unit = ()
