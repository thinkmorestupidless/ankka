package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * A minimal JSON tree.
 *
 * ankka's own payloads are all statically typed and go through derived codecs — this exists for the
 * two places where the shape genuinely is not known at compile time: emitting a tool's JSON Schema,
 * and reading the arguments a model decided to pass to that tool.
 */
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: Double)
  case Str(value: String)
  case Arr(values: Vector[Json])
  case Obj(fields: Map[String, Json])

  def apply(field: String): Option[Json] = this match
    case Obj(fields) => fields.get(field)
    case _           => None

  def asString: Option[String] = this match
    case Str(value) => Some(value)
    case _          => None

  def asDouble: Option[Double] = this match
    case Num(value) => Some(value)
    case _          => None

  def asBoolean: Option[Boolean] = this match
    case Bool(value) => Some(value)
    case _           => None

  def asArray: Option[Vector[Json]] = this match
    case Arr(values) => Some(values)
    case _           => None

  def isNull: Boolean = this == Json.Null

  /** Compact rendering, used for tool schemas and for logging model traffic. */
  def render: String = writeToString(this)(using Json.codec)

object Json:

  def obj(fields: (String, Json)*): Json = Obj(fields.toMap)
  def arr(values: Json*): Json           = Arr(values.toVector)
  def str(value: String): Json           = Str(value)
  def num(value: Double): Json           = Num(value)
  def bool(value: Boolean): Json         = Bool(value)

  def parse(text: String): Either[String, Json] =
    try Right(readFromString(text)(using codec))
    catch case failure: JsonReaderException => Left(failure.getMessage)

  /**
   * Hand-written rather than derived.
   *
   * `JsonCodecMaker` would treat this as a sealed hierarchy and add a discriminator field,
   * producing `{"type":"Obj","fields":{...}}` instead of `{...}` — which is not JSON any model or
   * schema validator would recognise.
   */
  given codec: JsonValueCodec[Json] = new JsonValueCodec[Json]:

    def nullValue: Json = Json.Null

    def decodeValue(in: JsonReader, default: Json): Json = read(in)

    def encodeValue(value: Json, out: JsonWriter): Unit = write(value, out)

    private def read(in: JsonReader): Json =
      in.nextToken() match
        case '"' =>
          in.rollbackToken()
          Str(in.readString(null))

        case 't' | 'f' =>
          in.rollbackToken()
          Bool(in.readBoolean())

        case 'n' =>
          in.readNullOrError(Json.Null, "expected null")

        case '{' =>
          val fields = Map.newBuilder[String, Json]
          if !in.isNextToken('}') then
            in.rollbackToken()
            while
              fields += (in.readKeyAsString() -> read(in))
              in.isNextToken(',')
            do ()
            if !in.isCurrentToken('}') then in.objectEndOrCommaError()
          Obj(fields.result())

        case '[' =>
          val values = Vector.newBuilder[Json]
          if !in.isNextToken(']') then
            in.rollbackToken()
            while
              values += read(in)
              in.isNextToken(',')
            do ()
            if !in.isCurrentToken(']') then in.arrayEndOrCommaError()
          Arr(values.result())

        case _ =>
          in.rollbackToken()
          Num(in.readDouble())

    private def write(value: Json, out: JsonWriter): Unit = value match
      case Null    => out.writeNull()
      case Bool(v) => out.writeVal(v)
      case Str(v)  => out.writeVal(v)

      // Whole numbers are written without a decimal point. JSON has one number type, so
      // `3` and `3.0` are equal to a parser — but a tool schema declaring
      // `"type": "integer"` rejects `3.0`, and models are sensitive to the difference in
      // arguments they are shown.
      case Num(v) =>
        if v.isWhole && v.abs <= Long.MaxValue.toDouble then out.writeVal(v.toLong)
        else out.writeVal(v)
      case Arr(items) =>
        out.writeArrayStart()
        items.foreach(write(_, out))
        out.writeArrayEnd()
      case Obj(fields) =>
        out.writeObjectStart()
        fields.foreach { (key, field) =>
          out.writeKey(key)
          write(field, out)
        }
        out.writeObjectEnd()
