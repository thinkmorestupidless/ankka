package com.thinkmorestupidless.ankka.runtime

import net.sf.jsqlparser.expression.{
  CastExpression,
  Expression,
  ExpressionVisitorAdapter,
  Function,
  JdbcNamedParameter,
  JsonExpression,
  LongValue,
  StringValue
}
import net.sf.jsqlparser.expression.operators.relational.{EqualsTo, ParenthesedExpressionList}
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.{PlainSelect, SelectItem}
import net.sf.jsqlparser.util.TablesNamesFinder

import java.util.{Collections, IdentityHashMap}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * A view's personal fields, as its descriptor gives them (feature 042, R12): their paths into the
 * row's JSON, and the ones marked for lookup.
 */
private[ankka] final case class ViewFields(personal: Vector[String], lookup: Vector[String]):
  def isEmpty: Boolean = personal.isEmpty

private[ankka] object ViewFields:
  val none: ViewFields = ViewFields(Vector.empty, Vector.empty)

/**
 * What a declared query may do with a view's personal fields (feature 042, R12, R13). A personal
 * field holds an envelope, so a predicate on it compares ciphertext: refused at start, naming the
 * view, the query and the field, unless the field is marked for lookup and the predicate is an
 * equality with a value. That equality is rewritten to compare the row's lookup token,
 * `lookup ->> '<path>' = :value`, and the value's name is answered so the query's caller turns the
 * value into its token. Selecting a personal field is not a predicate and is left alone: the row is
 * decoded, and the field opened, as every row is. The data subject beside an envelope is in the
 * clear, so `<field> -> 'subject'` may be compared.
 */
