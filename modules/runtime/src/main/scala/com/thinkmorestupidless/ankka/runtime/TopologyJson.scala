package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{
  BuildInfo,
  ComponentDescriptor,
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
 * discovery, a route by an endpoint. Nothing here comes from a request, so no entity id, session id
 * or filled-in path can appear.
 */
object TopologyJson:

  /** Left to right: what a request reaches first, to what it finally writes to. */
  private[runtime] def layerOf(kind: ComponentKind): Int = kind match
    case ComponentKind.Endpoint | ComponentKind.TimedAction                           => 0
    case ComponentKind.Workflow | ComponentKind.Agent | ComponentKind.AutonomousAgent => 1
    case ComponentKind.EventSourcedEntity | ComponentKind.KeyValueEntity              => 2
    case ComponentKind.View | ComponentKind.Consumer                                  => 3

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
    val components = registry.components
      .filter(_.kind != ComponentKind.Endpoint)
      .map(component)
    val endpoints = routes.groupBy(_.endpoint).toVector.map((id, served) => endpoint(id, served))
    val nodes     = (components ++ endpoints).sortBy(n => (n.layer, n.id)).map(_.json)

    s"""{"service":{"name":${Json.str(serviceName)},"runtime":${Json.str(BuildInfo.version)},""" +
      s""""instance":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)}},""" +
      s""""window":{"seconds":0,"since":${Json.str(startedAt)},"calls":0},""" +
      s""""nodes":${nodes.mkString("[", ",", "]")},"declared":[],"calls":[]}"""

  private final case class Node(id: String, layer: Int, json: String)

  private def component(descriptor: ComponentDescriptor): Node =
    val id       = descriptor.componentId.toString
    val layer    = layerOf(descriptor.kind)
    val handlers = descriptor.declaredHandlers.map(handler).mkString("[", ",", "]")
    Node(
      id,
      layer,
      s"""{"id":${Json.str(id)},"kind":${Json.str(descriptor.kind.toString)},"layer":$layer,""" +
        s""""platform":${descriptor.platform},"handlers":$handlers}"""
    )

  private def handler(declared: DeclaredHandler): String =
    s"""{"name":${Json.str(declared.name)},"type":${Json.str(
        declared.kind.toString.toLowerCase
      )}}"""

  /** An endpoint's handlers are its routes, each by its method and its path as a template. */
  private def endpoint(id: String, served: Vector[ServedRoute]): Node =
    val layer = layerOf(ComponentKind.Endpoint)
    val handlers = served
      .sortBy(r => (r.path, r.method))
      .map(r =>
        s"""{"name":${Json.str(s"${r.method} ${r.path}")},"type":"route",""" +
          s""""streaming":${r.streaming}}"""
      )
      .mkString("[", ",", "]")
    Node(
      id,
      layer,
      s"""{"id":${Json.str(id)},"kind":${Json.str(ComponentKind.Endpoint.toString)},""" +
        s""""layer":$layer,"platform":false,"handlers":$handlers}"""
    )
