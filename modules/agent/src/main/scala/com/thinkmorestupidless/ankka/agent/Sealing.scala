package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.*

/**
 * How an agent keeps a conversation about a data subject (feature 042, R23): what it persists that
 * holds conversation is written as the record's own JSON sealed under the data subject's key, a
 * personal field like any other, beside a clear copy with every personal text taken out.
 *
 * The clear copy is what the record reads as once the subject key is destroyed, so an erased task
 * or instance keeps its shape (its status, its ids, its times) and a read never fails. A session's
 * events need no clear copy: an erased conversation is an empty one.
 */
private[ankka] object Sealing:

  /** `value`'s JSON sealed under `subject`, or the erased body when the subject is erased. */
  def seal[A](subject: DataSubject, value: A, erased: Boolean)(using
      codec: JsonValueCodec[A]
  ): Personal[String] =
    if erased then Personal.Erased(subject)
    else Personal.Present(subject, writeToStringReentrant(value))

  /** `value` as clear JSON: what is kept beside the sealed body. */
  def clear[A](value: A)(using codec: JsonValueCodec[A]): String = writeToStringReentrant(value)

  /**
   * The record a sealed one holds: the whole record while its subject key lives, else `clear` read
   * back, with `true` for erased.
   */
  def open[A](body: Personal[String], clear: String)(using codec: JsonValueCodec[A]): (A, Boolean) =
    body match
      case Personal.Present(_, json) => (readFromStringReentrant(json), false)
      case Personal.Erased(_)        => (readFromStringReentrant(clear), true)

  /** The refusal a personal field's write meets once its data subject's key is destroyed. */
  def refusedAsErased(e: CommandError): Boolean =
    e.details.get(Personal.ErrorKey).contains(Personal.ErasedRefusal)

  /**
   * An event serializer for an entity that seals its events (feature 042). An event written by a
   * record still in memory when its data subject was erased meets the destroyed key; it is written
   * with an erased body instead (`erase`), which needs no key, rather than failing the record.
   */
  def eventSerializer[E](manifest0: String, erase: E => Option[E])(using
      codec: JsonValueCodec[E]
  ): Serializer[E] =
    new Serializer[E]:
      private val plain     = Serializer.json[E](manifest0)
      val manifest: String  = manifest0
      override def toString = s"Sealing.eventSerializer($manifest0)"

      def toBytes(value: E): Array[Byte] =
        try plain.toBytes(value)
        catch
          case e: CommandError if refusedAsErased(e) =>
            erase(value).fold(throw e)(plain.toBytes)

      def fromBytes(bytes: Array[Byte]): E = plain.fromBytes(bytes)

  /** A data subject as a record keeps it, refused as `BadRequest` when it is not one. */
  def subjectOf(value: String): Either[CommandError, DataSubject] =
    DataSubject.from(value).left.map(CommandError(_, ErrorCode.BadRequest))

  /** What a state is written as when it has a data subject: its clear copy and its sealed self. */
  final case class SealedState(sealedState: Int, clear: String, body: Personal[String])

  private given sealedStateCodec: JsonValueCodec[SealedState] = Codecs.make[SealedState]

  private val Marker = "{\"sealedState\":".getBytes(java.nio.charset.StandardCharsets.UTF_8)

  /**
   * A state's serializer that seals the state whole when it has a data subject, and writes it as
   * before when it has none, so a state with no subject keeps exactly the bytes it always had. A
   * reader takes either shape. An erased state is written erased: no key is asked for, and nothing
   * of the conversation is left to seal.
   */
  def stateSerializer[S](
      manifest0: String,
      subject: S => Option[String],
      isErased: S => Boolean,
      redact: S => S,
      markErased: S => S
  )(using codec: JsonValueCodec[S]): Serializer[S] =
    new Serializer[S]:
      private val plain     = Serializer.json[S](manifest0)
      val manifest: String  = manifest0
      override def toString = s"Sealing.stateSerializer($manifest0)"

      def toBytes(value: S): Array[Byte] =
        subject(value).flatMap(DataSubject.from(_).toOption) match
          case None    => plain.toBytes(value)
          case Some(s) =>
            def write(erased: Boolean) =
              PersonalScope.within(manifest0)(
                writeToArray(SealedState(1, clear(redact(value)), seal(s, value, erased)))
              )
            // A state still in memory when its subject was erased is written as what it now is.
            try write(isErased(value))
            catch case e: CommandError if refusedAsErased(e) => write(erased = true)

      def fromBytes(bytes: Array[Byte]): S =
        if !bytes.startsWith(Marker) then plain.fromBytes(bytes)
        else
          PersonalScope.within(manifest0) {
            val held          = readFromArray[SealedState](bytes)
            val (state, gone) = open[S](held.body, held.clear)
            if gone then markErased(state) else state
          }
