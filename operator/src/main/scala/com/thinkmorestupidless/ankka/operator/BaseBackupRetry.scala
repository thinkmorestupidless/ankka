package com.thinkmorestupidless.ankka.operator

import java.time.{Duration, Instant}

/**
 * Whether a project database's first base backup is taken again (feature 041). A schedule's next
 * backup is a day away, and its first can fail for reasons that pass on their own: CNPG still
 * adding the plugin ("the cluster has no plugin configured", "requested plugin is not available"),
 * or the instance restarting under it to load the plugin. Until one has completed, a cluster whose
 * every base backup failed is given another, a while after the last, and only a few times: a target
 * that refuses writes is reported as failing, and must not become a backup a minute.
 */
object BaseBackupRetry:

  /** One base backup: CNPG's phase, and when it ended, or was made when it never started. */
  final case class Attempt(phase: Option[String], at: Option[Instant])

  val After: Duration = Duration.ofMinutes(2)
  val AtMost: Int     = 5

  def due(attempts: Vector[Attempt], now: Instant): Boolean =
    attempts.nonEmpty &&
      attempts.size < AtMost &&
      attempts.forall(_.phase.contains("failed")) &&
      attempts.flatMap(_.at).maxOption.exists(last => !last.plus(After).isAfter(now))

  /** The name of the backup taken again; the epoch second keeps two attempts apart. */
  def name(cluster: String, now: Instant): String = s"$cluster-base-again-${now.getEpochSecond}"
