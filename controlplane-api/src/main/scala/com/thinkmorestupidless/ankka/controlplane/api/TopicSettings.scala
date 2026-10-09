package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

// ── Topic settings (feature 043) ─────────────────────────────────────────────
//
// How long a topic keeps, how it is cleaned and how many copies it has. Every value crosses the
// wire as the words a member types ("90d", "50GiB", "compact,delete", "everything", "none"), never
// as Kafka's keys, and is held typed. `TopicSettings.toKafka` is the one place the Kafka form is
// written here; the operator, which cannot depend on this module, renders the same keys from the
// resource's numbers, and both sides are held to `protocol/fixtures/topics/settings.json`.

/** Durations and sizes as a member writes them. */
object TopicUnits:

  private val DurationPattern = "([0-9]{1,15})(ms|s|m|h|d)".r
  private val SizePattern     = "([0-9]{1,15})(B|KiB|MiB|GiB|TiB)".r

  private val DurationUnits: Vector[(String, Long)] =
    Vector("d" -> 86400000L, "h" -> 3600000L, "m" -> 60000L, "s" -> 1000L, "ms" -> 1L)

  private val SizeUnits: Vector[(String, Long)] =
    Vector(
      "TiB" -> (1L << 40),
      "GiB" -> (1L << 30),
      "MiB" -> (1L << 20),
      "KiB" -> (1L << 10),
      "B"   -> 1L
    )

  val DurationWords: String = "a duration: a whole number and ms, s, m, h or d"
  val SizeWords: String     = "a size: a whole number and B, KiB, MiB, GiB or TiB"

  /** Milliseconds, from `"90d"`, `"500ms"`. */
  def duration(text: String): Either[String, Long] = text.trim match
    case DurationPattern(n, unit) =>
      DurationUnits
        .collectFirst { case (`unit`, factor) => n.toLong * factor }
        .toRight(DurationWords)
    case _ => Left(DurationWords)

  /** The shortest exact writing of a duration: `7776000000` is `"90d"`. */
  def durationText(millis: Long): String =
    DurationUnits
      .collectFirst {
        case (unit, factor) if millis != 0 && millis % factor == 0 => s"${millis / factor}$unit"
      }
      .getOrElse("0s")

  /** Bytes, from `"50GiB"`. */
  def size(text: String): Either[String, Long] = text.trim match
    case SizePattern(n, unit) =>
      SizeUnits.collectFirst { case (`unit`, factor) => n.toLong * factor }.toRight(SizeWords)
    case _ => Left(SizeWords)

  def sizeText(bytes: Long): String =
    SizeUnits
      .collectFirst {
        case (unit, factor) if bytes != 0 && bytes % factor == 0 => s"${bytes / factor}$unit"
      }
      .getOrElse("0B")

  /** A string codec for a value with a parser and a writing, placed in each value's companion. */
  private[api] def stringCodec[A](
      what: String,
      parse: String => Either[String, A],
      show: A => String
  ): JsonValueCodec[A] =
    new JsonValueCodec[A]:
      def decodeValue(in: JsonReader, default: A): A =
        val text = in.readString(null)
        parse(text).fold(problem => in.decodeError(s"$what \"$text\" is not $problem"), identity)
      def encodeValue(x: A, out: JsonWriter): Unit = out.writeVal(show(x))
      def nullValue: A                             = null.asInstanceOf[A]

/** How long a topic keeps a message: a duration, or everything. */
enum RetentionTime:
  case Bounded(millis: Long)
  case Everything

  def text: String = this match
    case Bounded(ms) => TopicUnits.durationText(ms)
    case Everything  => RetentionTime.EverythingWord

  /** `retention.ms`: `-1` keeps everything. */
  def kafka: Long = this match
    case Bounded(ms) => ms
    case Everything  => -1L

  /** Whether this keeps less than `other` does. */
  def shorterThan(other: RetentionTime): Boolean = (this, other) match
    case (Bounded(a), Bounded(b)) => a < b
    case (Bounded(_), Everything) => true
    case _                        => false

