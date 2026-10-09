package com.thinkmorestupidless.ankka.runtime.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Codecs

/**
 * The channel's frames as JSON (`contracts/keyring-channel.md`): one object per text frame, its
 * kind in `type`. Shared by the service's client and the keyring, so the two cannot disagree.
 *
 * A fetch carries an `id` its answer repeats, so several may be in flight on the one channel.
 */
object ChannelWire:

  /** From a service instance to the keyring. */
  enum Out:
    case Hello(
        project: String,
        service: String,
        instance: String,
        reads: Vector[String],
        appliedUpTo: Option[Long]
    )
    case Fetch(id: Long, project: String, subject: String, create: Boolean)
    case LookupKey(id: Long, project: String)
    case Ack(erasureId: String)
    case Completed(
        erasureId: String,
        sequence: Long,
        viewsRedacted: Vector[String],
        rowsRedacted: Long,
        sessionsMarked: Int,
        instancesStopped: Int,
        handlerOk: Option[Boolean],
        handlerDetail: Option[String],
        objectsErased: Option[Long],
        objectsFinalAt: Option[Long]
    )

  /** From the keyring to a service instance. */
  enum In:
    case Key(id: Long, project: String, subject: String, key: String)
    case Erased(id: Long, project: String, subject: String, erasureId: String)
    case Unknown(id: Long, project: String, subject: String)
    case Refused(id: Long, project: String, subject: String, reason: String)
    case LookupKeyIs(id: Long, project: String, key: String)
    case Log(entries: Vector[In.Entry])
    case Destroyed(project: String, subject: String, erasureId: String)
    case Apply(erasureId: String, sequence: Long, subject: String, reapply: Boolean)
    case Close(reason: String)

  object In:
    final case class Entry(erasureId: String, sequence: Long, subject: String, destroyedAt: Long)

  given outCodec: JsonValueCodec[Out] = Codecs.make[Out]
  given inCodec: JsonValueCodec[In]   = Codecs.make[In]

  def completed(c: Completion): Out.Completed =
    Out.Completed(
      c.erasureId,
      c.sequence,
      c.duties.viewsRedacted,
      c.duties.rowsRedacted,
      c.duties.sessionsMarked,
      c.duties.instancesStopped,
      c.handler.map(_.ok),
      c.handler.map(_.detail),
      c.handler.flatMap(_.objectsErased),
      c.handler.flatMap(_.objectsFinalAt).map(_.toEpochMilli)
    )

  def write(out: Out): String    = writeToString(out)
  def readIn(text: String): In   = readFromString[In](text)
  def writeIn(in: In): String    = writeToString(in)
  def readOut(text: String): Out = readFromString[Out](text)
