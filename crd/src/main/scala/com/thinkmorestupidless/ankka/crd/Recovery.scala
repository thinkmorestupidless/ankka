package com.thinkmorestupidless.ankka.crd

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

/**
 * The names a restore and a rehearsal derive (feature 041, research R4), here for the reason
 * `Buckets` is: the control plane shows them and refuses ids that would collide, and the operator
 * renders them, so the two cannot disagree.
 */
object Recovery:

  /** A project's database cluster: the first line of history. */
  val ProjectDatabase: String = "ankka-db"

  /** What a rehearsal namespace's name ends in. */
  val RehearsalSuffix: String = "-rehearsal"

  private val Minute = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)

  /** A restore's cluster, beside the project database, named for the minute it was asked for. */
  def restoreName(requestedAt: Instant): String = s"$ProjectDatabase-r${Minute.format(requestedAt)}"

  /** A rehearsal's cluster, in the rehearsal namespace. */
  def rehearsalName(requestedAt: Instant): String =
    s"$ProjectDatabase-x${Minute.format(requestedAt)}"

  /** Where a project's rehearsals run: the one namespace the operator may delete a cluster in. */
  def rehearsalNamespace(prefix: String, projectId: String): String =
    s"$prefix-$projectId$RehearsalSuffix"

  /**
   * A project called `shop-rehearsal` would have the namespace of `shop`'s rehearsals, where the
   * operator may delete, so no project id may end so.
   */
  def projectProblems(projectId: String): Vector[String] =
    if projectId.endsWith(RehearsalSuffix) then
      Vector(
        s"project id '$projectId' ends in '$RehearsalSuffix', which names another project's " +
          "rehearsal namespace"
      )
    else Vector.empty