object RetentionTime:
  val EverythingWord = "everything"

  def parse(text: String): Either[String, RetentionTime] =
    if text.trim == EverythingWord then Right(Everything)
    else
      TopicUnits
        .duration(text)
        .flatMap { ms =>
          if ms > 0 then Right(Bounded(ms)) else Left("a duration above zero")
        }
        .left
        .map(p => s"$p, or \"$EverythingWord\"")

  given codec: JsonValueCodec[RetentionTime] =
    TopicUnits.stringCodec("retention time", parse, _.text)

/** How much a partition of a topic keeps: a size, or no limit. */
enum RetentionSize:
  case Bounded(bytes: Long)
  case NoLimit

  def text: String = this match
    case Bounded(b) => TopicUnits.sizeText(b)
    case NoLimit    => RetentionSize.NoneWord

  /** `retention.bytes`: `-1` sets no limit. */
  def kafka: Long = this match
    case Bounded(b) => b
    case NoLimit    => -1L

  def smallerThan(other: RetentionSize): Boolean = (this, other) match
    case (Bounded(a), Bounded(b)) => a < b
    case (Bounded(_), NoLimit)    => true
    case _                        => false

object RetentionSize:
  val NoneWord = "none"

  def parse(text: String): Either[String, RetentionSize] =
    if text.trim == NoneWord then Right(NoLimit)
    else
      TopicUnits
        .size(text)
        .flatMap { b =>
          if b > 0 then Right(Bounded(b)) else Left("a size above zero")
        }
        .left
        .map(p => s"$p, or \"$NoneWord\"")

  given codec: JsonValueCodec[RetentionSize] =
    TopicUnits.stringCodec("retention size", parse, _.text)

/** How the broker removes a topic's messages. */
enum CleanupPolicy:
  /** By the topic's retention time and size. */
  case Delete

  /** Keeping the last message under each key, and removing nothing by age. */
  case Compact

  /** Both: the last message under each key, and only within the retention time and size. */
  case CompactDelete

  def text: String = this match
    case Delete        => "delete"
    case Compact       => "compact"
    case CompactDelete => "compact,delete"

  def compacts: Boolean = this != Delete

  /** Whether the broker removes messages by age and size under this policy. */
  def deletes: Boolean = this != Compact

object CleanupPolicy:
  val Words = "\"delete\", \"compact\" or \"compact,delete\""

  def parse(text: String): Either[String, CleanupPolicy] =
    text.split(',').map(_.trim).filter(_.nonEmpty).toSet match
      case s if s == Set("delete")            => Right(Delete)
      case s if s == Set("compact")           => Right(Compact)
      case s if s == Set("compact", "delete") => Right(CompactDelete)
      case _                                  => Left(Words)

  given codec: JsonValueCodec[CleanupPolicy] =
    TopicUnits.stringCodec("cleanup policy", parse, _.text)

/** A span of time a compacted topic is given: its tombstone window, its minimum compaction lag. */
final case class TimeSpan(millis: Long):
  def text: String = TopicUnits.durationText(millis)

object TimeSpan:
  def parse(text: String): Either[String, TimeSpan] =
    if text.trim == "0" then Right(TimeSpan(0)) else TopicUnits.duration(text).map(TimeSpan(_))

  given codec: JsonValueCodec[TimeSpan] = TopicUnits.stringCodec("duration", parse, _.text)

/** The longest a compacted topic may wait to compact a message: a duration, or no limit. */
enum CompactionLag:
  case Bounded(millis: Long)
  case NoLimit

  def text: String = this match
    case Bounded(ms) => TopicUnits.durationText(ms)
    case NoLimit     => RetentionSize.NoneWord

  /** `max.compaction.lag.ms`: Kafka's own default, `Long.MaxValue`, is no limit. */
  def kafka: Long = this match
    case Bounded(ms) => ms
    case NoLimit     => Long.MaxValue

