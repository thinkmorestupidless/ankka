package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.agent.Json

/** One place a value does not have its shape, and why. `path` is `$`, `$.field`, `$.items[2]`. */
final case class ShapeProblem(path: String, message: String):
  override def toString: String = s"$path: $message"

/**
 * The shape a run's input or a step's result must have: JSON Schema, in a subset small enough to
 * check exhaustively and write by hand — `type`, `properties`, `required`, `items`, `enum` and
 * `description`. A blueprint's shapes are data, so the platform checks them itself; `JsonSchema[A]`
 * only describes a Scala type to a model.
 */
final class Shape private (val schema: Json):

  /** The schema's `type`. */
  def kind: String = schema("type").flatMap(_.asString).getOrElse("any")

  def isArray: Boolean  = kind == "array"
  def isObject: Boolean = kind == "object"

  /** The shape of an array's items, when it is an array that declares them. */
  def items: Option[Shape] = schema("items").map(Shape.unchecked)

  /** The shape of one property of an object. */
  def field(name: String): Option[Shape] =
    schema("properties").flatMap(_(name)).map(Shape.unchecked)

  /** An array whose items are strings: what a gather's `chosenBy` must read. */
  def isStringArray: Boolean = isArray && items.exists(_.kind == "string")

  /** Every place `value` departs from this shape; empty when it conforms. */
  def check(value: Json): Vector[ShapeProblem] = Shape.check(schema, value, "$")

  override def equals(other: Any): Boolean = other match
    case that: Shape => that.schema == schema
    case _           => false
  override def hashCode: Int    = schema.hashCode
  override def toString: String = schema.render

