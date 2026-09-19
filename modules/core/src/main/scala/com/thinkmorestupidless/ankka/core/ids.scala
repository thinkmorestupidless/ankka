package com.thinkmorestupidless.ankka.core

/**
 * Identifiers used throughout ankka.
 *
 * All four are `opaque type X <: String`, so they cost nothing at runtime and can be passed
 * anywhere a `String` is wanted, but cannot be constructed by accident from one.
 */

/**
 * Stable, unique name for a component *type* (not an instance). It becomes the cluster sharding
 * entity type key and the persistence-id prefix, so changing it on a deployed component orphans
 * that component's existing state — exactly as in Akka.
 */
opaque type ComponentId <: String = String

object ComponentId:
  private val Valid  = "[a-zA-Z0-9][a-zA-Z0-9._-]*".r
  private val MaxLen = 128

  /** For component declarations, where a bad id is a programming error. */
  def apply(raw: String): ComponentId =
    parse(raw).fold(msg => throw IllegalArgumentException(msg), identity)

  def parse(raw: String): Either[String, ComponentId] =
    if raw.isEmpty then Left("componentId must not be empty")
    else if raw.length > MaxLen then Left(s"componentId '$raw' exceeds $MaxLen characters")
    else if !Valid.matches(raw) then
      Left(
        s"componentId '$raw' is invalid: must start alphanumeric and contain only " +
          "letters, digits, '.', '_' or '-'"
      )
    else Right(raw)

/**
 * Identity of a single component *instance* — one shopping cart, one wallet, one user.
 *
 * `|` is reserved: Pekko uses it to separate the entity type from the entity id inside a
 * persistence id, so allowing it here would let one entity read another's journal.
 */
opaque type EntityId <: String = String

object EntityId:
  def apply(raw: String): EntityId =
    parse(raw).fold(msg => throw IllegalArgumentException(msg), identity)

  def parse(raw: String): Either[String, EntityId] =
    if raw.isEmpty then Left("entityId must not be empty")
    else if raw.contains('|') then Left(s"entityId '$raw' must not contain '|'")
    else Right(raw)

/** Groups agent interactions that share session memory. */
opaque type SessionId <: String = String

object SessionId:
  def apply(raw: String): SessionId =
    parse(raw).fold(msg => throw IllegalArgumentException(msg), identity)

  def parse(raw: String): Either[String, SessionId] =
    EntityId.parse(raw).left.map(_.replace("entityId", "sessionId"))

/**
 * Wire name of a command handler. Part of ankka's compatibility surface: a persisted timer or an
 * in-flight request names its target by `(ComponentId, MethodName)`, so renaming a handler breaks
 * them.
 */
opaque type MethodName <: String = String

object MethodName:
  def apply(raw: String): MethodName =
    parse(raw).fold(msg => throw IllegalArgumentException(msg), identity)

  def parse(raw: String): Either[String, MethodName] =
    if raw.isEmpty then Left("methodName must not be empty")
    else Right(raw)
