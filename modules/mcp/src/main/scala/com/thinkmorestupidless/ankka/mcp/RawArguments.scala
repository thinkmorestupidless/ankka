package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json

/**
 * A tool's arguments checked against a JSON Schema the platform did not derive (feature 054): one
 * an SDK derived, or a developer wrote, and the sidecar received in discovery. The tool runs in
 * another process, which decodes the arguments itself; what is checked here is what makes a call
 * reach it as a call the developer could have meant: an object, every required property present,
 * and each property of its declared type, items and enumeration. Every other keyword is the
 * process's.
 *
 * The first problem is answered, worded as the Scala builder words a refused parameter (`parameter
 * 'quantity': must be an integer, not a string`), so a client is told the same thing whichever
 * language the service is written in.
 */
private[ankka] object RawArguments:

  /**
   * What is wrong with `arguments` for `schema`, the first problem first; empty when they conform.
   */
  def problems(schema: Json, arguments: Json): Vector[String] =
    Params.argumentsOf(ToolCall(arguments, null)) match
      case Left(refused) => Vector(refused)
      case Right(fields) =>
        val properties =
          schema("properties").collect { case Json.Obj(ps) => ps }.getOrElse(Map.empty)
        val required =
          schema("required").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
        val missing = required
          .filterNot(fields.fields.contains)
          .map(n => s"parameter '$n': is required and missing")
        val wrong = properties.toVector.sortBy(_._1).flatMap { (name, declared) =>
          fields.fields
            .get(name)
            .toVector
            .flatMap(value => check(declared, value))
            .map(p => s"parameter '$name': $p")
        }
        missing ++ wrong

  /** Whether a schema is one a tool's arguments can be checked against: a JSON object. */
  def isSchema(schema: Json): Boolean = schema match
    case _: Json.Obj => true
    case _           => false

  private def check(schema: Json, value: Json): Vector[String] =
    val types = schema("type") match
      case Some(Json.Str(t))  => Vector(t)
      case Some(Json.Arr(ts)) => ts.flatMap(_.asString)
      case _                  => Vector.empty
    val ofType =
      if types.isEmpty || types.exists(conforms(_, value)) then Vector.empty
      else Vector(s"must be ${types.map(article).mkString(" or ")}, not ${describe(value)}")
    val nested = (ofType, value) match
      case (Vector(), Json.Obj(fields)) =>
        val properties =
          schema("properties").collect { case Json.Obj(ps) => ps }.getOrElse(Map.empty)
        val required =
          schema("required").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
        required.filterNot(fields.contains).map(n => s"'$n' is required and missing") ++
          properties.toVector
            .sortBy(_._1)
            .flatMap((n, s) => fields.get(n).toVector.flatMap(check(s, _)).map(p => s"'$n' $p"))
      case (Vector(), Json.Arr(values)) =>
        schema("items").toVector.flatMap(s =>
          values.zipWithIndex.flatMap((v, i) => check(s, v).map(p => s"item $i $p"))
        )
      case _ => Vector.empty
    val ofEnum = schema("enum").flatMap(_.asArray) match
      case Some(allowed) if ofType.isEmpty && !allowed.contains(value) =>
        Vector(s"must be one of ${allowed.map(_.render).mkString(", ")}, not ${value.render}")
      case _ => Vector.empty
    ofType ++ nested ++ ofEnum

  private def conforms(kind: String, value: Json): Boolean = (kind, value) match
    case ("object", Json.Obj(_))   => true
    case ("array", Json.Arr(_))    => true
    case ("string", Json.Str(_))   => true
    case ("number", Json.Num(_))   => true
    case ("integer", Json.Num(n))  => n.isWhole
    case ("boolean", Json.Bool(_)) => true
    case ("null", Json.Null)       => true
    case (other, _) =>
      !Set("object", "array", "string", "number", "integer", "boolean", "null").contains(other)

  private def article(kind: String): String = kind match
    case "object" | "array" | "integer" => s"an $kind"
    case "null"                         => "null"
    case other                          => s"a $other"

  private def describe(value: Json): String = value match
    case Json.Null    => "null"
    case Json.Bool(_) => "a boolean"
    case Json.Num(_)  => "a number"
    case Json.Str(_)  => "a string"
    case Json.Arr(_)  => "an array"
    case Json.Obj(_)  => "an object"
