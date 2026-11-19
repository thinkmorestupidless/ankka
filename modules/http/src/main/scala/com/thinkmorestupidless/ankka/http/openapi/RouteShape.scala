package com.thinkmorestupidless.ankka.http.openapi

import com.thinkmorestupidless.ankka.core.shape.ShapeOf

/**
 * What one route knows of itself (feature 067, data-model.md): its path parameters' types, its
 * body, its answer, and the descriptions of the scopes it was declared in, outermost first.
 * Recorded where the route is declared, from the instances that read its body and write its answer.
 * A route built anywhere else knows nothing, which a document reports as incomplete.
 */
private[ankka] final case class RouteShape(
    /**
     * Each path parameter's name and its `FromPath` type: `string`, `int`, `long`, `boolean`,
     * `uuid`.
     */
    pathParameters: Vector[(String, String)],
    /** `None` for a route with no body. */
    body: Option[BodyShape],
    answer: AnswerShape,
    scopes: Vector[DescribedScope] = Vector.empty
):
  /** The scopes' descriptions, folded outermost first. */
  def description: Description =
    scopes.foldLeft(Description.none)((d, s) => d.andThen(s.description))

private[ankka] object RouteShape:
  val unknown: RouteShape =
    RouteShape(
      Vector.empty,
      None,
      AnswerShape(None, "application/octet-stream", None, AnswerKind.Body)
    )

/** A request body: its content type, its shape when known, and whether it is read as a stream. */
private[ankka] final case class BodyShape(
    contentType: String,
    shape: Option[ShapeOf[?]],
    streamed: Boolean = false
)

private[ankka] enum AnswerKind:
  /** A body written whole. */
  case Body

  /** No body: `204`. */
  case Nothing

  /** A body written as it is read; bytes have no shape. */
  case Stream

  /** Server-sent events. */
  case Events

/**
 * An answer: the status every value is answered with, or `None` when the handler's value decides
 * it; the content type; the shape when known.
 */
private[ankka] final case class AnswerShape(
    status: Option[Int],
    contentType: String,
    shape: Option[ShapeOf[?]],
    kind: AnswerKind
)

/**
 * A `described` scope: what it says, and how many routes it held, counted as they are declared, so
 * a field only one route may give can be refused when the scope held more.
 */
private[ankka] final class DescribedScope(val description: Description):
  private var count                   = 0
  private[ankka] def held: Int        = count
  private[ankka] def recorded(): Unit = count += 1
