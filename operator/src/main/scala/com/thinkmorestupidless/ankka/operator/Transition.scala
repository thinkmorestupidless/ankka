package com.thinkmorestupidless.ankka.operator

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The one deployment that is not a rolling update: a service's move from plain TCP to mutual TLS
 * (feature 014).
 *
 * A TLS node and a plain node cannot join each other, so the rolling update that is otherwise
 * zero-downtime would stall — the surge pod never ready, because it finds no peer it can talk to —
 * and letting it form a cluster of its own instead would be two clusters on one journal, the split
 * `Recreate` once existed to prevent. So the operator stops the old instances, waits until none is
 * left, and only then applies the new Deployment: one bounded interruption, once, performed by the
 * platform rather than asked of the deployer, and reported so the service's history says why.
 *
 * `Recreate` as a strategy was considered and rejected: changing the strategy of a live Deployment
 * is the wedge the rolling-update migration hit, and it would stay on the object afterwards.
 */
object Transition:

  val Detail: String = "moving to mutual TLS: instances restart together, once"

  /**
   * Longer than a pod's grace period plus its preStop sleep; past it, the next reconcile retries.
   */
  val Wait: FiniteDuration = 90.seconds

  /**
   * Whether the Deployment the operator last rendered for this service predates mutual TLS. `None`
   * — no Deployment, or one ankka does not own — never needs it: a new service starts on TLS, and a
   * foreign object is refused before this is asked.
   */
  def needed(templateLabels: Option[Map[String, String]]): Boolean =
    templateLabels.exists(labels => !labels.get(Labels.TransportKey).contains(Labels.TransportTls))