object CompactionLag:
  def parse(text: String): Either[String, CompactionLag] =
    if text.trim == RetentionSize.NoneWord then Right(NoLimit)
    else
      TopicUnits
        .duration(text)
        .flatMap { ms =>
          if ms > 0 then Right(Bounded(ms)) else Left("a duration above zero")
        }
        .left
        .map(p => s"$p, or \"none\"")

  given codec: JsonValueCodec[CompactionLag] =
    TopicUnits.stringCodec("maximum compaction lag", parse, _.text)

/** One setting of a topic, by the name its field has on the wire. */
enum Setting:
  case Retention, RetentionSize, Cleanup, TombstoneWindow, MinCompactionLag, MaxCompactionLag,
    Copies,
    MinInSync

  def wire: String = this match
    case Setting.Retention        => "retention"
    case Setting.RetentionSize    => "retentionSize"
    case Setting.Cleanup          => "cleanup"
    case Setting.TombstoneWindow  => "tombstoneWindow"
    case Setting.MinCompactionLag => "minCompactionLag"
    case Setting.MaxCompactionLag => "maxCompactionLag"
    case Setting.Copies           => "copies"
    case Setting.MinInSync        => "minInSync"

object Setting:
  def byWire(text: String): Option[Setting] = Setting.values.find(_.wire == text)

  given codec: JsonValueCodec[Setting] =
    TopicUnits.stringCodec("setting", t => byWire(t).toRight("a setting of a topic"), _.wire)

/** One setting a declaration changed, from what to what, in the words a member reads. */
final case class SettingChange(setting: Setting, from: String, to: String)

/**
 * Every setting of a declared topic. `copies` and `minInSync` are `None` only for a topic declared
 * before topics stated them, whose copies are what the broker holds; they are fixed once stated.
 */
final case class TopicSettings(
    retention: RetentionTime,
    retentionSize: RetentionSize,
    cleanup: CleanupPolicy,
    tombstoneWindow: TimeSpan,
    minCompactionLag: TimeSpan,
    maxCompactionLag: CompactionLag,
    copies: Option[Int] = None,
    minInSync: Option[Int] = None
):

  def compacted: Boolean = cleanup.compacts

  /** A setting's value in the words a member reads; copies unstated read as the broker's. */
  def valueOf(setting: Setting): String = setting match
    case Setting.Retention        => retention.text
    case Setting.RetentionSize    => retentionSize.text
    case Setting.Cleanup          => cleanup.text
    case Setting.TombstoneWindow  => tombstoneWindow.text
    case Setting.MinCompactionLag => minCompactionLag.text
    case Setting.MaxCompactionLag => maxCompactionLag.text
    case Setting.Copies           => copies.fold(TopicSettings.BrokersCopies)(_.toString)
    case Setting.MinInSync        => minInSync.fold(TopicSettings.BrokersCopies)(_.toString)

  /**
   * Kafka's topic configuration for these settings: every key stated, nothing left to a default.
   */
  def toKafka: Map[String, String] =
    Map(
      "retention.ms"          -> retention.kafka.toString,
      "retention.bytes"       -> retentionSize.kafka.toString,
      "cleanup.policy"        -> cleanup.text,
      "delete.retention.ms"   -> tombstoneWindow.millis.toString,
      "min.compaction.lag.ms" -> minCompactionLag.millis.toString,
      "max.compaction.lag.ms" -> maxCompactionLag.kafka.toString
    ) ++ minInSync.map(n => "min.insync.replicas" -> n.toString)

  def view(defaulted: Set[Setting]): TopicSettingsView =
    TopicSettingsView(
      retention = retention.text,
      retentionSize = retentionSize.text,
      cleanup = cleanup.text,
      tombstoneWindow = tombstoneWindow.text,
      minCompactionLag = minCompactionLag.text,
      maxCompactionLag = maxCompactionLag.text,
      copies = copies,
      minInSync = minInSync,
      defaulted = Setting.values.toVector.filter(defaulted).map(_.wire)
    )

