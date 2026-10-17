package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * The status document's codec on every side but the operator's (which reads it with Jackson, held
 * to the same fixture). Every field is written, defaults and empty lists included, so a reader that
 * has no notion of this side's defaults reads what was meant.
 */
object ModelServerDocumentJson:

  given DocumentCodec: JsonValueCodec[ModelServerDocument] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false).withTransientEmpty(false))

  def write(document: ModelServerDocument): String = writeToString(document)

  /** The document, or why it is not one; a model in a state nobody knows is refused by name. */
  def read(text: String): Either[String, ModelServerDocument] =
    try
      val document = readFromString[ModelServerDocument](text)
      document.models.find(m => !ModelState.All.contains(m.state)) match
        case Some(unknown) =>
          Left(s"model \"${unknown.name}\" is in an unknown state \"${unknown.state}\"")
        case None => Right(document)
    catch case e: JsonReaderException => Left(s"not a model server document: ${e.getMessage}")
