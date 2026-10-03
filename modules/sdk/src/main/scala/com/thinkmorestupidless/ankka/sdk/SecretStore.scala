package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.nio.charset.StandardCharsets

/**
 * Where a service keeps its service secrets: named text values it must never record, such as a
 * credential a person gave it.
 *
 * A secret store is in the service's own database and apart from everything its components know. It
 * is not an entity, a view or anything a projection reads, so nothing kept here reaches a journal,
 * a snapshot, a view's table or a backup of any of them in a readable form: the database holds each
 * value encrypted with the service's secret key.
 *
 * It is offered to endpoints, workflow steps, consumers, timed actions and agents, and not to an
 * entity or a view. A read is a blocking database call, which an entity's single-writer path must
 * not make, and a value read in an entity's handler is one line away from an event or a state.
 *
 * Every method blocks the calling thread until the database has answered. The components that are
 * given a store run on virtual threads, where a blocking call parks the thread and releases its
 * carrier. Every method throws `CommandError`: `BadRequest` for a name or a value that breaks
 * `SecretRules`, `Internal` when the service has no secret key or a stored value was encrypted with
 * another one, and `Unavailable` when the database cannot be reached.
 */
trait SecretStore:

  /** Keep `value` under `name`, replacing what was there. */
  def put(name: String, value: String): Unit

  /** The value kept under `name`, or `None` when there is none. Never an empty string. */
  def get(name: String): Option[String]

  /** Remove what is kept under `name`. Removing nothing is not an error, and needs no key. */
  def delete(name: String): Unit

object SecretStore:

  /**
   * A store that refuses every call: what a context holds where no running service stands behind
   * it, such as a route table built only to be listed.
   */
  val unavailable: SecretStore = new SecretStore:
    private def refuse() =
      throw CommandError("no secret store is available here", ErrorCode.Unavailable)
    def put(name: String, value: String): Unit = refuse()
    def get(name: String): Option[String]      = refuse()
    def delete(name: String): Unit             = refuse()

/**
 * What a service secret's name and value may be. The runtime's store and every test double apply
 * these, and `protocol/fixtures/secrets/rules.json` holds them to the other SDKs.
 */
object SecretRules:

  val MaxNameLength: Int = 253
  val MaxValueBytes: Int = 65536

  private val NameCharacter = "[A-Za-z0-9._/-]"
  private val ValidName     = s"$NameCharacter{1,$MaxNameLength}".r

  val NameRule: String =
    s"a secret's name is 1 to $MaxNameLength characters, each a letter, a digit, '.', '_', '-' or '/'"

  /** What is wrong with `name`, if anything. */
  def nameProblem(name: String): Option[String] =
    Option.when(!ValidName.matches(name)) {
      val shown = if name.length > 40 then name.take(40) + "…" else name
      s"$NameRule; \"$shown\" is not"
    }

  /** What is wrong with `value`, if anything. Never quotes the value. */
  def valueProblem(value: String): Option[String] =
    if value.isEmpty then Some("a secret's value must not be empty")
    else
      val bytes = value.getBytes(StandardCharsets.UTF_8).length
      Option.when(bytes > MaxValueBytes)(
        s"a secret's value is at most $MaxValueBytes bytes as UTF-8; this one is $bytes"
      )

  /** Refuses a name that breaks the rule. */
  def check(name: String): Unit =
    nameProblem(name).foreach(p => throw CommandError(p, ErrorCode.BadRequest))

  /** Refuses a name or a value that breaks its rule. */
  def check(name: String, value: String): Unit =
    check(name)
    valueProblem(value).foreach(p => throw CommandError(p, ErrorCode.BadRequest))