object TopicSettings:
  /** What an unstated copies setting reads as. */
  val BrokersCopies = "the broker's"

/** A topic's settings as the listing shows them: every value in words, and which were defaults. */
final case class TopicSettingsView(
    retention: String,
    retentionSize: String,
    cleanup: String,
    tombstoneWindow: String,
    minCompactionLag: String,
    maxCompactionLag: String,
    copies: Option[Int] = None,
    minInSync: Option[Int] = None,
    defaulted: Vector[String] = Vector.empty
)

/** What a topic's first declaration is given for a setting it leaves out. */
final case class TopicDefaults(
    retention: RetentionTime,
    retentionSize: RetentionSize,
    cleanup: CleanupPolicy,
    tombstoneWindow: TimeSpan,
    minCompactionLag: TimeSpan,
    maxCompactionLag: CompactionLag,
    copies: Int,
    minInSync: Int
)

object TopicDefaults:
  /** The shipped values: a laptop's, with one broker node. */
  val Shipped: TopicDefaults = TopicDefaults(
    retention = RetentionTime.Bounded(7L * 86400000L),
    retentionSize = RetentionSize.NoLimit,
    cleanup = CleanupPolicy.Delete,
    tombstoneWindow = TimeSpan(86400000L),
    minCompactionLag = TimeSpan(0L),
    maxCompactionLag = CompactionLag.NoLimit,
    copies = 1,
    minInSync = 1
  )

/** The limits the installation sets on what a declaration may ask. */
final case class TopicBounds(
    longestRetention: RetentionTime,
    largestRetentionSize: RetentionSize,
    mostCopies: Int
)

object TopicBounds:
  val Shipped: TopicBounds = TopicBounds(RetentionTime.Everything, RetentionSize.NoLimit, 3)

/** A declared topic's settings and which of them the installation supplied. */
final case class FilledSettings(settings: TopicSettings, defaulted: Set[Setting])

/** The settings a declaration gave, parsed; absent is "not given". */
final case class GivenSettings(
    retention: Option[RetentionTime] = None,
    retentionSize: Option[RetentionSize] = None,
    cleanup: Option[CleanupPolicy] = None,
    tombstoneWindow: Option[TimeSpan] = None,
    minCompactionLag: Option[TimeSpan] = None,
    maxCompactionLag: Option[CompactionLag] = None,
    copies: Option[Int] = None,
    minInSync: Option[Int] = None
):
  def named: Set[Setting] =
    Set(
      retention.map(_ => Setting.Retention),
      retentionSize.map(_ => Setting.RetentionSize),
      cleanup.map(_ => Setting.Cleanup),
      tombstoneWindow.map(_ => Setting.TombstoneWindow),
      minCompactionLag.map(_ => Setting.MinCompactionLag),
      maxCompactionLag.map(_ => Setting.MaxCompactionLag),
      copies.map(_ => Setting.Copies),
      minInSync.map(_ => Setting.MinInSync)
    ).flatten

