package nakka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}

/**
 * How a tool parameter is described to the model, and how its value is read back.
 *
 * Schema *and* decoder in one instance on purpose: they have to agree, and keeping them apart is
 * how you end up telling a model a field is an integer and then failing to parse the integer it
 * sends.
 */
trait SchemaType[A]:
  /** JSON Schema fragment for this parameter. */
  def schema: Json

  /** Whether the model must supply it. */
  def required: Boolean = true

  /** Reads the value the model supplied, or explains why it could not be read. */
  def decode(value: Option[Json]): Either[String, A]

object SchemaType:

  private def scalar[A](typeName: String)(read: Json => Option[A]): SchemaType[A] =
    new SchemaType[A]:
      val schema: Json = Json.obj("type" -> Json.str(typeName))
      def decode(value: Option[Json]): Either[String, A] = value match
        case None => Left(s"missing required value")
        case Some(json) =>
          read(json).toRight(s"expected $typeName but got ${json.render}")

  given string: SchemaType[String] = scalar("string")(_.asString)

  given boolean: SchemaType[Boolean] = scalar("boolean")(_.asBoolean)

  given double: SchemaType[Double] = scalar("number")(_.asDouble)

  /**
   * `integer`, not `number`.
   *
   * Models honour the distinction, and declaring `number` then rejecting `2.5` would be nakka's
   * fault rather than the model's.
   */
  given int: SchemaType[Int] =
    new SchemaType[Int]:
      val schema: Json = Json.obj("type" -> Json.str("integer"))
      def decode(value: Option[Json]): Either[String, Int] = value match
        case None => Left("missing required value")
        case Some(json) =>
          json.asDouble match
            case Some(d) if d.isWhole => Right(d.toInt)
            case Some(d)              => Left(s"expected an integer but got $d")
            case None                 => Left(s"expected an integer but got ${json.render}")

  given long: SchemaType[Long] =
    new SchemaType[Long]:
      val schema: Json = Json.obj("type" -> Json.str("integer"))
      def decode(value: Option[Json]): Either[String, Long] = value match
        case None => Left("missing required value")
        case Some(json) =>
          json.asDouble match
            case Some(d) if d.isWhole => Right(d.toLong)
            case Some(d)              => Left(s"expected an integer but got $d")
            case None                 => Left(s"expected an integer but got ${json.render}")

  /** An optional parameter: same schema, but absent from `required`. */
  given option[A](using inner: SchemaType[A]): SchemaType[Option[A]] =
    new SchemaType[Option[A]]:
      val schema: Json               = inner.schema
      override val required: Boolean = false
      def decode(value: Option[Json]): Either[String, Option[A]] = value match
        case None                      => Right(None)
        case Some(json) if json.isNull => Right(None)
        case some                      => inner.decode(some).map(Some(_))

  given list[A](using inner: SchemaType[A]): SchemaType[List[A]] =
    new SchemaType[List[A]]:
      val schema: Json =
        Json.obj("type" -> Json.str("array"), "items" -> inner.schema)
      def decode(value: Option[Json]): Either[String, List[A]] = value match
        case None => Left("missing required value")
        case Some(json) =>
          json.asArray match
            case None => Left(s"expected an array but got ${json.render}")
            case Some(items) =>
              items.foldLeft[Either[String, List[A]]](Right(Nil)) { (acc, item) =>
                for
                  soFar   <- acc
                  decoded <- inner.decode(Some(item))
                yield soFar :+ decoded
              }

/** How a tool's return value is rendered for the model. */
trait ToolOutput[A]:
  def render(value: A): String

object ToolOutput:

  given string: ToolOutput[String] = identity(_)

  given int: ToolOutput[Int]         = _.toString
  given long: ToolOutput[Long]       = _.toString
  given double: ToolOutput[Double]   = _.toString
  given boolean: ToolOutput[Boolean] = _.toString

  given json: ToolOutput[Json] = _.render

  /**
   * Anything with a jsoniter codec, rendered as JSON.
   *
   * Lower priority than the scalar instances above so a `String` result is passed through as prose
   * rather than being wrapped in quotes.
   */
  given encoded[A](using codec: JsonValueCodec[A]): ToolOutput[A] =
    value => writeToString(value)(using codec)
