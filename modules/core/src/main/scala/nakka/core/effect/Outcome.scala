package nakka.core.effect

import nakka.core.{CommandError, Metadata}

/**
 * The terminal disposition of a command handler: what, if anything, the caller receives.
 *
 * `Reply` computes its value from the state *after* persisted events have been applied,
 * which is why it holds a function rather than a value — at the point the developer
 * writes `.thenReply(newState => ...)` the new state does not exist yet.
 */
sealed trait Outcome[-S, +R]

object Outcome:

  final case class Reply[-S, +R](compute: S => R, metadata: Metadata) extends Outcome[S, R]

  /** A modelled business-rule rejection. Nothing is persisted. */
  final case class Fail(error: CommandError) extends Outcome[Any, Nothing]

  /** Persist and acknowledge, but send no payload back. */
  case object NoReply extends Outcome[Any, Nothing]
