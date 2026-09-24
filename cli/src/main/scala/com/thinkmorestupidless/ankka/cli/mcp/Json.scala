package com.thinkmorestupidless.ankka.cli.mcp

import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * A JSON tree, for the one place the CLI handles JSON whose shape it does not know in advance: the
 * Model Context Protocol, where a request's parameters and a tool's arguments are whatever the
 * client sent.
 *
 * The CLI's own wire types go through derived codecs, as everywhere else in ankka. The agent module
 * has a tree of the same shape, but the CLI depends on `controlplane-api` alone — no actor system,
 * no model provider — and a tree is not a reason to change that.
 */
private[cli] enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(values: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

  def apply(field: String): Option[Json] = this match
    case Obj(fields) => fields.collectFirst { case (`field`, value) => value }
    case _           => None

  def string(field: String): Option[String] = apply(field).collect { case Str(v) => v }

  def int(field: String): Option[Int] = apply(field).collect {
    case Num(v) if v.isValidInt            => v.toInt
    case Str(v) if v.toIntOption.isDefined => v.toInt
  }

  def bool(field: String): Option[Boolean] = apply(field).collect {
    case Bool(v)      => v
    case Str("true")  => true
    case Str("false") => false
  }

  def render: String = writeToString(this)(using Json.codec)

  def pretty: String = writeToString(this, WriterConfig.withIndentionStep(2))(using Json.codec)

private[cli] object Json:

  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)
  def arr(values: Json*): Json           = Arr(values.toVector)
  def str(value: String): Json           = Str(value)
  def num(value: Int): Json              = Num(BigDecimal(value))
  def bool(value: Boolean): Json         = Bool(value)

  def parse(text: String): Either[String, Json] =
    try Right(readFromString(text)(using codec))
    catch case failure: JsonReaderException => Left(failure.getMessage)

  /**
   * Hand-written: a derived codec would add a discriminator and write `{"type":"Obj",...}`.
   *
   * Numbers are `BigDecimal` so a request id is echoed exactly as it arrived, and object fields
   * keep their order so a schema reads the way it was written.
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
          val fields = Vector.newBuilder[(String, Json)]
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
          Num(in.readBigDecimal(null))

    private def write(value: Json, out: JsonWriter): Unit = value match
      case Null    => out.writeNull()
      case Bool(v) => out.writeVal(v)
      case Str(v)  => out.writeVal(v)
      case Num(v)  => out.writeVal(v)
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
