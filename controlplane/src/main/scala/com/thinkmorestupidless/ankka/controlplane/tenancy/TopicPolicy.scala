package com.thinkmorestupidless.ankka.controlplane.tenancy

import com.thinkmorestupidless.ankka.controlplane.api.{
  CleanupPolicy,
  CompactionLag,
  RetentionSize,
  RetentionTime,
  TimeSpan,
  TopicBounds,
  TopicDefaults
}
import com.typesafe.config.Config

/**
 * The installation's topic policy (feature 043) — `ankka.controlplane.topics` in `reference.conf`,
 * read once at startup as `OrganizationPolicy` is: what a topic's first declaration is given for a
 * setting it leaves out, the bounds every declaration is checked against, and the retention below
 * which a view reading a topic is warned. Configuration, never state: a declaration records the
 * values it was given, so a change here changes no topic already declared.
 */
final case class TopicPolicy(
    defaults: TopicDefaults,
    bounds: TopicBounds,
    warningThreshold: RetentionTime
)

object TopicPolicy:

  /** The shipped values: a laptop's, for a broker of one node. */
  val default: TopicPolicy =
    TopicPolicy(TopicDefaults.Shipped, TopicBounds.Shipped, RetentionTime.Bounded(30L * 86400000L))

  val Section = "ankka.controlplane.topics"

  /** Each setting's key under the section, and the variable that overrides it. */
  object Keys:
    val DefaultRetention     = "default-retention"      -> "ANKKA_TOPIC_DEFAULT_RETENTION"
    val DefaultRetentionSize = "default-retention-size" -> "ANKKA_TOPIC_DEFAULT_RETENTION_SIZE"
    val DefaultCleanup       = "default-cleanup"        -> "ANKKA_TOPIC_DEFAULT_CLEANUP"
    val DefaultTombstoneWindow =
      "default-tombstone-window" -> "ANKKA_TOPIC_DEFAULT_TOMBSTONE_WINDOW"
    val DefaultMinCompactionLag =
      "default-min-compaction-lag" -> "ANKKA_TOPIC_DEFAULT_MIN_COMPACTION_LAG"
    val DefaultMaxCompactionLag =
      "default-max-compaction-lag" -> "ANKKA_TOPIC_DEFAULT_MAX_COMPACTION_LAG"
    val DefaultCopies        = "default-copies"         -> "ANKKA_TOPIC_DEFAULT_COPIES"
    val DefaultMinInSync     = "default-min-in-sync"    -> "ANKKA_TOPIC_DEFAULT_MIN_IN_SYNC"
    val LongestRetention     = "longest-retention"      -> "ANKKA_TOPIC_LONGEST_RETENTION"
    val LargestRetentionSize = "largest-retention-size" -> "ANKKA_TOPIC_LARGEST_RETENTION_SIZE"
    val MostCopies           = "most-copies"            -> "ANKKA_TOPIC_MOST_COPIES"
    val WarningThreshold     = "warning-threshold"      -> "ANKKA_TOPIC_WARNING_THRESHOLD"

  /**
   * Refuses, naming the key and the variable, a value that is not the words it should be, and a
   * default outside its own bound: a control plane that filled declarations with values it would
   * refuse to be given must not come up at all.
   */
  def from(config: Config): TopicPolicy =
    val section                      = config.getConfig(Section)
    def named(key: (String, String)) = s"$Section.${key._1} (${key._2})"
    def read[A](key: (String, String), parse: String => Either[String, A]): A =
      val text = section.getString(key._1)
      parse(text).fold(
        p => throw IllegalArgumentException(s"${named(key)} is '$text'; it must be $p"),
        identity
      )
    def count(key: (String, String)): Int =
      read(key, t => t.trim.toIntOption.filter(_ >= 1).toRight("a whole number of at least 1"))

    val defaults = TopicDefaults(
      retention = read(Keys.DefaultRetention, RetentionTime.parse),
      retentionSize = read(Keys.DefaultRetentionSize, RetentionSize.parse),
      cleanup = read(Keys.DefaultCleanup, CleanupPolicy.parse),
      tombstoneWindow = read(Keys.DefaultTombstoneWindow, TimeSpan.parse),
      minCompactionLag = read(Keys.DefaultMinCompactionLag, TimeSpan.parse),
      maxCompactionLag = read(Keys.DefaultMaxCompactionLag, CompactionLag.parse),
      copies = count(Keys.DefaultCopies),
      minInSync = count(Keys.DefaultMinInSync)
    )
    val bounds = TopicBounds(
      longestRetention = read(Keys.LongestRetention, RetentionTime.parse),
      largestRetentionSize = read(Keys.LargestRetentionSize, RetentionSize.parse),
      mostCopies = count(Keys.MostCopies)
    )
    val threshold = read(Keys.WarningThreshold, RetentionTime.parse)

    def outside(problem: Boolean, key: (String, String), bound: (String, String), what: String) =
      Option.when(problem)(s"${named(key)} is outside ${named(bound)}: $what")
    val problems = Vector(
      outside(
        bounds.longestRetention.shorterThan(defaults.retention),
        Keys.DefaultRetention,
        Keys.LongestRetention,
        s"${defaults.retention.text} is longer than ${bounds.longestRetention.text}"
      ),
      outside(
        bounds.largestRetentionSize.smallerThan(defaults.retentionSize),
        Keys.DefaultRetentionSize,
        Keys.LargestRetentionSize,
        s"${defaults.retentionSize.text} is larger than ${bounds.largestRetentionSize.text}"
      ),
      outside(
        defaults.copies > bounds.mostCopies,
        Keys.DefaultCopies,
        Keys.MostCopies,
        s"${defaults.copies} is more than ${bounds.mostCopies}"
      ),
      outside(
        defaults.minInSync > defaults.copies,
        Keys.DefaultMinInSync,
        Keys.DefaultCopies,
        s"${defaults.minInSync} is more than ${defaults.copies}"
      )
    ).flatten
    if problems.nonEmpty then throw IllegalArgumentException(problems.mkString("; "))
    TopicPolicy(defaults, bounds, threshold)
