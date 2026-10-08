package com.thinkmorestupidless.ankka.graph.sink

import com.thinkmorestupidless.ankka.core.graph.{GraphDelta, PropertyValue}

/**
 * The reference store: the contract's rules over a map, for tests and for a service that wants a
 * graph it can query in memory. Every operation is synchronised, since a parallel sink applies
 * deltas of several partitions at once.
 */
final class InMemoryGraphStore extends GraphStore:

  import InMemoryGraphStore.*

  @volatile private var held: Map[String, Element] = Map.empty

  /** Every element by its key, as the store holds it now. */
  def elements: Map[String, Element] = held

  def node(id: String): Option[Element] = held.get(GraphDelta.nodeKey(id))

  def edge(id: String): Option[Element] = held.get(GraphDelta.edgeKey(id))

  def clear(): Unit = synchronized { held = Map.empty }

  def apply(delta: GraphDelta): Unit = synchronized {
    if delta.element == GraphDelta.Element.Edge then
      // An edge's endpoints are nodes the store may not hold yet: placeholders, replaced by the
      // nodes' own deltas when they arrive.
      for endpoint <- delta.from.toVector ++ delta.to.toVector do
        val key = GraphDelta.nodeKey(endpoint)
        if !held.contains(key) then
          held = held.updated(
            key,
            Element(GraphDelta.Element.Node, endpoint, Placeholder, deleted = false)
          )
    val key     = delta.key
    val stored  = held.get(key)
    val applies = stored.forall(_.version < delta.version)
    if applies then
      val next =
        if delta.isTombstone then
          Element(
            delta.element,
            delta.id,
            delta.version,
            deleted = true,
            edgeType = delta.edgeType.orElse(stored.flatMap(_.edgeType)),
            from = delta.from.orElse(stored.flatMap(_.from)),
            to = delta.to.orElse(stored.flatMap(_.to))
          )
        else
          Element(
            delta.element,
            delta.id,
            delta.version,
            deleted = false,
            delta.labels,
            delta.properties,
            delta.edgeType,
            delta.from,
            delta.to
          )
      held = held.updated(key, next)
  }

object InMemoryGraphStore:

  /** The version of a placeholder endpoint: below every delta's. */
  val Placeholder: Long = -1L

  /** One element as the store holds it: a node or a relationship, at a version, deleted or not. */
  final case class Element(
      element: GraphDelta.Element,
      id: String,
      version: Long,
      deleted: Boolean,
      labels: Vector[String] = Vector.empty,
      properties: Map[String, PropertyValue] = Map.empty,
      edgeType: Option[String] = None,
      from: Option[String] = None,
      to: Option[String] = None
  ):
    def isPlaceholder: Boolean = version == Placeholder
