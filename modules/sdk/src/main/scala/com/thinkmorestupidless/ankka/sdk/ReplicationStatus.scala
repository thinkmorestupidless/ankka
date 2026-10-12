package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{Codecs, MethodName, Serializer}

/**
 * How one copy of a replicated entity stands, from its own journal (feature 051): its home, its
 * replication set, a handover waiting to complete, the other home when the entity is in conflict,
 * and how much of the history this copy holds. Any copy answers, the home and every follower, and
 * an installation outside the set answers with the copy it last held.
 */
final case class ReplicationStatus(
    home: Option[String],
    replicationSet: Set[String],
    pendingHandover: Option[String],
    conflict: Option[String],
    position: Long
):
  /** Whether the entity was created at two installations and an operator must resolve it. */
  def inConflict: Boolean = conflict.nonEmpty

object ReplicationStatus:

  /**
   * The reserved query any copy of a replicated entity answers with its status. Under the reserved
   * `ankka:` prefix, which no entity's handler may use.
   */
  private[ankka] val Method: MethodName = MethodName("ankka:replication")

  /**
   * The reserved query that says whether a handover to the installation its payload names has
   * completed: `pending` while it waits, `completed` once that installation is the home, and a
   * refusal `Unavailable` when it was abandoned or never asked for.
   */
  private[ankka] val HandoverMethod: MethodName = MethodName("ankka:handover-status")

  private[ankka] val Pending: String   = "pending"
  private[ankka] val Completed: String = "completed"

  private[ankka] val serializer: Serializer[ReplicationStatus] =
    Codecs.serializer[ReplicationStatus]("replication-status")