object Shape:

  val Keywords: Set[String] = Set("type", "properties", "required", "items", "enum", "description")
  val Types: Set[String]    = Set("object", "array", "string", "number", "integer", "boolean")

  /** Reads a schema, refusing anything outside the subset; every problem is named at once. */
  def fromJson(json: Json): Either[Vector[String], Shape] =
    val problems = problemsIn(json, "$")
    if problems.isEmpty then Right(new Shape(json)) else Left(problems)

  def fromText(text: String): Either[Vector[String], Shape] =
    Json.parse(text).left.map(e => Vector(s"$$: not JSON: $e")).flatMap(fromJson)

  /** For a schema already checked, or built here. */
  private def unchecked(json: Json): Shape = new Shape(json)

  // ── Builders ───────────────────────────────────────────────────────────────

  val string: Shape  = unchecked(Json.obj("type" -> Json.str("string")))
  val number: Shape  = unchecked(Json.obj("type" -> Json.str("number")))
  val integer: Shape = unchecked(Json.obj("type" -> Json.str("integer")))
  val boolean: Shape = unchecked(Json.obj("type" -> Json.str("boolean")))

  /** An object with these properties, every one of them required. */
  def obj(fields: (String, Shape)*): Shape =
    unchecked(
      Json.obj(
        "type"       -> Json.str("object"),
        "properties" -> Json.Obj(fields.map((n, s) => n -> s.schema).toMap),
        "required"   -> Json.Arr(fields.map((n, _) => Json.str(n)).toVector)
      )
    )

  /** As `obj`, with some properties optional. */
  def obj(fields: Seq[(String, Shape)], required: Seq[String]): Shape =
    unchecked(
      Json.obj(
        "type"       -> Json.str("object"),
        "properties" -> Json.Obj(fields.map((n, s) => n -> s.schema).toMap),
        "required"   -> Json.Arr(required.map(Json.str).toVector)
      )
    )

  def arr(items: Shape): Shape =
    unchecked(Json.obj("type" -> Json.str("array"), "items" -> items.schema))

  /** A string that is one of these. */
  def enumOf(values: String*): Shape =
    unchecked(
      Json.obj("type" -> Json.str("string"), "enum" -> Json.Arr(values.map(Json.str).toVector))
    )

  extension (shape: Shape)
    /** The same shape, described for the model. */
    def describedAs(text: String): Shape = shape.schema match
      case Json.Obj(fields) => unchecked(Json.Obj(fields + ("description" -> Json.str(text))))
      case other            => unchecked(other)

  // ── Checking a schema ──────────────────────────────────────────────────────

  private def problemsIn(json: Json, path: String): Vector[String] = json match
    case Json.Obj(fields) =>
      val unknown = fields.keys.filterNot(Keywords.contains).toVector.sorted.map { k =>
        s"$path: '$k' is not in the shapes the platform checks (${Keywords.toVector.sorted.mkString(", ")})"
      }
      val kind = fields.get("type") match
        case None                                   => Vector(s"$path: a shape needs a 'type'")
        case Some(Json.Str(t)) if Types.contains(t) => Vector.empty
        case Some(Json.Str(t)) =>
          Vector(s"$path: type '$t' is not one of ${Types.toVector.sorted.mkString(", ")}")
        case Some(_) => Vector(s"$path: 'type' must be a string")
      val props = fields.get("properties").toVector.flatMap {
        case Json.Obj(ps) => ps.toVector.sortBy(_._1).flatMap((n, s) => problemsIn(s, s"$path.$n"))
        case _            => Vector(s"$path: 'properties' must be an object")
      }
      val required = fields.get("required").toVector.flatMap {
        case Json.Arr(names) if names.forall(_.asString.isDefined) =>
          val declared =
            fields.get("properties").collect { case Json.Obj(ps) => ps.keySet }.getOrElse(Set.empty)
          names
            .flatMap(_.asString)
            .filterNot(declared.contains)
            .map(n => s"$path: required '$n' is not a property")
        case _ => Vector(s"$path: 'required' must be an array of property names")
      }
      val items = fields.get("items").toVector.flatMap(s => problemsIn(s, s"$path.items"))
      val enumeration = fields.get("enum").toVector.flatMap {
        case Json.Arr(vs) if vs.nonEmpty => Vector.empty
        case _                           => Vector(s"$path: 'enum' must be a non-empty array")
      }
      val description = fields.get("description").toVector.flatMap {
        case Json.Str(_) => Vector.empty
        case _           => Vector(s"$path: 'description' must be a string")
      }
      unknown ++ kind ++ props ++ required ++ items ++ enumeration ++ description
    case _ => Vector(s"$path: a shape is an object")

  // ── Checking a value ───────────────────────────────────────────────────────

  private def check(schema: Json, value: Json, path: String): Vector[ShapeProblem] =
    val kind = schema("type").flatMap(_.asString).getOrElse("any")
    val ofKind: Vector[ShapeProblem] = (kind, value) match
      case ("object", Json.Obj(fields)) =>
        val properties =
          schema("properties").collect { case Json.Obj(ps) => ps }.getOrElse(Map.empty)
        val required =
          schema("required").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
        val missing = required
          .filterNot(fields.contains)
          .map(n => ShapeProblem(s"$path.$n", "is required and missing"))
        val nested = properties.toVector.sortBy(_._1).flatMap { (n, s) =>
          fields.get(n).toVector.flatMap(v => check(s, v, s"$path.$n"))
        }
        missing ++ nested
      case ("object", _) => Vector(ShapeProblem(path, s"must be an object, not ${describe(value)}"))
      case ("array", Json.Arr(values)) =>
        schema("items").toVector.flatMap(s =>
          values.zipWithIndex.flatMap((v, i) => check(s, v, s"$path[$i]"))
        )
      case ("array", _) => Vector(ShapeProblem(path, s"must be an array, not ${describe(value)}"))
      case ("string", Json.Str(_)) => Vector.empty
      case ("string", _) => Vector(ShapeProblem(path, s"must be a string, not ${describe(value)}"))
      case ("number", Json.Num(_)) => Vector.empty
      case ("number", _) => Vector(ShapeProblem(path, s"must be a number, not ${describe(value)}"))
      case ("integer", Json.Num(n)) if n.isWhole => Vector.empty
      case ("integer", _) =>
        Vector(ShapeProblem(path, s"must be an integer, not ${describe(value)}"))
      case ("boolean", Json.Bool(_)) => Vector.empty
      case ("boolean", _) =>
        Vector(ShapeProblem(path, s"must be a boolean, not ${describe(value)}"))
      case _ => Vector.empty
    val ofEnum = schema("enum").flatMap(_.asArray) match
      case Some(allowed) if ofKind.isEmpty && !allowed.contains(value) =>
        Vector(
          ShapeProblem(
            path,
            s"must be one of ${allowed.map(_.render).mkString(", ")}, not ${value.render}"
          )
        )
      case _ => Vector.empty
    ofKind ++ ofEnum

  private def describe(value: Json): String = value match
    case Json.Null    => "null"
    case Json.Bool(_) => "a boolean"
    case Json.Num(_)  => "a number"
    case Json.Str(_)  => "a string"
    case Json.Arr(_)  => "an array"
    case Json.Obj(_)  => "an object"

  /**
   * Written as its schema; read back checked, so a stored shape outside the subset is a decode
   * error.
   */
  given codec: JsonValueCodec[Shape] = new JsonValueCodec[Shape]:
    def decodeValue(in: JsonReader, default: Shape): Shape =
      fromJson(Json.codec.decodeValue(in, Json.Null))
        .fold(ps => in.decodeError(ps.mkString("; ")), identity)
    def encodeValue(x: Shape, out: JsonWriter): Unit = Json.codec.encodeValue(x.schema, out)
    def nullValue: Shape                             = null