private[ankka] object PersonalQueryCheck:

  /** The statement's text after any rewrite, and each value compared with a lookup field. */
  final case class Checked(text: Option[String], lookups: Map[String, String])

  private val Clear = Set("subject", "project")

  /** A reference to a field of the row: the node, and its path as a view's fields are written. */
  private final case class Ref(node: Expression, path: String)

  def check(statement: Statement, fields: ViewFields): Either[String, Checked] =
    if fields.isEmpty then Right(Checked(None, Map.empty))
    else
      val found = Finder()
      statement.accept(found, null): Unit
      val selected = Collections.newSetFromMap(IdentityHashMap[Expression, java.lang.Boolean]())
      found.items.foreach(e => references(e).foreach(r => selected.add(r.node): Unit))
      val predicates = found.refs.filterNot(r => selected.contains(r.node))
      def personalOf(path: String): Option[String] =
        fields.personal.find(p =>
          path == p || (path.startsWith(p + ".") && !Clear(path.stripPrefix(p + "."))) ||
            p.startsWith(path + ".") || p.startsWith(path + "[")
        )
      // Each equality of a lookup field with a named value is rewritten.
      val rewritten = Collections.newSetFromMap(IdentityHashMap[Expression, java.lang.Boolean]())
      val lookups   = ArrayBuffer.empty[(String, String)]
      found.equalities.foreach { eq =>
        def side(field: Expression, value: Expression, put: Expression => Unit): Unit =
          (refOf(field), value) match
            case (Some(r), p: JdbcNamedParameter) if fields.lookup.contains(r.path) =>
              rewritten.add(field): Unit
              lookups += p.getName -> r.path
              put(lookupColumn(r.path))
            case _ => ()
        side(eq.getLeftExpression, eq.getRightExpression, eq.setLeftExpression(_): Unit)
        side(eq.getRightExpression, eq.getLeftExpression, eq.setRightExpression(_): Unit)
      }
      val refused = predicates.filterNot(r => rewritten.contains(r.node)).flatMap { r =>
        personalOf(r.path).map(p => (r, p))
      }
      refused.headOption match
        case Some((_, p)) if fields.lookup.contains(p) =>
          Left(
            s"which compares the personal field '$p' other than by equality with a value; a lookup " +
              s"field is compared only as <field> = :value"
          )
        case Some((_, p)) =>
          Left(
            s"which compares the personal field '$p', which holds ciphertext; mark it with " +
              s"lookup(_.${p.replace("[*]", "")}) to compare it by equality, or compare a field that is not personal"
          )
        case None =>
          val twice = lookups.groupBy(_._1).collectFirst {
            case (name, uses) if uses.map(_._2).distinct.size > 1 => name
          }
          val elsewhere = lookups
            .map(_._1)
            .distinct
            .find(name => found.parameters.count(_ == name) > lookups.count(_._1 == name))
          (twice, elsewhere) match
            case (Some(name), _) =>
              Left(s"whose value ':$name' is compared with two lookup fields; give each its own")
            case (_, Some(name)) =>
              Left(
                s"whose value ':$name' is compared with a lookup field and used elsewhere; a lookup " +
                  "field's value is its token, so give the other use a value of its own"
              )
            case _ =>
              if lookups.isEmpty then Right(Checked(None, Map.empty))
              else Right(Checked(Some(statement.toString), lookups.toMap))

  /** `lookup ->> '<path>'`: the row's token for a lookup field. */
  private def lookupColumn(path: String): Expression =
    val json = JsonExpression(Column("lookup"))
    json.addIdent(StringValue(path), "->>")
    json

  /** Whether `e` is the row's JSON: `payload`, cast, or in parentheses. */
  private def isPayload(e: Expression): Boolean = e match
    case c: Column                       => c.getColumnName.equalsIgnoreCase("payload")
    case c: CastExpression               => isPayload(c.getLeftExpression)
    case p: ParenthesedExpressionList[?] =>
      p.size == 1 && isPayload(p.get(0).asInstanceOf[Expression])
    case _ => false

  private def segments(e: Expression, operator: String): List[String] = e match
    case s: StringValue if operator.startsWith("#") =>
      s.getValue.stripPrefix("{").stripSuffix("}").split(",").toList.map(_.trim).map(numbered)
    case s: StringValue    => List(numbered(s.getValue))
    case _: LongValue      => List("[*]")
    case j: JsonExpression =>
      segments(j.getExpression, operator) ++
        j.getIdentList.asScala.toList.flatMap(en => segments(en.getKey, en.getValue))
    case _ => List("?")

  private def numbered(segment: String): String =
    if segment.nonEmpty && segment.forall(_.isDigit) then "[*]" else segment

  private def joined(parts: List[String]): String =
    parts.foldLeft("")((acc, s) => if s == "[*]" || acc.isEmpty then acc + s else s"$acc.$s")

  /** The path `e` reads from the row, when it reads one. */
  private def refOf(e: Expression): Option[Ref] = e match
    case j: JsonExpression if isPayload(j.getExpression) =>
      Some(
        Ref(
          j,
          joined(j.getIdentList.asScala.toList.flatMap(en => segments(en.getKey, en.getValue)))
        )
      )
    case f: Function
        if Set(
          "jsonb_extract_path",
          "jsonb_extract_path_text",
          "json_extract_path",
          "json_extract_path_text"
        )
          .contains(f.getName.toLowerCase) && f.getParameters != null && f.getParameters.size > 1 &&
          isPayload(f.getParameters.get(0).asInstanceOf[Expression]) =>
      val rest = f.getParameters.asScala.toList.drop(1).map(_.asInstanceOf[Expression])
      Some(Ref(f, joined(rest.flatMap(segments(_, "->")))))
    case _ => None

  /** Every reference to the row's fields under `e`. */
  private def references(e: Expression): Vector[Ref] =
    val out = ArrayBuffer.empty[Ref]
    e.accept(
      new ExpressionVisitorAdapter[Void]:
        override def visit[S](json: JsonExpression, context: S): Void =
          refOf(json).foreach(out += _)
          super.visit(json, context)
        override def visit[S](function: Function, context: S): Void =
          refOf(function).foreach(out += _)
          super.visit(function, context)
      ,
      null
    ): Unit
    out.toVector

  /** The references, equalities, values and select items of a whole statement. */
  private final class Finder extends TablesNamesFinder[Void]:
    val refs       = ArrayBuffer.empty[Ref]
    val equalities = ArrayBuffer.empty[EqualsTo]
    val parameters = ArrayBuffer.empty[String]
    val items      = ArrayBuffer.empty[Expression]
    init(false)

    override def visit[S](json: JsonExpression, context: S): Void =
      refOf(json).foreach(refs += _)
      super.visit(json, context)
    override def visit[S](function: Function, context: S): Void =
      refOf(function).foreach(refs += _)
      super.visit(function, context)
    override def visit[S](equalsTo: EqualsTo, context: S): Void =
      equalities += equalsTo
      super.visit(equalsTo, context)
    override def visit[S](parameter: JdbcNamedParameter, context: S): Void =
      parameters += parameter.getName
      super.visit(parameter, context)
    override def visit[S](select: PlainSelect, context: S): Void =
      Option(select.getSelectItems).foreach(
        _.asScala.foreach((i: SelectItem[?]) => Option(i.getExpression).foreach(items += _))
      )
      super.visit(select, context)
