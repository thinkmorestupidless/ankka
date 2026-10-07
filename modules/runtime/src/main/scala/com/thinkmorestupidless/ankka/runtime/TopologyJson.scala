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
 * Every name in it is declared: a component by being registered, a handler on a companion or in
 * discovery, a route by an endpoint, a connection by a view's or a consumer's source. A call is
 * counted only under such names, so nothing here comes from a request and no entity id, session id
 * or filled-in path can appear.
 *
 * Two things in it are of different standing and are kept apart. `declared` is complete: every
 * source and destination there is. `calls` is observed: the pairs of handlers that called each
 * other in the window, and nothing about the ones that did not.
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

  /** A running service's topology as it is now: what it registered, and what it has counted. */
  def of(
      service: AnkkaService,
      serviceName: String,
      instanceId: String,
      startedAt: String
  ): String =
    val observability = Observability(service.system)
    render(
      serviceName,
      instanceId,
      startedAt,
      service.registry,
      service.routes,
      observability.calls.snapshot(System.currentTimeMillis()),
      observability.names.nameOf
    )

  /** Who a call is from when nobody can say. A node of its own, and never a guess at one. */
  val UnknownNode: String = "unknown"

  /**
   * @param startedAt
   *   when this instance started.
   * @param calls
   *   what was counted in the window, by the recorder's integers.
   * @param nameOf
   *   the name behind one of those integers.
   */
  def render(
      serviceName: String,
      instanceId: String,
      startedAt: String,
      registry: ComponentRegistry,
      routes: Vector[ServedRoute],
      calls: CallCounts.Snapshot,
      nameOf: Int => Option[String]
  ): String =
    // An endpoint is drawn from the routes it serves. A remote one is also in the registry, by the
    // id it was declared with; listing it from there as well would draw it twice.
    val registered = registry.components.filter(_.kind != ComponentKind.Endpoint)
    val ids        = nodeIds(registered)
    val components = registered.map(d => component(ids(d), d))
    // A route is reported by whatever serves it, and a remote endpoint's are reported twice: by the
    // HTTP server and by the sidecar that declared them. It is one route.
    val endpoints =
      routes.distinct.groupBy(_.endpoint).toVector.map((id, served) => endpoint(id, served))

    val connections = registered.flatMap(d => declared(d, ids(d), registered, ids))
    // A topic or an outside component is a node because something declared a connection to it.
    val others = connections.flatMap(c => Vector(c.fromNode, c.toNode).flatten).distinctBy(_.id)

    val declaredNodes = components ++ endpoints ++ others
    val observed      = observedCalls(calls, nameOf, registered, ids, declaredNodes)

    val nodes =
      (declaredNodes ++ observed.nodes).distinctBy(_.id).sortBy(n => (n.layer, n.id)).map(_.json)
    val edges = connections
      .sortBy(c => (c.from, c.to, c.kind))
      .map(c =>
        s"""{"from":${Json.str(c.from)},"to":${Json.str(c.to)},"kind":${Json.str(c.kind)}}"""
      )

    s"""{"service":{"name":${Json.str(serviceName)},"runtime":${Json.str(BuildInfo.version)},""" +
      s""""instance":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)}},""" +
      // Two totals, as everywhere: the calls a handler ran for, and the calls nothing answered.
      s""""window":{"seconds":${calls.windowSeconds},""" +
      s""""since":${Json.str(java.time.Instant.ofEpochMilli(calls.sinceMillis).toString)},""" +
      s""""calls":${calls.handled},"unanswered":${calls.unanswered}},""" +
      s""""nodes":${nodes.mkString("[", ",", "]")},""" +
      s""""declared":${edges.mkString("[", ",", "]")},""" +
      s""""calls":${observed.edges.mkString("[", ",", "]")}}"""

  private final case class Observed(nodes: Vector[Node], edges: Vector[String])

  /**
   * The calls that were counted, as one entry for each two nodes with a pair for each two handlers.
   *
   * A name is turned back into the node it belongs to. One that belongs to none is not drawn onto
   * the nearest thing: a caller that cannot be placed is the unknown caller, and a callee that
   * cannot be placed is left out, which cannot happen for a call a host of this service counted.
   */
  private def observedCalls(
      calls: CallCounts.Snapshot,
      nameOf: Int => Option[String],
      registered: Vector[ComponentDescriptor],
      ids: Map[ComponentDescriptor, String],
      declaredNodes: Vector[Node]
  ): Observed =
    val layers = declaredNodes.map(n => n.id -> n.layer).toMap

    def nodeOf(component: String, handler: String): Option[String] =
      if component.startsWith("endpoint:") then Option.when(layers.contains(component))(component)
      else if component.startsWith("service:") then Some(component)
      else
        registered.filter(_.componentId.toString == component) match
          case Vector()    => None
          case Vector(one) => Some(ids(one))
          // An entity and the view over it may share an id. The handler says which was meant: one
          // of them declares it, or it is a way of asking a view.
          case several =>
            several
              .find(_.declaredHandlers.exists(_.name == handler))
              .orElse(
                several
                  .find(_.kind == ComponentKind.View)
                  .filter(_ => ViewQueries.Names.contains(handler))
              )
              .orElse(several.find(d => d.kind != ComponentKind.View))
              .map(ids)

    final case class Placed(
        from: String,
        to: String,
        caller: String,
        callee: String,
        pair: CallCounts.Pair
    )

    val placed = calls.pairs.flatMap { pair =>
      for
        callerComponent <- nameOf(pair.callerComponent)
        callerHandler   <- nameOf(pair.callerHandler)
        calleeComponent <- nameOf(pair.calleeComponent)
        calleeHandler   <- nameOf(pair.calleeHandler)
        to              <- nodeOf(calleeComponent, calleeHandler)
      yield
        val from =
          if callerComponent == CallCounts.UnknownOrigin then None
          else nodeOf(callerComponent, callerHandler)
        Placed(
          from.getOrElse(UnknownNode),
          to,
          if from.isDefined then callerHandler else UnknownNode,
          calleeHandler,
          pair
        )
    }

    val unknown = Option.when(placed.exists(_.from == UnknownNode))(
      node(UnknownNode, "UnknownCaller", layer = 0, platform = false, handlers = "[]")
    )
    // Another service is drawn one layer after whatever calls it.
    val services = placed
      .filter(_.to.startsWith("service:"))
      .groupBy(_.to)
      .toVector
      .map { (id, calling) =>
        val after = calling.map(p => layers.getOrElse(p.from, 0)).max + 1
        node(id, "ExternalService", after, platform = false, handlers = "[]")
      }

    val edges = placed
      .groupBy(p => (p.from, p.to))
      .toVector
      .sortBy((ends, _) => ends)
      .map { case ((from, to), pairs) =>
        val rendered =
          pairs.sortBy(p => (p.caller, p.callee)).map(p => pairJson(p.caller, p.callee, p.pair))
        s"""{"from":${Json.str(from)},"to":${Json
            .str(to)},"pairs":${rendered.mkString("[", ",", "]")}}"""
      }
    Observed(unknown.toVector ++ services, edges)

  /**
   * One handler calling another. `handled` is what the host that ran the handler saw and
   * `unanswered` what the caller saw; one call can be in both, so nothing here adds them up.
   */
  private def pairJson(caller: String, callee: String, pair: CallCounts.Pair): String =
    s"""{"caller":${Json.str(caller)},"callee":${Json.str(callee)},""" +
      s""""handled":{"ok":${pair.ok},"refused":${pair.refused},"failed":${pair.failed}},""" +
      s""""unanswered":{"timedOut":${pair.timedOut},"undelivered":${pair.undelivered}},""" +
      // Read from the histogram, so each is the upper edge of a bucket and says so.
      s""""durationMillis":{"p50":${millis(pair.percentileMillis(0.50))},""" +
      s""""p99":${millis(pair.percentileMillis(0.99))},"max":${millis(pair.maxMillis)},""" +
      s""""bucketed":true},""" +
      s""""histogram":${pair.histogram.mkString("[", ",", "]")},"streaming":${pair.streaming}}"""

  private def millis(value: Double): String =
    BigDecimal(value).bigDecimal.stripTrailingZeros.toPlainString

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
   * Exactly the sources the component declared — one for a plain view or a consumer, one per source
   * for a keyed view — and one destination, so a reader may say nothing else feeds a view. Read
   * from the descriptor and from nothing else: not from what has been delivered, and not from what
   * a component is called.
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

    val sources = DeclaredConnections.sourcesOf(descriptor).map {
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
    sources ++ destination
