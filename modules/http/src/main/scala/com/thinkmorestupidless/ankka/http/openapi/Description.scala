package com.thinkmorestupidless.ankka.http.openapi

import com.thinkmorestupidless.ankka.core.shape.ShapeOf
import com.thinkmorestupidless.ankka.http.FromQuery

/**
 * A query parameter a handler reads: its name, its `FromQuery` type, and whether it must be sent.
 */
final case class QueryParameter(name: String, kind: String, required: Boolean)

/**
 * What a route declares about itself beyond its handler's types (feature 067): a summary, the shape
 * of a body or an answer whose type says none, answers by status, refusals, query parameters and
 * route tags. Given to `described` around one or more routes, or as an endpoint's `description` for
 * all of its routes:
 *
 * {{{
 * described(_.summary("A player's messages").answers[Messages].refuses(404, "not found")
 *            .query[Int]("page")) {
 *   get("/{playerId}/messages") { (id: String) => ... }
 * }
 * }}}
 *
 * A description adds to what the route's types say and never changes what the route does.
 */
final class Description private (
    private[ankka] val summaries: Vector[String],
    private[ankka] val bodies: Vector[ShapeOf[?]],
    private[ankka] val answered: Vector[(Int, Option[ShapeOf[?]])],
    private[ankka] val refusals: Vector[(Int, String, Option[ShapeOf[?]])],
    private[ankka] val refusalShapes: Vector[ShapeOf[?]],
    private[ankka] val queries: Vector[QueryParameter],
    private[ankka] val tags: Vector[String]
):
  private def copy(
      summaries: Vector[String] = summaries,
      bodies: Vector[ShapeOf[?]] = bodies,
      answered: Vector[(Int, Option[ShapeOf[?]])] = answered,
      refusals: Vector[(Int, String, Option[ShapeOf[?]])] = refusals,
      refusalShapes: Vector[ShapeOf[?]] = refusalShapes,
      queries: Vector[QueryParameter] = queries,
      tags: Vector[String] = tags
  ): Description =
    Description(summaries, bodies, answered, refusals, refusalShapes, queries, tags)

  /** One line saying what the route is for. */
  def summary(text: String): Description = copy(summaries = summaries :+ text)

  /** The body's shape, for a route whose body type says none (text, bytes). */
  def body[A](using shape: ShapeOf[A]): Description = copy(bodies = bodies :+ shape)

  /** The body's shape as JSON Schema. */
  def body(jsonSchema: String): Description = copy(bodies = bodies :+ ShapeOf.of(jsonSchema))

  /** The `200` answer's shape. */
  def answers[A](using shape: ShapeOf[A]): Description = answers[A](200)

  /** An answer by status, with a shape. */
  def answers[A](status: Int)(using shape: ShapeOf[A]): Description =
    copy(answered = answered :+ (status -> Some(shape)))

  /** An answer by status, with no body. */
  def answers(status: Int): Description = copy(answered = answered :+ (status -> None))

  /** An answer by status, its shape as JSON Schema. */
  def answers(status: Int, jsonSchema: String): Description =
    copy(answered = answered :+ (status -> Some(ShapeOf.of(jsonSchema))))

  /** A refusal, with the endpoint's refusal shape, else the platform's. */
  def refuses(status: Int, reason: String): Description =
    copy(refusals = refusals :+ (status, reason, None))

  /** A refusal with a shape of its own. */
  def refuses[A](status: Int, reason: String)(using shape: ShapeOf[A]): Description =
    copy(refusals = refusals :+ (status, reason, Some(shape)))

  /** The shape of every refusal that names none. */
  def refusalShape[A](using shape: ShapeOf[A]): Description =
    copy(refusalShapes = refusalShapes :+ shape)

  /** A query parameter the handler reads, which may be left out. */
  def query[A](name: String)(using from: FromQuery[A]): Description =
    copy(queries = queries :+ QueryParameter(name, from.name, required = false))

  /** A query parameter the handler reads, which must be sent. */
  def queryRequired[A](name: String)(using from: FromQuery[A]): Description =
    copy(queries = queries :+ QueryParameter(name, from.name, required = true))

  /** Route tags, by which a document is written for some of a service's routes. */
  def tag(name: String, more: String*): Description = copy(tags = tags ++ (name +: more))

  /** This description, then `inner`'s: lists append, so two of a single field are both kept. */
  private[ankka] def andThen(inner: Description): Description =
    Description(
      summaries ++ inner.summaries,
      bodies ++ inner.bodies,
      answered ++ inner.answered,
      refusals ++ inner.refusals,
      refusalShapes ++ inner.refusalShapes,
      queries ++ inner.queries,
      tags ++ inner.tags
    )

  /** Whether it says something only one route may: a summary, a body shape or an answer. */
  private[ankka] def namesOneRoute: Boolean =
    summaries.nonEmpty || bodies.nonEmpty || answered.nonEmpty

object Description:
  /** A description that says nothing. */
  val none: Description =
    Description(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
