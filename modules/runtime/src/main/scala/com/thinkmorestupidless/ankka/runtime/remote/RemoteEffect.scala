package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.core.{CommandError, Metadata}

/**
 * The one reduction of a process's reply to what the sidecar persists and answers.
 *
 * It applies the same `Outcome` rules as `EventSourcedEffect.materialise` and
 * `KeyValueEffect.materialise`, restated here because the in-process builders make some of them
 * unconstructable while a process can send anything:
 *
 *   - `Fail` persists nothing and leaves state untouched: a reply with an `error` outcome *and*
 *     events is a violation, not a partial write.
 *   - `NoReply` persists and answers nothing.
 *   - `Reply` persists and answers with the encoded reply.
 *
 * And the rules the wire adds: the reply must be for the command in flight, a handler discovered
 * `read_only` may not persist (which is what makes `query` unable to persist over the protocol),
 * and a snapshot arrives only when it was asked for.
 */
object RemoteEffect:

  final case class Materialised(
      events: Vector[Payload],
      newState: Option[Payload],
      retention: Option[Retention],
      reply: Either[CommandError, Option[(Payload, Metadata)]],
      snapshot: Option[Payload]
  ):
    def persisted: Boolean = events.nonEmpty || newState.nonEmpty

  def materialise(
      reply: Reply,
      handler: RemoteHandler,
      expectedCommandId: Long,
      snapshotRequested: Boolean
  ): Either[ProtocolViolation, Materialised] =
    if reply.commandId != expectedCommandId then
      Left(
        ProtocolViolation(
          s"reply for command ${reply.commandId} while command $expectedCommandId was in flight"
        )
      )
    else if handler.readOnly && (reply.events.nonEmpty || reply.newState.nonEmpty) then
      Left(ProtocolViolation(s"read-only handler '${handler.name}' replied with a state change"))
    else if reply.snapshot.nonEmpty && !snapshotRequested then
      Left(ProtocolViolation(s"handler '${handler.name}' sent a snapshot nobody asked for"))
    else
      reply.outcome match
        case RemoteOutcome.Error(error) if reply.events.nonEmpty || reply.newState.nonEmpty =>
          Left(
            ProtocolViolation(
              s"handler '${handler.name}' refused with '${error.message}' and also changed state"
            )
          )
        case RemoteOutcome.Error(error) =>
          Right(Materialised(Vector.empty, None, None, Left(error), None))
        case RemoteOutcome.NoReply =>
          Right(
            Materialised(reply.events, reply.newState, reply.retention, Right(None), reply.snapshot)
          )
        case RemoteOutcome.Reply(payload, metadata) =>
          Right(
            Materialised(
              reply.events,
              reply.newState,
              reply.retention,
              Right(Some((payload, metadata))),
              reply.snapshot
            )
          )
