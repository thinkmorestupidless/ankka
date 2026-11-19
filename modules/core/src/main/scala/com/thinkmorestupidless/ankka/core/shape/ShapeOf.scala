package com.thinkmorestupidless.ankka.core.shape

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID
import scala.deriving.Mirror

/**
 * The JSON shape of `A` as `Codecs.make` writes it (feature 067, research R1), and the named shapes
 * it refers to, itself included when it is named.
 *
 * Derived for case classes, sealed hierarchies and enumerations; given for the scalars, `Option`,
 * the collections and maps. A type whose companion gives a `JsonValueCodec` states its shape there
 * too, as `ShapeOf.of(...)` or, for a codec that is `Codecs.make`, `ShapeOf.derived`:
 *
 * {{{
 * object ServiceLifecycle:
 *   given JsonValueCodec[ServiceLifecycle] = ...
 *   given ShapeOf[ServiceLifecycle] = ShapeOf.of("""{"type":"string","enum":["Ready","Paused"]}""")
 * }}}
 */
trait ShapeOf[A]:
  def shape: Shape

  /** Every named shape `shape` reaches, each once. */
  def definitions: Vector[Named] = Vector.empty

object ShapeOf:

  /** An instance from its parts; what the derivation writes. */
  def instance[A](s: Shape, defs: Vector[Named]): ShapeOf[A] =
    val reached = defs.distinctBy(_.name)
    new ShapeOf[A]:
      val shape: Shape                        = s
      override val definitions: Vector[Named] = reached

  /** A shape written as JSON Schema, a JSON object, used as it is given. */
  def of[A](jsonSchema: String): ShapeOf[A] = instance(Shape.Raw(jsonSchema), Vector.empty)

  /** Case classes, sealed hierarchies and enumerations, as `Codecs.make` writes them. */
  inline given derived[A](using Mirror.Of[A]): ShapeOf[A] = ${ ShapeMacro.derived[A] }

  private def scalar[A](kind: String, format: Option[String] = None): ShapeOf[A] =
    instance(Shape.Scalar(kind, format), Vector.empty)

  given ShapeOf[String]     = scalar("string")
  given ShapeOf[Boolean]    = scalar("boolean")
  given ShapeOf[Int]        = scalar("integer")
  given ShapeOf[Long]       = scalar("integer", Some("int64"))
  given ShapeOf[Double]     = scalar("number")
  given ShapeOf[BigDecimal] = scalar("number")
  given ShapeOf[Instant]    = scalar("string", Some("date-time"))
  given ShapeOf[Duration]   = scalar("string", Some("duration"))
  given ShapeOf[LocalDate]  = scalar("string", Some("date"))
  given ShapeOf[UUID]       = scalar("string", Some("uuid"))

  given option[A](using of: ShapeOf[A]): ShapeOf[Option[A]] =
    instance(Shape.Nullable(of.shape), of.definitions)

  private def array[C, A](of: ShapeOf[A]): ShapeOf[C] =
    instance(Shape.Arr(of.shape), of.definitions)

  given list[A](using of: ShapeOf[A]): ShapeOf[List[A]]     = array(of)
  given vector[A](using of: ShapeOf[A]): ShapeOf[Vector[A]] = array(of)
  given seq[A](using of: ShapeOf[A]): ShapeOf[Seq[A]]       = array(of)
  given set[A](using of: ShapeOf[A]): ShapeOf[Set[A]]       = array(of)

  given map[A](using of: ShapeOf[A]): ShapeOf[Map[String, A]] =
    instance(Shape.Dict(of.shape), of.definitions)

  /**
   * `A`'s shape when it has one (feature 067): what a route asks for. A type with no derivation
   * gives none and never fails a compile; it makes the route incomplete.
   */
  final class Maybe[A](val found: Option[ShapeOf[A]])

  object Maybe:
    def none[A]: Maybe[A] = Maybe(None)

    inline given maybe[A]: Maybe[A] = ${ ShapeMacro.maybe[A] }
