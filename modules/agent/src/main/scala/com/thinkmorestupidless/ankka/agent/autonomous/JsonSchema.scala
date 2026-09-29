package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.Json

import scala.annotation.implicitNotFound
import scala.compiletime.{constValueTuple, erasedValue, summonInline}
import scala.deriving.Mirror

/**
 * How a value's shape is described to a model, as JSON Schema.
 *
 * Description only. A task's result is *decoded* with its jsoniter codec, which is the truth; this
 * is what the model is shown so it knows what to send. Both come from the same case class, so they
 * describe the same thing, and a value the model sends that the codec refuses goes back to it as
 * the error of the tool it called.
 *
 * Products are derived with `JsonSchema.derived`. Sum types are not: a sealed trait needs a
 * discriminator the model has to be told about, and that belongs in a later version rather than in
 * a guess.
 */
@implicitNotFound(
  "no JsonSchema for ${A}. Case classes derive one with `given JsonSchema[${A}] = " +
    "JsonSchema.derived`; sealed traits and enums are not supported in a task's result."
)
trait JsonSchema[A]:
  def schema: Json

  /** Whether an object holding this as a field must supply it. */
  def required: Boolean = true

object JsonSchema:

  def apply[A](using s: JsonSchema[A]): JsonSchema[A] = s

  private def of[A](typeName: String): JsonSchema[A] =
    new JsonSchema[A]:
      val schema: Json = Json.obj("type" -> Json.str(typeName))

  given string: JsonSchema[String]   = of("string")
  given boolean: JsonSchema[Boolean] = of("boolean")
  given double: JsonSchema[Double]   = of("number")

  /** `integer`, not `number`: models honour the difference. */
  given int: JsonSchema[Int]   = of("integer")
  given long: JsonSchema[Long] = of("integer")

  given option[A](using inner: JsonSchema[A]): JsonSchema[Option[A]] =
    new JsonSchema[Option[A]]:
      val schema: Json               = inner.schema
      override val required: Boolean = false

  private def array[A](inner: JsonSchema[A]): Json =
    Json.obj("type" -> Json.str("array"), "items" -> inner.schema)

  given list[A](using inner: JsonSchema[A]): JsonSchema[List[A]] =
    new JsonSchema[List[A]]:
      val schema: Json = array(inner)

  given vector[A](using inner: JsonSchema[A]): JsonSchema[Vector[A]] =
    new JsonSchema[Vector[A]]:
      val schema: Json = array(inner)

  given map[A](using inner: JsonSchema[A]): JsonSchema[Map[String, A]] =
    new JsonSchema[Map[String, A]]:
      val schema: Json =
        Json.obj("type" -> Json.str("object"), "additionalProperties" -> inner.schema)

  /**
   * The schema of a case class: an object whose properties are its fields, each described by its
   * own `JsonSchema`, with every field that is not an `Option` required.
   */
  inline def derived[A](using m: Mirror.ProductOf[A]): JsonSchema[A] =
    product[A](
      constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]],
      elementSchemas[m.MirroredElemTypes]
    )

  /** Kept out of the inline method, so each derivation site does not get its own class. */
  private def product[A](labels: List[String], schemas: List[JsonSchema[?]]): JsonSchema[A] =
    val fields = labels.zip(schemas)
    new JsonSchema[A]:
      val schema: Json = Json.obj(
        "type"       -> Json.str("object"),
        "properties" -> Json.Obj(fields.map((name, s) => name -> s.schema).toMap),
        "required" -> Json.Arr(
          fields.collect { case (name, s) if s.required => Json.str(name) }.toVector
        ),
        "additionalProperties" -> Json.bool(false)
      )

  private inline def elementSchemas[T <: Tuple]: List[JsonSchema[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[JsonSchema[h]] :: elementSchemas[t]
