package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.keyring.{KeyringWire, NoticeFrame}

import scala.util.Try

/**
 * The keyring's side of the wire (feature 042): every message is `core`'s, defined once in
 * `KeyringWire` and read by the runtime's client too; this only reads and writes them as text.
 */
object Wire:
  export KeyringWire.given

  def write[A: JsonValueCodec](value: A): String = writeToString(value)

  /** `text` as an `A`, or why it is not one. */
  def read[A: JsonValueCodec](text: String): Either[String, A] =
    Try(readFromString[A](text)).toEither.left.map(e => s"not a message: ${e.getMessage}")

  /** A text frame of the notices WebSocket. */
  def frame(text: String): Either[String, NoticeFrame] = read[NoticeFrame](text)
