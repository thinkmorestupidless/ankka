package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.ComponentId

/**
 * What a replicated entity's host is given (feature 051): this installation's name, the settings,
 * and the transport that reaches the others. A service that replicates nothing has none, and every
 * entity it hosts behaves as it did before replication.
 */
final class Replication private[ankka] (
    val settings: ReplicationSettings,
    val transport: ReplicationTransport,
    /** The replicated components this service hosts. */
    val components: Set[ComponentId]
):
  /** This installation. */
  val self: String = settings.installation.getOrElse(
    throw IllegalArgumentException("replication needs this installation's name")
  )

  /** Whether `componentId` is one of the replicated entities this service hosts. */
  def replicates(componentId: ComponentId): Boolean = components.contains(componentId)

/**
 * How one copy of a replicated entity stands, derived from what its journal says on every command,
 * never kept apart from it.
 */
enum Mode:
  /** No record says where the entity lives: never held here, or held from before replication. */
  case Unknown

  /** The home: this copy handles the entity's writes. */
  case Home

  /** In the replication set and not the home: mirrors the home and answers reads. */
  case Follower

  /** Not in the replication set: forwards writes and refuses reads, naming the home. */
  case Outside

object Mode:
  def of(
      self: String,
      home: Option[String],
      replicationSet: Set[String]
  ): Mode =
    home match
      case None                      => Unknown
      case Some(h) if h == self      => Home
      case _ if replicationSet(self) => Follower
      case _                         => Outside
