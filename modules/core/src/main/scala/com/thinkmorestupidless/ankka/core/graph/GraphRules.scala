package com.thinkmorestupidless.ankka.core.graph

import com.thinkmorestupidless.ankka.core.graph.GraphDelta.{Element, Kind}

/**
 * The rules of an element, in one place: what the builder refuses where an element is described,
 * what is refused of a change's result as a whole, and what the reader refuses of a record.
 *
 * They are the merge sink's own rules, so that what a consumer publishes is what the sink accepts,
 * with two of ankka's on top: a version is at least 1, because 0 is what a change with no sequence
 * number presents, and an element appears once in a result.
 */
private[ankka] object GraphRules:

  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r
  private val Reserved   = Set("id", "_version", "_deleted")

  private def refuse(why: String, message: String): Nothing =
    throw GraphElementRefused(why, message)

  // ── Describing an element: the version is not known yet, and is 0 until it is ────────────────

  def node(id: String, labels: Seq[String], properties: Map[String, Any]): GraphDelta =
    val what = s"node '$id'"
    GraphDelta(
      Kind.Node,
      Element.Node,
      idOf("a node", id),
      0L,
      labels.toVector.map(identifier(what, "label", _)),
      None,
      None,
      None,
      propertiesOf(what, properties)
    )

  def edge(
      id: String,
      edgeType: String,
      from: String,
      to: String,
      properties: Map[String, Any]
  ): GraphDelta =
    val what           = s"edge '$id'"
    val checkedId      = idOf("an edge", id)
    val (t, f, target) = endpoints(what, edgeType, from, to)
    GraphDelta(
      Kind.Edge,
      Element.Edge,
      checkedId,
      0L,
      Vector.empty,
      Some(t),
      Some(f),
      Some(target),
      propertiesOf(what, properties)
    )

  def tombstoneNode(id: String): GraphDelta =
    GraphDelta(
      Kind.Tombstone,
      Element.Node,
      idOf("a node's tombstone", id),
      0L,
      Vector.empty,
      None,
      None,
      None,
      Map.empty
    )

  def tombstoneEdge(id: String, edgeType: String, from: String, to: String): GraphDelta =
    val what           = s"the tombstone of edge '$id'"
    val checkedId      = idOf("an edge's tombstone", id)
    val (t, f, target) = endpoints(what, edgeType, from, to)
    GraphDelta(
      Kind.Tombstone,
      Element.Edge,
      checkedId,
      0L,
      Vector.empty,
      Some(t),
      Some(f),
      Some(target),
      Map.empty
    )

  /** A version the author stated. */
  def stated(what: String, version: Long): Long =
    if version < 1 then
      refuse("version", s"$what: a version must be at least 1, and $version was stated")
    else version

  /**
   * A change's result: each element at the version it stated, else at the change's sequence number,
   * and no element twice.
   */
  def resolve(elements: Seq[(GraphDelta, Option[Long])], sequenceNumber: Long): Vector[GraphDelta] =
    val resolved = elements.toVector.map { (template, version) =>
      version match
        case Some(v)                     => template.copy(version = v)
        case None if sequenceNumber >= 1 => template.copy(version = sequenceNumber)
        case None =>
          refuse(
            "no-sequence",
            s"${describe(template)} states no version and this change has no sequence number " +
              "(its source is a topic); state a version"
          )
    }
    resolved.groupBy(_.key).collectFirst { case (key, several) if several.sizeIs > 1 => key } match
      case Some(key) =>
        refuse(
          "duplicate",
          s"the element '$key' is in this result ${resolved.count(_.key == key)} times; an " +
            "element is published once for a change"
        )
      case None => resolved

  def describe(delta: GraphDelta): String =
    if delta.isTombstone then s"the tombstone of ${delta.element.name} '${delta.id}'"
    else s"${delta.element.name} '${delta.id}'"

  private def idOf(what: String, id: String): String =
    if id == null || id.isEmpty then refuse("id", s"$what needs an id that is not empty") else id

  private def identifier(what: String, role: String, value: String): String =
    if value != null && Identifier.matches(value) then value
    else
      refuse("identifier", s"$what: $role '$value' is not an identifier ([A-Za-z_][A-Za-z0-9_]*)")

  private def endpoints(
      what: String,
      edgeType: String,
      from: String,
      to: String
  ): (String, String, String) =
    def present(role: String, value: String): String =
      if value == null || value.isEmpty then
        refuse("endpoints", s"$what needs a $role that is not empty")
      else value
    val t      = present("type", edgeType)
    val f      = present("from", from)
    val target = present("to", to)
    (identifier(what, "type", t), f, target)

  private def propertiesOf(what: String, properties: Map[String, Any]): Map[String, PropertyValue] =
    properties.map { (name, value) =>
      if Reserved(name) then
        refuse("reserved", s"$what: the property name '$name' is the sink's own; choose another")
      name -> propertyOf(what, name, value)
    }

  private enum ScalarKind:
    case Text, Flag, Whole, Fraction

  private def propertyOf(what: String, name: String, value: Any): PropertyValue =
    def bad(reason: String): Nothing =
      refuse(
        "property-value",
        s"$what: property '$name' $reason; a property is a string, a number, a boolean, or a " +
          "list of one of those that is not empty"
      )
    def scalar(item: Any): (ScalarKind, Scalar) = item match
      case text: String                         => ScalarKind.Text  -> text
      case flag: Boolean                        => ScalarKind.Flag  -> flag
      case whole: Int                           => ScalarKind.Whole -> whole.toLong
      case whole: Long                          => ScalarKind.Whole -> whole
      case whole: Short                         => ScalarKind.Whole -> whole.toLong
      case whole: Byte                          => ScalarKind.Whole -> whole.toLong
      case number: BigInt                       => ScalarKind.Whole -> wholeOf(BigDecimal(number))
      case number: BigDecimal if number.isWhole => ScalarKind.Whole -> wholeOf(number)
      case number: BigDecimal                   => fraction(number.toDouble)
      case number: Float                        => double(number.toDouble)
      case number: Double                       => double(number)
      case null                                 => bad("is null")
      case _: Seq[?]                            => bad("has a list inside a list")
      case other                                => bad(s"is a ${other.getClass.getSimpleName}")
    def wholeOf(number: BigDecimal): Long =
      if number.isValidLong then number.toLongExact
      else
        refuse(
          "integer-range",
          s"$what: property '$name' holds $number, a whole number that does not fit 64 bits"
        )
    def double(number: Double): (ScalarKind, Scalar) =
      if number.isNaN || number.isInfinite then bad(s"is $number, which is not a finite number")
      else if number.isWhole then ScalarKind.Whole -> wholeOf(BigDecimal(number))
      else ScalarKind.Fraction                     -> number
    def fraction(number: Double): (ScalarKind, Scalar) =
      if number.isNaN || number.isInfinite then bad("is too large to be a finite number")
      else ScalarKind.Fraction -> number

    value match
      case items: Seq[?] =>
        if items.isEmpty then bad("is an empty list")
        val scalars = items.toVector.map(scalar)
        if scalars.map(_._1).distinct.sizeIs > 1 then bad("is a list of more than one kind")
        scalars.map(_._2)
      case other => scalar(other)._2

  // ── Reading a record: the contract's own validation, in the sink's words ─────────────────────

  def fromJson(json: GraphJson.Obj): Either[String, GraphDelta] =
    try Right(read(json))
    catch
      case ReadFailed(problem)          => Left(problem)
      case refused: GraphElementRefused => Left(refused.getMessage)

  private final case class ReadFailed(problem: String) extends RuntimeException(problem)
  private def fail(problem: String): Nothing = throw ReadFailed(problem)

  private def read(json: GraphJson.Obj): GraphDelta =
    val kind = json.field("kind") match
      case None                      => fail("kind missing")
      case Some(GraphJson.Str(name)) => name
      case Some(_)                   => fail("kind missing")
    val id = json.field("id") match
      case Some(GraphJson.Str(id)) if id.nonEmpty => id
      case _                                      => fail("id missing or empty")
    val version = json.field("version") match
      case Some(GraphJson.Num(n)) if n >= 0 && n.isWhole && n.isValidLong => n.toLongExact
      case _ => fail("version is not a non-negative integer")

    def text(name: String): Option[String] = json.field(name) match
      case Some(GraphJson.Str(value)) if value.nonEmpty => Some(value)
      case _                                            => None
    def endpointsOr(problem: String): (String, String, String) =
      (text("type"), text("from"), text("to")) match
        case (Some(t), Some(f), Some(target)) if Identifier.matches(t) => (t, f, target)
        case _                                                         => fail(problem)
    def properties(what: String): Map[String, PropertyValue] = json.field("properties") match
      case None | Some(GraphJson.Null) => Map.empty
      case Some(GraphJson.Obj(fields)) =>
        fields.map { (name, value) =>
          if Reserved(name) then fail(s"property '$name' is reserved")
          name -> (try propertyOf(what, name, plain(value))
          catch
            case _: GraphElementRefused =>
              fail(s"property '$name' is not a scalar or array of scalars"))
        }.toMap
      case Some(_) => fail("properties must be an object")

    kind match
      case "node" =>
        val labels = json.field("labels") match
          case None | Some(GraphJson.Null) => Vector.empty
          case Some(GraphJson.Arr(items)) =>
            items.map {
              case GraphJson.Str(label) if Identifier.matches(label) => label
              case _ => fail("labels must be an array of identifiers")
            }
          case Some(_) => fail("labels must be an array of identifiers")
        GraphDelta(
          Kind.Node,
          Element.Node,
          id,
          version,
          labels,
          None,
          None,
          None,
          properties(s"node '$id'")
        )
      case "edge" =>
        val (t, f, target) = endpointsOr("edge needs type, from and to")
        GraphDelta(
          Kind.Edge,
          Element.Edge,
          id,
          version,
          Vector.empty,
          Some(t),
          Some(f),
          Some(target),
          properties(s"edge '$id'")
        )
      case "tombstone" =>
        text("element") match
          case Some("node") =>
            GraphDelta(
              Kind.Tombstone,
              Element.Node,
              id,
              version,
              Vector.empty,
              None,
              None,
              None,
              Map.empty
            )
          case Some("edge") =>
            val (t, f, target) = endpointsOr("tombstone of an edge needs type, from and to")
            GraphDelta(
              Kind.Tombstone,
              Element.Edge,
              id,
              version,
              Vector.empty,
              Some(t),
              Some(f),
              Some(target),
              Map.empty
            )
          case _ => fail("tombstone needs element 'node' or 'edge'")
      case other => fail(s"unknown kind '$other'")

  /** JSON as the plain values the builder takes, numbers exact. */
  def plain(json: GraphJson): Any = json match
    case GraphJson.Str(text)   => text
    case GraphJson.Num(number) => number
    case GraphJson.Bool(flag)  => flag
    case GraphJson.Null        => null
    case GraphJson.Arr(items)  => items.map(plain)
    case GraphJson.Obj(fields) => fields.map((name, value) => name -> plain(value)).toMap
