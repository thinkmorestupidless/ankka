package com.thinkmorestupidless.ankka.sdk

import scala.quoted.*

/**
 * The path of a field a selector names, `_.email` as `email` and `_.address.line1` as
 * `address.line1` (or `_.address.get.line1` through an `Option`): what a view's `lookup` is told
 * (feature 042). Anything but a chain of fields is refused where it is written.
 */
object FieldPath:

  inline def of[A](inline select: A => Any): String = ${ ofImpl('select) }

  private def ofImpl[A: Type](select: Expr[A => Any])(using Quotes): Expr[String] =
    import quotes.reflect.*
    def refuse(term: Term): Nothing =
      report.errorAndAbort(
        s"a lookup names a field of the row, such as _.email; '${term.show}' is not one"
      )
    def path(term: Term): List[String] = term match
      // An optional record's field is reached through `.get`, which adds nothing to the path.
      case Select(qualifier, "get") if qualifier.tpe.widen <:< TypeRepr.of[Option[Any]] =>
        path(qualifier)
      case Select(qualifier, name) => path(qualifier) :+ name
      case Ident(_)                => Nil
      case Inlined(_, Nil, inner)  => path(inner)
      case Typed(inner, _)         => path(inner)
      case Block(Nil, inner)       => path(inner)
      case other                   => refuse(other)
    select.asTerm.underlyingArgument match
      case Lambda(List(_), body) =>
        val names = path(body)
        if names.isEmpty then refuse(body) else Expr(names.mkString("."))
      case other => refuse(other)
