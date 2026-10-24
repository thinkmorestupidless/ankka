package com.thinkmorestupidless.ankka.mcp

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.agent.{Json, SchemaType}
import com.thinkmorestupidless.ankka.agent.autonomous.JsonSchema

import scala.deriving.Mirror
import scala.util.control.NonFatal

/**
 * How a referenced command's or query's input is read from a tool's arguments (feature 054): the
 * properties it adds to the tool's schema beside the entity's id, and the decode.
 *
 * A case class's fields are properties of their own. A scalar has no field name to give, so there
 * is deliberately no instance for one: a scalar input is named with the `input` overload of
 * `command` and `query`, which builds `ToolInput.named`.
 */
trait ToolInput[I]:
  /** The properties this input adds, in order. */
  def schema: Vector[(String, Json)]

  /** The properties of `schema` a client must send. */
  def required: Vector[String]

  /** The input, from the arguments the client sent (the id's property among them, ignored). */
  def decode(arguments: Json.Obj): Either[String, I]

object ToolInput:

  /**
   * A case class: its fields are the tool's properties, and its codec reads them — the one in
   * scope, or one derived as `Codecs.make` derives it, since a component usually holds only a
   * `Serializer`.
   */
  inline given derived[A](using m: Mirror.ProductOf[A]): ToolInput[A] =
    product(
      JsonSchema.derived[A],
      scala.compiletime.summonFrom {
        case codec: JsonValueCodec[A] => codec
        case _                        => com.thinkmorestupidless.ankka.core.Codecs.make[A]
      }
    )

  private def product[A](described: JsonSchema[A], codec: JsonValueCodec[A]): ToolInput[A] =
    val (properties, needed) = described.schema match
      case Json.Obj(fields) =>
        val props = fields.get("properties") match
          case Some(Json.Obj(ps)) => ps.toVector.sortBy(_._1)
          case _                  => Vector.empty
        val req = fields.get("required").flatMap(_.asArray).getOrElse(Vector.empty)
        (props, req.flatMap(_.asString))
      case _ => (Vector.empty, Vector.empty)
    new ToolInput[A]:
      val schema: Vector[(String, Json)]                 = properties
      val required: Vector[String]                       = needed
      def decode(arguments: Json.Obj): Either[String, A] =
        val names = properties.map(_._1).toSet
        val own   = arguments.fields.filter((name, _) => names.contains(name))
        needed.find(name => !own.contains(name)) match
          case Some(missing) => Left(s"argument '$missing' is required")
          case None          =>
            try Right(readFromString(Json.Obj(own).render)(using codec))
            catch
              case NonFatal(failure) =>
                Left(Option(failure.getMessage).map(_.linesIterator.next()).getOrElse("invalid"))

  /** A scalar input under the property `name`, read by its `SchemaType`. */
  def named[A](name: String)(using schemaType: SchemaType[A]): ToolInput[A] =
    new ToolInput[A]:
      val schema: Vector[(String, Json)] = Vector(name -> schemaType.schema)
      val required: Vector[String] = if schemaType.required then Vector(name) else Vector.empty
      def decode(arguments: Json.Obj): Either[String, A] =
        schemaType.decode(arguments(name)).left.map(reason => s"parameter '$name': $reason")
