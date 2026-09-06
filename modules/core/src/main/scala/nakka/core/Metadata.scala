package nakka.core

/**
 * Ordered, multi-valued, case-insensitive string metadata attached to commands, replies,
 * events and broker messages.
 *
 * Keys are compared case-insensitively (HTTP headers and CloudEvents attributes both
 * behave this way) but the original casing is preserved on the way out, so a header set
 * as `X-Request-Id` is not silently rewritten to `x-request-id`.
 */
final class Metadata private (private val entries: Vector[(String, String)]):

  def isEmpty: Boolean  = entries.isEmpty
  def nonEmpty: Boolean = entries.nonEmpty
  def size: Int         = entries.size

  /** All key/value pairs, in insertion order. */
  def toSeq: Seq[(String, String)] = entries

  /** First value for `key`, if present. */
  def get(key: String): Option[String] =
    entries.collectFirst { case (k, v) if eq(k, key) => v }

  /** Every value for `key`, in insertion order. */
  def getAll(key: String): Seq[String] =
    entries.collect { case (k, v) if eq(k, key) => v }

  def contains(key: String): Boolean = entries.exists((k, _) => eq(k, key))

  /** Appends a value, keeping any existing values for the same key. */
  def add(key: String, value: String): Metadata =
    Metadata(entries :+ (key -> value))

  /** Replaces every existing value for `key` with `value`. */
  def set(key: String, value: String): Metadata =
    Metadata(entries.filterNot((k, _) => eq(k, key)) :+ (key -> value))

  def remove(key: String): Metadata =
    Metadata(entries.filterNot((k, _) => eq(k, key)))

  /** Right-biased merge: entries in `other` are appended. */
  def ++(other: Metadata): Metadata = Metadata(entries ++ other.entries)

  // ── CloudEvents attributes ────────────────────────────────────────────────
  // nakka speaks CloudEvents on broker topics and service-to-service streams, and
  // `ce-subject` doubles as the entity id that a View row or Consumer keys off.

  def subject: Option[String]     = get(Metadata.CeSubject)
  def eventType: Option[String]   = get(Metadata.CeType)
  def source: Option[String]      = get(Metadata.CeSource)
  def eventId: Option[String]     = get(Metadata.CeId)
  def specVersion: Option[String] = get(Metadata.CeSpecVersion)

  def withSubject(value: String): Metadata = set(Metadata.CeSubject, value)

  override def toString: String =
    entries.map((k, v) => s"$k=$v").mkString("Metadata(", ", ", ")")

  override def equals(other: Any): Boolean = other match
    case m: Metadata => normalised == m.normalised
    case _           => false

  override def hashCode: Int = normalised.hashCode

  private def normalised: Vector[(String, String)] =
    entries.map((k, v) => k.toLowerCase -> v)

  private inline def eq(a: String, b: String): Boolean = a.equalsIgnoreCase(b)

object Metadata:
  val CeSubject     = "ce-subject"
  val CeType        = "ce-type"
  val CeSource      = "ce-source"
  val CeId          = "ce-id"
  val CeSpecVersion = "ce-specversion"

  val empty: Metadata = new Metadata(Vector.empty)

  def apply(entries: Vector[(String, String)]): Metadata = new Metadata(entries)

  def of(pairs: (String, String)*): Metadata = new Metadata(pairs.toVector)
