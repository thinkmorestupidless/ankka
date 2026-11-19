package com.thinkmorestupidless.ankka.core.shape

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

import scala.collection.mutable
import scala.quoted.*

/**
 * The derivation behind `ShapeOf.derived` and `ShapeOf.Maybe` (feature 067, research R1). A macro,
 * not a `Mirror` fold, because what `Codecs.make` writes depends on which fields have defaults and
 * on what a companion defines, and a `Mirror` says neither.
 *
 * The rules, each held to the codec by `ShapeAgreesSuite`:
 *   - a case class is an object, its fields in declaration order; `required` leaves out an
 *     `Option`, a collection and a field with a default, since the codec reads each when absent;
 *   - a sealed hierarchy or an enumeration is one of its leaves, each with a `type` constant;
 *   - a value class is its one field;
 *   - a type found with a `ShapeOf` of its own uses that one;
 *   - a type whose companion gives a `JsonValueCodec` and no `ShapeOf` has none, unless the shape
 *     is being derived inside that companion (`given ShapeOf[T] = ShapeOf.derived`);
 *   - a case class, a sealed hierarchy or an enumeration is named, written once and referred to, so
 *     a type that refers to itself terminates.
 */
private[shape] object ShapeMacro:

  def derived[A: Type](using Quotes): Expr[ShapeOf[A]] =
    import quotes.reflect.*
    val (shape, defs, why) = build[A](root = true)
    shape match
      case Some(s) => '{ ShapeOf.instance[A]($s, $defs) }
      case None    => report.errorAndAbort(why.getOrElse(s"no shape for ${Type.show[A]}"))

  def maybe[A: Type](using Quotes): Expr[ShapeOf.Maybe[A]] =
    val (shape, defs, _) = build[A](root = false)
    shape match
      case Some(s) => '{ ShapeOf.Maybe[A](Some(ShapeOf.instance[A]($s, $defs))) }
      case None    => '{ ShapeOf.Maybe.none[A] }

  private def build[A: Type](root: Boolean)(using
      Quotes
  ): (Option[Expr[Shape]], Expr[Vector[Named]], Option[String]) =
    import quotes.reflect.*

    val defs              = mutable.ListBuffer.empty[Expr[Vector[Named]]]
    val done              = mutable.Set.empty[String]
    var why: List[String] = Nil

    val derivedMethods: Set[Symbol] =
      Symbol
        .requiredModule("com.thinkmorestupidless.ankka.core.shape.ShapeOf")
        .methodMember("derived")
        .toSet

    def fullName(t: TypeRepr): String =
      val base = t.typeSymbol.fullName.replace("$", "")
      t.typeArgs match
        case Nil  => base
        case args => args.map(fullName).mkString(base + "[", ",", "]")

    def simpleName(t: TypeRepr): String =
      val base = t.typeSymbol.name.stripSuffix("$")
      t.typeArgs match
        case Nil  => base
        case args => (base +: args.map(simpleName)).mkString("_")

    def scalar(kind: String, format: Option[String] = None): Option[Expr[Shape]] =
      Some('{ Shape.Scalar(${ Expr(kind) }, ${ Expr(format) }) })

    def isA[T: Type](t: TypeRepr): Boolean = t =:= TypeRepr.of[T]

    def isCaseClass(sym: Symbol): Boolean =
      sym.isClassDef && sym.flags.is(Flags.Case) && !sym.flags.is(Flags.Module)

    def isSum(sym: Symbol): Boolean =
      sym.isClassDef && sym.flags.is(Flags.Sealed) && !sym.flags.is(Flags.Case)

    def isOptional(t: TypeRepr): Boolean = t.widen.dealias <:< TypeRepr.of[Option[Any]]

    def isCollection(t: TypeRepr): Boolean =
      val w = t.widen.dealias
      w <:< TypeRepr.of[Iterable[Any]] || w <:< TypeRepr.of[collection.Map[?, ?]] ||
      w.typeSymbol == defn.ArrayClass

    def argOf(t: TypeRepr, of: Symbol, index: Int): TypeRepr = t.baseType(of).typeArgs(index)

    def companionCodec(t: TypeRepr): Boolean =
      val companion = t.typeSymbol.companionModule
      !companion.isNoSymbol && (companion.declaredFields ++ companion.declaredMethods).exists {
        member =>
          (member.flags.is(Flags.Given) || member.flags.is(Flags.Implicit)) && {
            val declared = companion.termRef.memberType(member) match
              case m: MethodType => m.resType
              case p: PolyType   => p.resType
              case other         => other
            declared.widen <:< TypeRepr.of[JsonValueCodec].appliedTo(t)
          }
      }

    def insideCompanion(t: TypeRepr): Boolean =
      val companion                      = t.typeSymbol.companionModule
      def within(owner: Symbol): Boolean =
        if owner.isNoSymbol || owner.isPackageDef then false
        else if owner == companion || owner == companion.moduleClass then true
        else within(owner.maybeOwner)
      !companion.isNoSymbol && within(Symbol.spliceOwner)

    def found(t: TypeRepr): Option[Expr[Shape]] =
      Implicits.search(TypeRepr.of[ShapeOf].appliedTo(t)) match
        case success: ImplicitSearchSuccess if !derivedMethods.contains(success.tree.symbol) =>
          val instance = success.tree.asExprOf[ShapeOf[?]]
          defs += '{ $instance.definitions }
          Some('{ $instance.shape })
        case _ => None

    def named(t: TypeRepr, seen: Set[String], body: Set[String] => Option[Expr[Shape]]) =
      val full = fullName(t)
      val name = '{ TypeName(${ Expr(full) }, ${ Expr(simpleName(t)) }) }
      if seen(full) || done(full) then Some('{ Shape.Ref($name) })
      else
        body(seen + full).map { b =>
          done += full
          defs += '{ Vector(Named($name, $b)) }
          '{ Shape.Ref($name) }
        }

    def product(t: TypeRepr, seen: Set[String]): Option[Expr[Shape]] =
      val sym      = t.typeSymbol
      val defaults = sym.primaryConstructor.paramSymss.flatten
        .filter(p => p.isTerm && p.flags.is(Flags.HasDefault))
        .map(_.name)
        .toSet
      val fields = sym.caseFields.map { field =>
        val fieldType = t.memberType(field).widen
        (field.name, fieldType, of(fieldType, seen, root = false))
      }
      fields.collectFirst { case (name, fieldType, None) => (name, fieldType) } match
        case Some((name, fieldType)) =>
          why = s"no shape for ${t.show}: its field $name, a ${fieldType.show}, has none" :: why
          None
        case None =>
          val properties = fields.map((name, _, s) => '{ (${ Expr(name) }, ${ s.get }) })
          val required   = fields.collect {
            case (name, fieldType, _)
                if !defaults(name) && !isOptional(fieldType) && !isCollection(fieldType) =>
              Expr(name)
          }
          Some('{ Shape.Obj(Vector(${ Varargs(properties) }*), Vector(${ Varargs(required) }*)) })

    def leaves(sym: Symbol): List[Symbol] = sym.children.flatMap { child =>
      if isSum(child) then leaves(child) else List(child)
    }

    def sum(t: TypeRepr, seen: Set[String]): Option[Expr[Shape]] =
      val cases = leaves(t.typeSymbol).map { leaf =>
        val name                       = leaf.name.stripSuffix("$")
        val shape: Option[Expr[Shape]] =
          if leaf.isTerm || leaf.flags.is(Flags.Module) then
            Some('{ Shape.Obj(Vector.empty, Vector.empty) })
          else product(leaf.typeRef, seen)
        (name, shape)
      }
      if cases.isEmpty || cases.exists(_._2.isEmpty) then None
      else
        val pairs = cases.map((name, s) => '{ (${ Expr(name) }, ${ s.get }) })
        Some('{ Shape.OneOf(Vector(${ Varargs(pairs) }*)) })

    def of(t0: TypeRepr, seen: Set[String], root: Boolean): Option[Expr[Shape]] =
      val t   = t0.widen.dealias
      val sym = t.typeSymbol
      if isA[String](t) || isA[Char](t) then scalar("string")
      else if isA[Boolean](t) then scalar("boolean")
      else if isA[Int](t) || isA[Short](t) || isA[Byte](t) then scalar("integer")
      else if isA[Long](t) then scalar("integer", Some("int64"))
      else if isA[BigInt](t) then scalar("integer")
      else if isA[Double](t) || isA[Float](t) || isA[BigDecimal](t) then scalar("number")
      else if isA[java.math.BigDecimal](t) then scalar("number")
      else if isA[java.time.Instant](t) || isA[java.time.OffsetDateTime](t) then
        scalar("string", Some("date-time"))
      else if isA[java.time.LocalDate](t) then scalar("string", Some("date"))
      else if isA[java.time.Duration](t) then scalar("string", Some("duration"))
      else if isA[java.util.UUID](t) then scalar("string", Some("uuid"))
      else if t <:< TypeRepr.of[Option[Any]] then
        of(argOf(t, TypeRepr.of[Option[Any]].typeSymbol, 0), seen, false)
          .map(s => '{ Shape.Nullable($s) })
      else if t <:< TypeRepr.of[collection.Map[?, ?]] then
        of(argOf(t, TypeRepr.of[collection.Map[?, ?]].typeSymbol, 1), seen, false)
          .map(s => '{ Shape.Dict($s) })
      else if t <:< TypeRepr.of[Iterable[Any]] then
        of(argOf(t, TypeRepr.of[Iterable[Any]].typeSymbol, 0), seen, false)
          .map(s => '{ Shape.Arr($s) })
      else if sym == defn.ArrayClass then
        of(t.typeArgs.head, seen, false).map(s => '{ Shape.Arr($s) })
      else if t <:< TypeRepr.of[Tuple] then
        why = s"no shape for ${t.show}: a tuple is written as an array" :: why
        None
      else if (isCaseClass(sym) || isSum(sym)) && (seen(fullName(t)) || done(fullName(t))) then
        named(t, seen, _ => None)
      else
        (if root then None else found(t)).orElse {
          if t <:< TypeRepr.of[AnyVal] && isCaseClass(sym) && sym.caseFields.sizeIs == 1 then
            of(t.memberType(sym.caseFields.head), seen, false)
          else if companionCodec(t) && !insideCompanion(t) then
            why = s"${t.show} has a JsonValueCodec in its companion and no ShapeOf: give it one " +
              s"beside the codec, `given ShapeOf[${simpleName(t)}] = ShapeOf.derived` for a codec " +
              "that is Codecs.make, or ShapeOf.of(<JSON Schema>) for one written by hand" :: why
            None
          else if isCaseClass(sym) then named(t, seen, product(t, _))
          else if isSum(sym) then named(t, seen, sum(t, _))
          else
            why = s"no shape can be derived for ${t.show}" :: why
            None
        }

    val shape     = of(TypeRepr.of[A], Set.empty, root)
    val collected = '{ Vector[Vector[Named]](${ Varargs(defs.toList) }*).flatten }
    (shape, collected, Option.when(why.nonEmpty)(why.mkString("; ")))
