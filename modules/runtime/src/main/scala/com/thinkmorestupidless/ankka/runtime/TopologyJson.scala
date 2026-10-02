package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{
  BuildInfo,
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  DeclaredHandler
}

/**
 * A service's topology, as one document: what it is made of and how the parts are connected.
 *
 * The one renderer. A local service serves this to the console and a deployed one to the control
 * plane, and both must say the same thing about the same service, so both call this and neither
 * builds any part of it for itself. For the same reason the document carries each node's `layer`:
 * two consoles draw it, and a rule each worked out separately is a rule they would come to disagree
 * on.
 *
 * Everything in it is declared: a component by being registered, a handler on a companion or in
 * discovery, a route by an endpoint, a connection by a view's or a consumer's source. Nothing here
 * comes from a request, so no entity id, session id or filled-in path can appear.
 */
object TopologyJson:

  /** Left to right: what a request reaches first, to what it finally writes to. */
  private[runtime] def layerOf(kind: ComponentKind): Int = kind match
    case ComponentKind.Endpoint | ComponentKind.TimedAction                           => 0
    case ComponentKind.Workflow | ComponentKind.Agent | ComponentKind.AutonomousAgent => 1
    case ComponentKind.EventSourcedEntity | ComponentKind.KeyValueEntity              => 2
    case ComponentKind.View | ComponentKind.Consumer                                  => 3

  /** Topics are drawn last: what a consumer publishes to is where the service's work leaves it. */
  private val TopicLayer = 4

  /**
   * @param startedAt
   *   when this instance started, which is also where its window begins until it has counted calls
   *   for longer than the window is.
   */
  def render(
      serviceName: String,
      instanceId: String,
      startedAt: String,
      registry: ComponentRegistry,
      routes: Vector[ServedRoute]
  ): String =
    // An endpoint is drawn from the routes it serves. A remote one is also in the registry, by the
    // id it was declared with; listing it from there as well would draw it twice.
    val registered = registry.components.filter(_.kind != ComponentKind.Endpoint)
    val ids        = nodeIds(registered)
    val components = registered.map(d => component(ids(d), d))
    val endpoints  = routes.groupBy(_.endpoint).toVector.map((id, served) => endpoint(id, served))

    val connections = registered.flatMap(d => declared(d, ids(d), registered, ids))
    // A topic or an outside component is a node because something declared a connection to it.
    val others = connections.flatMap(c => Vector(c.fromNode, c.toNode).flatten).distinctBy(_.id)

    val nodes = (components ++ endpoints ++ others).sortBy(n => (n.layer, n.id)).map(_.json)
    val edges = connections
      .sortBy(c => (c.from, c.to, c.kind))
      .map(c =>
        s"""{"from":${Json.str(c.from)},"to":${Json.str(c.to)},"kind":${Json.str(c.kind)}}"""
      )

    s"""{"service":{"name":${Json.str(serviceName)},"runtime":${Json.str(BuildInfo.version)},""" +
      s""""instance":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)}},""" +
      s""""window":{"seconds":0,"since":${Json.str(startedAt)},"calls":0},""" +
      s""""nodes":${nodes.mkString("[", ",", "]")},""" +
      s""""declared":${edges.mkString("[", ",", "]")},"calls":[]}"""

  private final case class Node(id: String, layer: Int, json: String)

  /** A declared connection, and the node it needs drawn at either end when no component is one. */
  private final case class Connection(
      from: String,
      to: String,
      kind: String,
      fromNode: Option[Node] = None,
      toNode: Option[Node] = None
  )

  /**
   * What each component is called in the document.
   *
   * Its component id, which is what a developer wrote. Ids are unique only within a kind, so an
   * entity and the view over it may share one; a connection has to say which it means, and then
   * each is named with its kind as well (`view:cart`). A component id cannot hold a colon, so
   * neither form can be mistaken for the other, nor for a topic or an endpoint.
   */
  private def nodeIds(registered: Vector[ComponentDescriptor]): Map[ComponentDescriptor, String] =
    val shared = registered.groupBy(_.componentId).collect { case (id, ds) if ds.sizeIs > 1 => id }
    registered.map { d =>
      val id = d.componentId.toString
      d -> (if shared.exists(_ == d.componentId) then s"${d.kind.toString.toLowerCase}:$id" else id)
    }.toMap

  private def component(id: String, descriptor: ComponentDescriptor): Node =
    val layer    = layerOf(descriptor.kind)
    val handlers = descriptor.declaredHandlers.map(handler).mkString("[", ",", "]")
    node(id, descriptor.kind.toString, layer, descriptor.platform, handlers)

  private def node(
      id: String,
      kind: String,
      layer: Int,
      platform: Boolean,
      handlers: String
  ): Node =
    Node(
      id,
      layer,
      s"""{"id":${Json.str(id)},"kind":${Json.str(kind)},"layer":$layer,""" +
        s""""platform":$platform,"handlers":$handlers}"""
    )

  private def handler(declared: DeclaredHandler): String =
    val kind = declared.kind.toString.toLowerCase
    s"""{"name":${Json.str(declared.name)},"type":${Json.str(kind)}}"""

  /** An endpoint's handlers are its routes, each by its method and its path as a template. */
  private def endpoint(id: String, served: Vector[ServedRoute]): Node =
    val handlers = served
      .sortBy(r => (r.path, r.method))
      .map(r =>
        s"""{"name":${Json.str(s"${r.method} ${r.path}")},"type":"route",""" +
          s""""streaming":${r.streaming}}"""
      )
      .mkString("[", ",", "]")
    node(
      id,
      ComponentKind.Endpoint.toString,
      layerOf(ComponentKind.Endpoint),
      platform = false,
      handlers
    )

  private def topic(name: String): Node =
    node(s"topic:$name", "Topic", TopicLayer, platform = false, handlers = "[]")

  /**
   * The connections one component declared: what it reads, and what it publishes to.
   *
   * Exactly one for a source and one for a destination, so a reader may say nothing else feeds a
   * view. Read from the descriptor and from nothing else: not from what has been delivered, and not
   * from what a component is called.
   */
  private def declared(
      descriptor: ComponentDescriptor,
      id: String,
      registered: Vector[ComponentDescriptor],
      ids: Map[ComponentDescriptor, String]
  ): Vector[Connection] =
    def entity(component: ComponentId, kind: ComponentKind, connection: String): Connection =
      registered.find(d => d.kind == kind && d.componentId == component) match
        case Some(source) => Connection(ids(source), id, connection)
        // Not one of this service's components: said so, one layer before what reads it, rather
        // than left out or drawn as though this service ran it.
        case None =>
          val outside = node(
            s"external:$component",
            "ExternalComponent",
            layerOf(descriptor.kind) - 1,
            platform = false,
            handlers = "[]"
          )
          Connection(outside.id, id, connection, fromNode = Some(outside))

    val source = DeclaredConnections.sourceOf(descriptor).map {
      case DeclaredSource.Events(component) =>
        entity(component, ComponentKind.EventSourcedEntity, "events")
      case DeclaredSource.State(component) =>
        entity(component, ComponentKind.KeyValueEntity, "state")
      case DeclaredSource.Topic(name) =>
        val from = topic(name)
        Connection(from.id, id, "topic-subscription", fromNode = Some(from))
    }
    val destination = DeclaredConnections.destinationOf(descriptor).map { name =>
      val to = topic(name)
      Connection(id, to.id, "topic-publication", toNode = Some(to))
    }
    source.toVector ++ destination
