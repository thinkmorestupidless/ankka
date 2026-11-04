package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.Personal

import scala.quoted.*

/**
 * Where a view's row holds personal fields (feature 042, R12), as paths into its JSON: `email`, a
 * nested record's `address.line1`, a list's `contacts[*].email`. An `Option` adds nothing to a
 * path. Derived from the row's type where a view is declared, so a view says nothing to have its
 * personal fields known: the runtime refuses a declared query's predicate on one, and redacts each
 * erased data subject's from the rows.
 *
 * Case classes, sealed hierarchies, options, collections and arrays are followed; anything else (a
 * `String`, a hand-written class) holds none the SDK can see. A recursive row type is followed
 * `PersonalFields.Depth` levels deep.
 */
final case class PersonalFields[A](paths: Vector[String])

object PersonalFields:

  /** How deep a row's records are followed for personal fields. */
  inline val Depth = 8

  /** The personal fields of `A`. */
  inline def of[A]: PersonalFields[A] = ${ ofImpl[A] }

  inline given derived[A]: PersonalFields[A] = of[A]

  private def ofImpl[A: Type](using Quotes): Expr[PersonalFields[A]] =
    import quotes.reflect.*

    val personal = TypeRepr.of[Personal[Any]]
    val option   = TypeRepr.of[Option[Any]]
    val iterable = TypeRepr.of[Iterable[Any]]
    val plain    = List(
      TypeRepr.of[String],
      TypeRepr.of[AnyVal],
      TypeRepr.of[java.time.temporal.TemporalAccessor],
      TypeRepr.of[java.util.UUID],
      TypeRepr.of[BigDecimal],
      TypeRepr.of[BigInt]
    )

    def join(prefix: String, label: String) = if prefix.isEmpty then label else s"$prefix.$label"

    def elementOf(t: TypeRepr, of: Symbol): Option[TypeRepr] =
      t.baseType(of).typeArgs.headOption

    def walk(t0: TypeRepr, prefix: String, depth: Int): List[String] =
      val t = t0.dealias.widen
      if depth <= 0 then Nil
      else if t <:< personal then List(prefix)
      else if t <:< option then
        elementOf(t, option.typeSymbol).toList.flatMap(walk(_, prefix, depth - 1))
      else if t <:< iterable then
        elementOf(t, iterable.typeSymbol).toList.flatMap(walk(_, s"$prefix[*]", depth - 1))
      else if t.typeSymbol == defn.ArrayClass then
        t.typeArgs.headOption.toList.flatMap(walk(_, s"$prefix[*]", depth - 1))
      else if plain.exists(t <:< _) then Nil
      else
        val sym = t.typeSymbol
        if sym.isClassDef && sym.flags.is(Flags.Case) then
          sym.caseFields.flatMap(f => walk(t.memberType(f), join(prefix, f.name), depth - 1))
        else if sym.isClassDef && sym.flags.is(Flags.Sealed) then
          sym.children
            .filter(_.isClassDef)
            .flatMap(child => walk(child.typeRef, prefix, depth))
            .distinct
        else Nil

    val found = Expr(walk(TypeRepr.of[A], "", Depth).distinct)
    '{ PersonalFields[A]($found.toVector) }
