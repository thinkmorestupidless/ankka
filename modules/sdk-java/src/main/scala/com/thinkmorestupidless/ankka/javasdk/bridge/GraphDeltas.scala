package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.graph.{GraphDelta as ScalaDelta, PropertyValue}
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.component.GraphDelta

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Java graph delta over the Scala one: one reader and one writer, the core's. */
object GraphDeltas:

  def read(value: Array[Byte]): GraphDelta = toJava(refused(ScalaDelta.read(value)))

  def read(key: String, value: Array[Byte]): GraphDelta =
    toJava(refused(ScalaDelta.read(Option(key), value)))

  val serializer: Serializer[GraphDelta] = new Serializer[GraphDelta]:
    def manifest(): String                        = ScalaDelta.SchemaName
    def toBytes(delta: GraphDelta): Array[Byte]   = ScalaDelta.serializer.toBytes(toScala(delta))
    def fromBytes(bytes: Array[Byte]): GraphDelta = read(bytes)
    override def toString: String = s"GraphDelta.serializer(${ScalaDelta.SchemaName})"

  private def refused(read: Either[String, ScalaDelta]): ScalaDelta =
    read.fold(problem => throw IllegalArgumentException(problem), identity)

  private[ankka] def toJava(delta: ScalaDelta): GraphDelta =
    GraphDelta(
      delta.kind.name,
      delta.element.name,
      delta.id,
      delta.version,
      delta.labels.asJava,
      delta.edgeType.toJava,
      delta.from.toJava,
      delta.to.toJava,
      java.util.Collections.unmodifiableMap(
        java.util.LinkedHashMap[String, Object](
          delta.properties.view.mapValues(javaValue).toMap.asJava
        )
      )
    )

  private def javaValue(value: PropertyValue): Object = value match
    case items: Seq[?] => items.map(item => javaValue(item.asInstanceOf[PropertyValue])).asJava
    case whole: Int    => java.lang.Long.valueOf(whole.toLong)
    case other         => other.asInstanceOf[Object]

  // A Java delta came from `read`, so its fields name a kind and an element the core knows.
  private def toScala(delta: GraphDelta): ScalaDelta =
    ScalaDelta(
      ScalaDelta.Kind.values.find(_.name == delta.kind).get,
      ScalaDelta.Element.values.find(_.name == delta.element).get,
      delta.id,
      delta.version,
      delta.labels.asScala.toVector,
      delta.edgeType.toScala,
      delta.from.toScala,
      delta.to.toScala,
      delta.properties.asScala.view.mapValues(scalaValue).toMap
    )

  private def scalaValue(value: Object): PropertyValue = value match
    case items: java.util.List[?] =>
      items.asScala.toVector
        .map(item => scalaValue(item.asInstanceOf[Object]))
        .asInstanceOf[PropertyValue]
    case whole: java.lang.Long    => whole.longValue
    case number: java.lang.Double => number.doubleValue
    case flag: java.lang.Boolean  => flag.booleanValue
    case text: String             => text
    case other =>
      throw IllegalArgumentException(s"$other is not a property value a graph delta carries")