/** The rules of a topic's settings, applied identically by the CLI and the control plane. */
object TopicSettingsRules:

  val FixedCopies: String = "copies and minimum in-sync copies are fixed when a topic is declared"

  val PartitionsRequired: String = "partitions is required for a new topic"

  val RemovesNothing: String = "this declaration removes nothing"

  /** The refusal of a removal without its acknowledgement. */
  def unacknowledged(removal: String): String =
    s"this declaration removes $removal; a declaration that removes messages says so with \"removes\""

  /** The refusal of a removal by someone who is not an owner. */
  def ownerRequired(removal: String): String =
    s"owner role required: this declaration removes $removal"

  /** Parse what a request gave, all problems at once. */
  def parse(name: String, request: TopicDeclarationRequest): Either[Vector[String], GivenSettings] =
    def field[A](label: String, value: Option[String], parse: String => Either[String, A]) =
      value match
        case None => Right(None)
        case Some(text) =>
          parse(text).map(Some(_)).left.map(p => s"topic '$name': $label \"$text\" is not $p")
    val retention     = field("retention", request.retention, RetentionTime.parse)
    val retentionSize = field("retentionSize", request.retentionSize, RetentionSize.parse)
    val cleanup       = field("cleanup", request.cleanup, CleanupPolicy.parse)
    val tombstone     = field("tombstoneWindow", request.tombstoneWindow, TimeSpan.parse)
    val minLag        = field("minCompactionLag", request.minCompactionLag, TimeSpan.parse)
    val maxLag        = field("maxCompactionLag", request.maxCompactionLag, CompactionLag.parse)
    val parsed        = Vector(retention, retentionSize, cleanup, tombstone, minLag, maxLag)
    val problems      = parsed.collect { case Left(p) => p }
    if problems.nonEmpty then Left(problems)
    else
      val compacted    = Option.when(request.compacted)(CleanupPolicy.Compact)
      val cleanupGiven = cleanup.toOption.flatten
      val agreement = (cleanupGiven, request.compacted) match
        case (Some(CleanupPolicy.Delete), true) =>
          Vector(s"topic '$name': compacted and cleanup disagree")
        case _ => Vector.empty
      if agreement.nonEmpty then Left(agreement)
      else
        Right(
          GivenSettings(
            retention = retention.toOption.flatten,
            retentionSize = retentionSize.toOption.flatten,
            cleanup = cleanupGiven.orElse(compacted),
            tombstoneWindow = tombstone.toOption.flatten,
            minCompactionLag = minLag.toOption.flatten,
            maxCompactionLag = maxLag.toOption.flatten,
            copies = request.copies,
            minInSync = request.minInSync
          )
        )

  /** What a given value breaks of the installation's bounds, or of each other. */
  def bounded(name: String, asked: GivenSettings, bounds: TopicBounds): Vector[String] =
    val retention = asked.retention.collect {
      case r if bounds.longestRetention.shorterThan(r) =>
        s"topic '$name': retention time ${r.text} is longer than the installation's longest, " +
          bounds.longestRetention.text
    }
    val size = asked.retentionSize.collect {
      case s if bounds.largestRetentionSize.smallerThan(s) =>
        s"topic '$name': retention size ${s.text} is larger than the installation's largest, " +
          bounds.largestRetentionSize.text
    }
    val copies = asked.copies.collect {
      case c if c > bounds.mostCopies =>
        s"topic '$name': copies $c is more than the installation's most, ${bounds.mostCopies}"
      case c if c < 1 => s"topic '$name': copies must be at least 1"
    }
    retention.toVector ++ size.toVector ++ copies.toVector

  /** The rules settings hold whatever the installation says. */
  def coherent(name: String, settings: TopicSettings): Vector[String] =
    val inSync = (settings.copies, settings.minInSync) match
      case (Some(c), Some(m)) if m < 1 || m > c =>
        Vector(s"topic '$name': minimum in-sync copies must be between 1 and the copies, $c")
      case _ => Vector.empty
    val lags = settings.maxCompactionLag match
      case CompactionLag.Bounded(max) if max < settings.minCompactionLag.millis =>
        Vector(
          s"topic '$name': the maximum compaction lag ${settings.maxCompactionLag.text} is shorter " +
            s"than the minimum, ${settings.minCompactionLag.text}"
        )
      case _ => Vector.empty
    inSync ++ lags

  /** A first declaration: every setting it leaves out from the installation's defaults. */
  def fill(asked: GivenSettings, defaults: TopicDefaults): FilledSettings =
    FilledSettings(
      TopicSettings(
        retention = asked.retention.getOrElse(defaults.retention),
        retentionSize = asked.retentionSize.getOrElse(defaults.retentionSize),
        cleanup = asked.cleanup.getOrElse(defaults.cleanup),
        tombstoneWindow = asked.tombstoneWindow.getOrElse(defaults.tombstoneWindow),
        minCompactionLag = asked.minCompactionLag.getOrElse(defaults.minCompactionLag),
        maxCompactionLag = asked.maxCompactionLag.getOrElse(defaults.maxCompactionLag),
        copies = Some(asked.copies.getOrElse(defaults.copies)),
        minInSync = Some(
          asked.minInSync.getOrElse(
            math.min(defaults.minInSync, asked.copies.getOrElse(defaults.copies))
          )
        )
      ),
      Setting.values.toSet -- asked.named
    )

  /**
   * A redeclaration: every setting it leaves out keeps its value and its mark. Copies and the
   * minimum in-sync copies are fixed: given at all for a topic whose copies are the broker's, or
   * given differently, is the fixed-copies refusal.
   */
  def merge(asked: GivenSettings, current: FilledSettings): Either[String, FilledSettings] =
    val now                                           = current.settings
    def fixed(wanted: Option[Int], held: Option[Int]) = wanted.exists(a => !held.contains(a))
    if fixed(asked.copies, now.copies) || fixed(asked.minInSync, now.minInSync) then
      Left(FixedCopies)
    else
      Right(
        FilledSettings(
          now.copy(
            retention = asked.retention.getOrElse(now.retention),
            retentionSize = asked.retentionSize.getOrElse(now.retentionSize),
            cleanup = asked.cleanup.getOrElse(now.cleanup),
            tombstoneWindow = asked.tombstoneWindow.getOrElse(now.tombstoneWindow),
            minCompactionLag = asked.minCompactionLag.getOrElse(now.minCompactionLag),
            maxCompactionLag = asked.maxCompactionLag.getOrElse(now.maxCompactionLag)
          ),
          current.defaulted -- asked.named
        )
      )

  /**
   * The settings of a topic declared before topics stated them, filled from the installation's
   * defaults: everything but its copies, which stay the broker's.
   */
  def legacy(compacted: Boolean, defaults: TopicDefaults): FilledSettings =
    val filled = fill(GivenSettings(), defaults)
    FilledSettings(
      filled.settings.copy(
        cleanup = if compacted then CleanupPolicy.Compact else defaults.cleanup,
        copies = None,
        minInSync = None
      ),
      Setting.values.toSet -- Set(Setting.Copies, Setting.MinInSync)
    )

  /** Every setting that differs, from what to what. */
  def changes(before: TopicSettings, after: TopicSettings): Vector[SettingChange] =
    Setting.values.toVector.flatMap { s =>
      val (from, to) = (before.valueOf(s), after.valueOf(s))
      Option.when(from != to)(SettingChange(s, from, to))
    }

  /**
   * What a change removes from the broker, in the words an owner confirms, or nothing: a shorter
   * retention time, a smaller retention size, or the end of compaction keeping messages forever.
   */
  def removal(before: TopicSettings, after: TopicSettings): Option[String] =
    val deletesAfter = after.cleanup.deletes
    val shorter = Option.when(deletesAfter && after.retention.shorterThan(before.retention))(
      s"messages older than ${after.retention.text}"
    )
    val smaller =
      Option.when(deletesAfter && after.retentionSize.smallerThan(before.retentionSize))(
        s"messages beyond ${after.retentionSize.text} on a partition"
      )
    // A compacted topic that deleted nothing by age now does: what compaction kept and the
    // retention no longer covers goes. Said once, unless the shorter retention already says it.
    val uncompacted = after.retention match
      case RetentionTime.Bounded(_) if !before.cleanup.deletes && deletesAfter && shorter.isEmpty =>
        Some(s"messages older than ${after.retention.text}, which compaction kept")
      case _ => None
    val parts = shorter.toVector ++ uncompacted.toVector ++ smaller.toVector
    Option.when(parts.nonEmpty)(parts.mkString("; "))
