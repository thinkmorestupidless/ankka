package com.thinkmorestupidless.ankka.controlplane.tenancy

import com.typesafe.config.Config

/**
 * What the control plane knows of the installation's broker beyond its topic policy: how many nodes
 * it has, from `ankka.controlplane.broker.nodes` (`ANKKA_BROKER_NODES`), which the broker component
 * that sizes the broker sets. Read once at startup, as `TopicPolicy` is. `None` is "not stated":
 * the control plane was not told, and does not guess from anything it can see.
 */
final case class BrokerFacts(nodes: Option[Int])

object BrokerFacts:

  val NodesKey: String = "ankka.controlplane.broker.nodes"

  /** Nothing stated: what a control plane not told the number reports. */
  val unstated: BrokerFacts = BrokerFacts(None)

  /** Refuses, naming the key and the variable, a count that is not a whole number of at least 1. */
  def from(config: Config): BrokerFacts =
    if !config.hasPath(NodesKey) then unstated
    else
      val text = config.getString(NodesKey).trim
      if text.isEmpty then unstated
      else
        text.toIntOption.filter(_ >= 1) match
          case Some(n) => BrokerFacts(Some(n))
          case None    =>
            throw IllegalArgumentException(
              s"$NodesKey (ANKKA_BROKER_NODES) is '$text'; it must be a whole number of at least 1"
            )
