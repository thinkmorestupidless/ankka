package com.thinkmorestupidless.ankka.core.graph

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * A scalar property value. An `Int` is kept as a `Long`, and so is a `Double` with a whole value.
 */
type Scalar = String | Boolean | Int | Long | Double

/** A property value: a scalar, or a non-empty list of scalars of one kind. */
type PropertyValue = Scalar | Seq[Scalar]

/**
 * An element of a graph refused: what is wrong with it, in words, and `why`, the name of the rule
 * it broke (`id`, `endpoints`, `identifier`, `reserved`, `property-value`, `integer-range`,
 * `version`, `duplicate`, `no-sequence`).
 */
final class GraphElementRefused(val why: String, message: String)
    extends IllegalArgumentException(message)

/**
 * One graph delta under the contract `ankka.graph-delta.v1`: a node or an edge as it now is, whole,
 * or a tombstone marking one deleted, at a version.
 *
 * The contract is ankka-flow's, whose merge sink applies a delta when its version is newer than
 * what the graph holds. A value of this type is always valid — it is made only by the builder and
 * the reader — so what is published is something the sink accepts.
 *
 * `kind` is what the delta is; `element` is which id space it is about, since nodes and edges are
 * separate ones. `edgeType`, `from` and `to` are set for an edge and an edge's tombstone. Property
 * values are normalised: every integer is a `Long`, a float with a whole value is that `Long` (the
 * sink stores it as one), and a list is a `Vector`. Two deltas are equal when the sink would read
 * them the same.
 */
final case class GraphDelta private[ankka] (
    kind: GraphDelta.Kind,
    element: GraphDelta.Element,
    id: String,
    version: Long,
    labels: Vector[String],
    edgeType: Option[String],
    from: Option[String],
    to: Option[String],
    properties: Map[String, PropertyValue]
):

  /** The record key every delta for this element is published under: `node:<id>` or `edge:<id>`. */
  def key: String = s"${element.name}:$id"

  def isTombstone: Boolean = kind == GraphDelta.Kind.Tombstone

object GraphDelta:

  /** The contract's name: the manifest of a delta's payload and the `ce-type` of its record. */
  val SchemaName: String = "ankka.graph-delta.v1"

  enum Kind(val name: String):
    case Node      extends Kind("node")
    case Edge      extends Kind("edge")
    case Tombstone extends Kind("tombstone")

  /** Which id space: a node and an edge with the same id are different elements. */
  enum Element(val name: String):
    case Node extends Element("node")
    case Edge extends Element("edge")

  def nodeKey(id: String): String = s"node:$id"
  def edgeKey(id: String): String = s"edge:$id"

  /**
   * A delta as JSON, under the manifest `ankka.graph-delta.v1`. Reading refuses what `read` does.
   */
  val serializer: Serializer[GraphDelta] = new Serializer[GraphDelta]:
    val manifest: String                        = SchemaName
    def toBytes(delta: GraphDelta): Array[Byte] = write(delta)
    def fromBytes(bytes: Array[Byte]): GraphDelta =
      read(bytes).fold(problem => throw IllegalArgumentException(problem), identity)
    override def toString: String = s"GraphDelta.serializer($SchemaName)"

  /** Reads a record's value. `Left` says what is wrong, in the sink's words. */
  def read(value: Array[Byte]): Either[String, GraphDelta] =
    GraphJson.parse(value) match
      case Right(json @ GraphJson.Obj(_)) => GraphRules.fromJson(json)
      case _                              => Left("not a JSON object")

  /** As `read`, and the record's key must be the delta's element key. */
  def read(key: Option[String], value: Array[Byte]): Either[String, GraphDelta] =
    read(value).flatMap { delta =>
      key match
        case Some(found) if found == delta.key => Right(delta)
        case Some(found) => Left(s"key '$found' is not this delta's element key '${delta.key}'")
        case None        => Left(s"no key; this delta's element key is '${delta.key}'")
    }

  private def write(delta: GraphDelta): Array[Byte] = writeToArray(delta)(using writer)

  // Field order as the contract's examples have it. `labels` and `properties` are always written
  // for a node or an edge, empty when there are none.
  private val writer: JsonValueCodec[GraphDelta] = new JsonValueCodec[GraphDelta]:
    def nullValue: GraphDelta = null
    def decodeValue(in: JsonReader, default: GraphDelta): GraphDelta =
      in.decodeError("a graph delta is read with GraphDelta.read")

    def encodeValue(delta: GraphDelta, out: JsonWriter): Unit =
      out.writeObjectStart()
      out.writeKey("kind")
      out.writeVal(delta.kind.name)
      if delta.isTombstone then
        out.writeKey("element")
        out.writeVal(delta.element.name)
      out.writeKey("id")
      out.writeVal(delta.id)
      out.writeKey("version")
      out.writeVal(delta.version)
      if delta.kind == Kind.Node then
        out.writeKey("labels")
        out.writeArrayStart()
        delta.labels.foreach(out.writeVal)
        out.writeArrayEnd()
      delta.edgeType.foreach { edgeType =>
        out.writeKey("type")
        out.writeVal(edgeType)
      }
      delta.from.foreach { from =>
        out.writeKey("from")
        out.writeVal(from)
      }
      delta.to.foreach { to =>
        out.writeKey("to")
        out.writeVal(to)
      }
      if !delta.isTombstone then
        out.writeKey("properties")
        out.writeObjectStart()
        delta.properties.toVector.sortBy(_._1).foreach { (name, value) =>
          out.writeKey(name)
          writeValue(value, out)
        }
        out.writeObjectEnd()
      out.writeObjectEnd()

    private def writeValue(value: PropertyValue, out: JsonWriter): Unit = value match
      case text: String   => out.writeVal(text)
      case flag: Boolean  => out.writeVal(flag)
      case whole: Long    => out.writeVal(whole)
      case whole: Int     => out.writeVal(whole)
      case number: Double => out.writeVal(number)
      case items: Seq[?] =>
        out.writeArrayStart()
        items.foreach(item => writeValue(item.asInstanceOf[PropertyValue], out))
        out.writeArrayEnd()
