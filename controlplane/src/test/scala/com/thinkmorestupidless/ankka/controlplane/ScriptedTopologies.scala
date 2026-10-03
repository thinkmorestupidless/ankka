package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.deploy.TopologyReader

import java.util.concurrent.ConcurrentHashMap

/**
 * A topology reader a suite scripts: what each instance of a service answers, per service. The
 * default for a service nothing was scripted for is no running instance.
 */
final class ScriptedTopologies extends TopologyReader:
  private val scripted =
    new ConcurrentHashMap[(String, String), Vector[
      (InstanceTopology, Option[InstanceTopologyDocument])
    ]]()

  /** Answers for a service through another reader, for a case about how reads are made. */
  @volatile var through: Option[TopologyReader] = None

  def script(
      projectId: String,
      service: String,
      instances: Vector[(InstanceTopology, Option[InstanceTopologyDocument])]
  ): Unit = scripted.put((projectId, service), instances): Unit

  def clear(projectId: String, service: String): Unit = scripted.remove((projectId, service)): Unit

  def read(
      projectId: String,
      service: String
  ): Vector[(InstanceTopology, Option[InstanceTopologyDocument])] =
    through match
      case Some(reader) => reader.read(projectId, service)
      case None         => Option(scripted.get((projectId, service))).getOrElse(Vector.empty)

/** Documents as an instance of the shopping cart would write them, for scripting. */
object Topologies:

  val At = "2026-10-01T10:00:00Z"

  def ok(pod: String): InstanceTopology =
    InstanceTopology(pod, InstanceStatus.Ok, None, Some("0.10.0"), Some(At))

  /** Thirty-two buckets with `calls` in the one for about a millisecond. */
  def histogram(calls: Long): Vector[Long] = Vector.fill(32)(0L).updated(10, calls)

  /**
   * A cart instance's document: an endpoint, the entity, a view over it, and `handled` ok calls
   * from the endpoint's route to the entity's command. `extra` nodes are added as they are.
   */
  def cart(
      instance: String,
      handled: Long,
      extra: Vector[TopologyNode] = Vector.empty
  ): InstanceTopologyDocument =
    InstanceTopologyDocument(
      TopologyService("cart", "0.10.0", instance, At),
      TopologyWindow(600L, At, handled, 0L),
      Vector(
        TopologyNode(
          "endpoint:/carts",
          "Endpoint",
          0,
          platform = false,
          Vector(TopologyHandler("POST /carts/{cartId}/items", "route", Some(false)))
        ),
        TopologyNode(
          "cart",
          "EventSourcedEntity",
          1,
          platform = false,
          Vector(TopologyHandler("add-item", "command"), TopologyHandler("get-cart", "query"))
        )
      ) ++ extra,
      extra.collect { case n if n.kind == "View" => DeclaredEdge("cart", n.id, "events") },
      if handled == 0 then Vector.empty
      else
        Vector(
          CallEdge(
            "endpoint:/carts",
            "cart",
            Vector(
              CallPair(
                "POST /carts/{cartId}/items",
                "add-item",
                HandledCounts(handled, 0L, 0L),
                UnansweredCounts(0L, 0L),
                DurationMillis(2.0, 2.0, 2.0),
                histogram = histogram(handled)
              )
            )
          )
        )
    )

  def view(id: String): TopologyNode = TopologyNode(id, "View", 2, platform = false, Vector.empty)
