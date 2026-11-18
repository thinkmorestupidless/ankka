package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Done
import com.thinkmorestupidless.ankka.core.shape.ShapeOf
import com.thinkmorestupidless.ankka.http.openapi.AnswerKind

/**
 * Converts a raw string parameter into a typed value.
 *
 * Used for both path segments and query parameters — the job is identical, so they share one set of
 * instances rather than two that could drift apart.
 */
trait FromPath[A]:
  def name: String
  def parse(raw: String): Either[String, A]

object FromPath:

  private def simple[A](typeName: String, f: String => A): FromPath[A] = new FromPath[A]:
    val name                                  = typeName
    def parse(raw: String): Either[String, A] =
      try Right(f(raw))
      catch
        case _: IllegalArgumentException | _: NumberFormatException =>
          Left(s"'$raw' is not a valid $typeName")

  given string: FromPath[String]       = simple("string", identity)
  given int: FromPath[Int]             = simple("int", _.toInt)
  given long: FromPath[Long]           = simple("long", _.toLong)
  given boolean: FromPath[Boolean]     = simple("boolean", _.toBoolean)
  given uuid: FromPath[java.util.UUID] = simple("uuid", java.util.UUID.fromString)

/** Reads a request body into a typed handler argument. */
trait FromBody[A]:
  def contentType: String
  def read(bytes: Array[Byte]): Either[String, A]

  /** The JSON shape of what `read` accepts, when it is known (feature 067). */
  def shape: Option[ShapeOf[?]] = None

object FromBody:

  /**
   * Any type with a jsoniter codec in scope can be a request body; its shape is derived when the
   * type has one (feature 067), and a type with none makes its route incomplete, never a compile
   * error.
   */
  given json[A](using codec: JsonValueCodec[A], derived: ShapeOf.Maybe[A]): FromBody[A] =
    new FromBody[A]:
      val contentType                                 = "application/json"
      def read(bytes: Array[Byte]): Either[String, A] =
        try Right(readFromArray(bytes)(using codec))
        catch
          case failure: JsonReaderException => Left(s"malformed JSON body: ${failure.getMessage}")
      override val shape: Option[ShapeOf[?]] = derived.found

  given text: FromBody[String] = new FromBody[String]:
    val contentType                        = "text/plain"
    def read(bytes: Array[Byte])           = Right(String(bytes, "UTF-8"))
    override val shape: Option[ShapeOf[?]] = Some(summon[ShapeOf[String]])

/** Renders a handler's return value as an HTTP response. */
trait ToResponse[A]:
  def status(value: A): Int
  def contentType: String
  def write(value: A): Array[Byte]

  /**
   * Headers beside the content type — a redirect's `Location`, a session's `Set-Cookie`. None by
   * default; `Respond` is how a handler supplies them. `Content-Type` never goes here: it is
   * `contentType`, and the server sets it from the entity.
   */
  def headers(@scala.annotation.unused value: A): Vector[(String, String)] = Vector.empty

  /** The JSON shape of what `write` writes, when it is known (feature 067). */
  def shape: Option[ShapeOf[?]] = None

  /** The status every value is answered with; `None` when the value decides it (feature 067). */
  def fixedStatus: Option[Int] = None

  /** Whether a value is answered as a body, as nothing, or as a stream (feature 067). */
  private[ankka] def kind: AnswerKind = AnswerKind.Body

object ToResponse:

  /**
   * Any type with a jsoniter codec in scope can be a response body; its shape is derived when the
   * type has one (feature 067).
   */
  given json[A](using codec: JsonValueCodec[A], derived: ShapeOf.Maybe[A]): ToResponse[A] =
    new ToResponse[A]:
      def status(value: A)                   = 200
      val contentType                        = "application/json"
      def write(value: A)                    = writeToArray(value)(using codec)
      override val shape: Option[ShapeOf[?]] = derived.found
      override val fixedStatus: Option[Int]  = Some(200)

  given text: ToResponse[String] = new ToResponse[String]:
    def status(value: String)              = 200
    val contentType                        = "text/plain; charset=UTF-8"
    def write(value: String)               = value.getBytes("UTF-8")
    override val shape: Option[ShapeOf[?]] = Some(summon[ShapeOf[String]])
    override val fixedStatus: Option[Int]  = Some(200)

  /**
   * Scalars, rendered as bare JSON values.
   *
   * jsoniter derives no codecs for primitives, and `/carts/{id}/total` returning an `Int` is too
   * ordinary to require the caller to declare one. Being concrete rather than generic also means
   * these take precedence over `json[A]`.
   */
  private def scalar[A](render: A => String)(using of: ShapeOf[A]): ToResponse[A] =
    new ToResponse[A]:
      def status(value: A)                   = 200
      val contentType                        = "application/json"
      def write(value: A)                    = render(value).getBytes("UTF-8")
      override val shape: Option[ShapeOf[?]] = Some(of)
      override val fixedStatus: Option[Int]  = Some(200)

  given int: ToResponse[Int]         = scalar(_.toString)
  given long: ToResponse[Long]       = scalar(_.toString)
  given double: ToResponse[Double]   = scalar(_.toString)
  given boolean: ToResponse[Boolean] = scalar(_.toString)

  /** `Done` means "it worked and there is nothing to say" — 204, not an empty 200. */
  given done: ToResponse[Done] = new ToResponse[Done]:
    def status(value: Done)                      = 204
    val contentType                              = "text/plain"
    def write(value: Done)                       = Array.emptyByteArray
    override val fixedStatus: Option[Int]        = Some(204)
    override private[ankka] def kind: AnswerKind = AnswerKind.Nothing

  given unit: ToResponse[Unit] = new ToResponse[Unit]:
    def status(value: Unit)                      = 204
    val contentType                              = "text/plain"
    def write(value: Unit)                       = Array.emptyByteArray
    override val fixedStatus: Option[Int]        = Some(204)
    override private[ankka] def kind: AnswerKind = AnswerKind.Nothing

/** Query parameters are parsed by the same instances as path segments. */
type FromQuery[A] = FromPath[A]
