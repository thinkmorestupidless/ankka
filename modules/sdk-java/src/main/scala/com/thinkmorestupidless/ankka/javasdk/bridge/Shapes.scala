package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.thinkmorestupidless.ankka.core.graph.GraphJson as J
import com.thinkmorestupidless.ankka.core.shape.{Named, Shape, ShapeOf, TypeName}
import com.thinkmorestupidless.ankka.javasdk.codec.RecordCodec

import java.lang.reflect.Type
import scala.collection.mutable

/**
 * A Java or Kotlin type's shape, from what the record codec writes for it (feature 067, research
 * R1): the codec's plan, read through `RecordCodec.planOf`, so the document says what the codec
 * does and not what a second derivation thinks it does. The rules are Scala's: a record's
 * defaulted, optional and collection components are not `required`; `Optional` and a Kotlin
 * nullable type are nullable; a sealed interface and an enum are one-of their cases by `type`; a
 * record, a sealed interface and an enum are named, which ends a type that contains itself.
 */
private[ankka] object Shapes:

  /** `tpe`'s shape, or why it has none: a type the codec does not write. */
  def of(tpe: Type): Either[String, ShapeOf[?]] =
    scala.util
      .Try(RecordCodec.planOf(tpe))
      .toEither
      .left
      .map(e => s"no shape for ${tpe.getTypeName}: ${Option(e.getMessage).getOrElse(e.toString)}")
      .flatMap(read)

  /** A plan's description as a shape. */
  private[bridge] def read(description: String): Either[String, ShapeOf[?]] =
    GraphJson.parse(description.getBytes("UTF-8")).flatMap { json =>
      val defs = mutable.ArrayBuffer.empty[Named]
      scala.util
        .Try(node(json, defs))
        .toEither
        .left
        .map(e => s"the codec's plan could not be read: ${e.getMessage}")
        .map(shape => ShapeOf.instance[Any](shape, defs.toVector))
    }

  private def node(json: GraphJson, defs: mutable.ArrayBuffer[Named]): Shape =
    def str(name: String): String = json.field(name) match
      case Some(J.Str(s)) => s
      case other          => throw IllegalArgumentException(s"$name is $other")
    json match
      case J.Obj((key, value) +: _) =>
        key match
          case "leaf"     => leaf(str("leaf"))
          case "nullable" => Shape.Nullable(node(value, defs))
          case "array"    => Shape.Arr(node(value, defs))
          case "dict"     => Shape.Dict(node(value, defs))
          case "ref"      => Shape.Ref(TypeName(str("ref"), str("simple")))
          case "named"    =>
            val name = TypeName(str("named"), str("simple"))
            val body = json.field("shape").getOrElse(throw IllegalArgumentException("no shape"))
            val at   = defs.length
            defs += Named(name, Shape.Obj(Vector.empty, Vector.empty))
            defs(at) = Named(name, structure(body, defs))
            Shape.Ref(name)
          case other => throw IllegalArgumentException(s"unknown node $other")
      case other => throw IllegalArgumentException(s"not a node: $other")

  private def structure(json: GraphJson, defs: mutable.ArrayBuffer[Named]): Shape =
    json match
      case J.Obj(Vector(("object", J.Arr(fields)))) =>
        val read = fields.map { field =>
          val name     = field.field("name").collect { case J.Str(s) => s }.get
          val required = field.field("required").contains(J.Bool(true))
          (name, node(field.field("shape").get, defs), required)
        }
        Shape.Obj(read.map((n, s, _) => (n, s)), read.collect { case (n, _, true) => n })
      case J.Obj(Vector(("oneOf", J.Arr(cases)))) =>
        Shape.OneOf(cases.map { c =>
          val name = c.field("name").collect { case J.Str(s) => s }.get
          (name, structure(c.field("shape").get, defs))
        })
      case other => throw IllegalArgumentException(s"not a structure: $other")

  /** What the codec writes each leaf as, in Scala's words for the same types. */
  private def leaf(kind: String): Shape = kind match
    case "STRING"          => Shape.string
    case "BOOLEAN"         => Shape.boolean
    case "INT"             => Shape.integer
    case "LONG"            => Shape.Scalar("integer", Some("int64"))
    case "DOUBLE"          => Shape.number
    case "BIG_DECIMAL"     => Shape.number
    case "INSTANT"         => Shape.Scalar("string", Some("date-time"))
    case "DURATION"        => Shape.Scalar("string", Some("duration"))
    case "LOCAL_DATE"      => Shape.Scalar("string", Some("date"))
    case "LOCAL_DATE_TIME" => Shape.string
    case "UUID"            => Shape.Scalar("string", Some("uuid"))
    case "BYTES"           => Shape.Scalar("string", Some("byte"))
    case other             => throw IllegalArgumentException(s"unknown leaf $other")
