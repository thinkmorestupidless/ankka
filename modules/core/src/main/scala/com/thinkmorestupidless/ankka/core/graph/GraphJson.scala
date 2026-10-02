package com.thinkmorestupidless.ankka.core.graph

import com.github.plokhotnyuk.jsoniter_scala.core.*

/**
 * JSON as a tree, for the one value in `core` whose shape is not fixed: a delta's properties are
 * whatever its author gave them. Numbers are kept exact, so an integer past 2⁵³ or past 64 bits is
 * seen for what it is.
 */
private[ankka] enum GraphJson:
  case Str(value: String)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Null
  case Arr(items: Vector[GraphJson])
  case Obj(fields: Vector[(String, GraphJson)])

  def field(name: String): Option[GraphJson] = this match
    case Obj(fields) => fields.collectFirst { case (`name`, value) => value }
    case _           => None

private[ankka] object GraphJson:

  /** `Left` is why the bytes are not JSON. */
  def parse(bytes: Array[Byte]): Either[String, GraphJson] =
    try Right(readFromArray[GraphJson](bytes))
    catch case failure: JsonReaderException => Left(failure.getMessage)

  given codec: JsonValueCodec[GraphJson] = new JsonValueCodec[GraphJson]:
    def nullValue: GraphJson = GraphJson.Null

    def decodeValue(in: JsonReader, default: GraphJson): GraphJson =
      val token = in.nextToken()
      if token == '"' then
        in.rollbackToken()
        Str(in.readString(null))
      else if token == 't' || token == 'f' then
        in.rollbackToken()
        Bool(in.readBoolean())
      else if token == 'n' then in.readNullOrError(Null, "expected a JSON value")
      else if (token >= '0' && token <= '9') || token == '-' then
        in.rollbackToken()
        Num(in.readBigDecimal(null))
      else if token == '[' then
        if in.isNextToken(']') then Arr(Vector.empty)
        else
          in.rollbackToken()
          val items = Vector.newBuilder[GraphJson]
          while
            items += decodeValue(in, default)
            in.isNextToken(',')
          do ()
          if in.isCurrentToken(']') then Arr(items.result()) else in.arrayEndOrCommaError()
      else if token == '{' then
        if in.isNextToken('}') then Obj(Vector.empty)
        else
          in.rollbackToken()
          val fields = Vector.newBuilder[(String, GraphJson)]
          while
            val name = in.readKeyAsString()
            fields += (name -> decodeValue(in, default))
            in.isNextToken(',')
          do ()
          if in.isCurrentToken('}') then Obj(fields.result()) else in.objectEndOrCommaError()
      else in.decodeError("expected a JSON value")

    def encodeValue(value: GraphJson, out: JsonWriter): Unit = value match
      case Str(text)   => out.writeVal(text)
      case Num(number) => out.writeVal(number)
      case Bool(flag)  => out.writeVal(flag)
      case Null        => out.writeNull()
      case Arr(items) =>
        out.writeArrayStart()
        items.foreach(encodeValue(_, out))
        out.writeArrayEnd()
      case Obj(fields) =>
        out.writeObjectStart()
        fields.foreach { (name, field) =>
          out.writeKey(name)
          encodeValue(field, out)
        }
        out.writeObjectEnd()
