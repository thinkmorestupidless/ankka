package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.shape.ShapeOf
import com.thinkmorestupidless.ankka.http.openapi.{Description, QueryParameter}
import com.thinkmorestupidless.ankka.javasdk.annotations

import java.lang.reflect.AnnotatedElement

/**
 * A Java endpoint's or handler's OpenAPI annotations as the `Description` a Scala route is given
 * (feature 067): what `described` says in Scala, said by `@Summary`, `@BodyShape`, `@Answers`,
 * `@Refuses`, `@RefusalShape`, `@ReadsQuery` and `@Tag`. A class gives every route of its endpoint
 * what it says, before the method's own.
 */
private[bridge] object Described:

  /** What `element`'s annotations say, or every problem with them, each a sentence. */
  def of(element: AnnotatedElement): Either[Vector[String], Description] =
    val problems = Vector.newBuilder[String]
    var d        = Description.none

    def shape(c: Class[?], what: String): Option[ShapeOf[?]] =
      Shapes.of(c) match
        case Right(s)  => Some(s)
        case Left(why) =>
          problems += s"$what names ${c.getSimpleName}, which has no shape: $why"
          None

    def named(c: Class[?]): Boolean = c != classOf[Void] && c != java.lang.Void.TYPE

    Option(element.getAnnotation(classOf[annotations.Summary])).foreach(s => d = d.summary(s.value))
    Option(element.getAnnotation(classOf[annotations.BodyShape])).foreach { b =>
      (named(b.value), b.schema.nonEmpty) match
        case (true, true) => problems += "@BodyShape names both a type and a schema; name one"
        case (true, _)    => shape(b.value, "@BodyShape").foreach(s => d = d.body(using any(s)))
        case (_, true)    => d = d.body(b.schema)
        case _            => problems += "@BodyShape names neither a type nor a schema"
    }
    element.getAnnotationsByType(classOf[annotations.Answers]).foreach { a =>
      (named(a.shape), a.schema.nonEmpty) match
        case (true, true) =>
          problems += s"@Answers(status = ${a.status}) names both a shape and a schema; name one"
        case (true, _) =>
          shape(a.shape, s"@Answers(status = ${a.status})")
            .foreach(s => d = d.answering(a.status, Some(s)))
        case (_, true) => d = d.answers(a.status, a.schema)
        case _         => d = d.answering(a.status, None)
    }
    element.getAnnotationsByType(classOf[annotations.Refuses]).foreach { r =>
      val own =
        if named(r.shape) then shape(r.shape, s"@Refuses(status = ${r.status})") else None
      d = d.refusing(r.status, r.reason, own)
    }
    Option(element.getAnnotation(classOf[annotations.RefusalShape])).foreach(r =>
      shape(r.value, "@RefusalShape").foreach(s => d = d.refusalShape(using any(s)))
    )
    element.getAnnotationsByType(classOf[annotations.ReadsQuery]).foreach { q =>
      kindOf(q.`type`) match
        case Some(kind) => d = d.querying(QueryParameter(q.name, kind, q.required))
        case None       =>
          problems += s"@ReadsQuery(\"${q.name}\") is read as ${q.`type`.getSimpleName}, which a " +
            "query cannot hold: String, Integer, Long, Double, Boolean or UUID"
    }
    Option(element.getAnnotation(classOf[annotations.Tag])).foreach { t =>
      t.value.toList match
        case first :: more => d = d.tag(first, more*)
        case Nil           => problems += "@Tag names no tag"
    }
    val found = problems.result()
    if found.nonEmpty then Left(found) else Right(d)

  /**
   * The document's name for a path or query parameter's Java type: as Scala's `FromPath` names
   * them, and `double` for a `Double`; an enum's constants are text.
   */
  def kindOf(c: Class[?]): Option[String] =
    if c == classOf[String] || c.isEnum then Some("string")
    else if c == classOf[Int] || c == classOf[Integer] then Some("int")
    else if c == classOf[Long] || c == classOf[java.lang.Long] then Some("long")
    else if c == classOf[Boolean] || c == classOf[java.lang.Boolean] then Some("boolean")
    else if c == classOf[Double] || c == classOf[java.lang.Double] then Some("double")
    else if c == classOf[java.util.UUID] then Some("uuid")
    else None

  private def any(s: ShapeOf[?]): ShapeOf[Any] = s.asInstanceOf[ShapeOf[Any]]
