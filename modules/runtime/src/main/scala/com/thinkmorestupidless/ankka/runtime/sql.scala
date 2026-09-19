package com.thinkmorestupidless.ankka.runtime

/**
 * A parameterised SQL fragment.
 *
 * Values are never spliced into the SQL text. `sql"... = $email"` produces `... = $1` plus a bound
 * parameter, so a view query cannot be turned into an injection by user-supplied data.
 *
 * Literal parts and parameters are kept separate rather than pre-rendered, because concatenating
 * two fragments has to renumber placeholders and doing that on rendered text would be guesswork.
 */
final class SqlFragment private[ankka] (
    private[ankka] val parts: Vector[String],
    val params: Vector[SqlParam]
):

  /** The SQL with `$1`, `$2`, … placeholders. */
  def render: String =
    val builder = StringBuilder()
    parts.zipWithIndex.foreach { (part, index) =>
      builder.append(part)
      if index < params.size then builder.append('$').append(index + 1): Unit
    }
    builder.toString

  /** Appends another fragment, renumbering its placeholders. */
  def ++(other: SqlFragment): SqlFragment =
    if parts.isEmpty then other
    else if other.parts.isEmpty then this
    else
      val joined =
        parts.init ++ Vector(parts.last + other.parts.head) ++ other.parts.tail
      new SqlFragment(joined, params ++ other.params)

  def isEmpty: Boolean = parts.forall(_.isBlank) && params.isEmpty

  override def toString: String = s"SqlFragment(${render}, ${params.map(_.value)})"

object SqlFragment:
  val empty: SqlFragment = new SqlFragment(Vector(""), Vector.empty)

  /** Raw SQL with no parameters. Only for text the developer controls. */
  def raw(sql: String): SqlFragment = new SqlFragment(Vector(sql), Vector.empty)

/** A bound parameter, carrying the type r2dbc needs to bind it. */
final case class SqlParam(value: Any, javaType: Class[?])

object SqlParam:
  def of(value: Any): SqlParam = value match
    case v: String => SqlParam(v, classOf[String])
    // Bound as-is for `bytea` columns; a timer payload is opaque bytes by design.
    case v: Array[Byte]       => SqlParam(v, classOf[Array[Byte]])
    case v: Int               => SqlParam(Integer.valueOf(v), classOf[Integer])
    case v: Long              => SqlParam(java.lang.Long.valueOf(v), classOf[java.lang.Long])
    case v: Boolean           => SqlParam(java.lang.Boolean.valueOf(v), classOf[java.lang.Boolean])
    case v: Double            => SqlParam(java.lang.Double.valueOf(v), classOf[java.lang.Double])
    case v: Float             => SqlParam(java.lang.Float.valueOf(v), classOf[java.lang.Float])
    case v: Short             => SqlParam(java.lang.Short.valueOf(v), classOf[java.lang.Short])
    case v: BigDecimal        => SqlParam(v.bigDecimal, classOf[java.math.BigDecimal])
    case v: java.util.UUID    => SqlParam(v, classOf[java.util.UUID])
    case v: java.time.Instant => SqlParam(v, classOf[java.time.Instant])
    case other =>
      throw IllegalArgumentException(
        s"cannot bind ${other.getClass.getName} as a SQL parameter; " +
          "convert it to a String, number, boolean, UUID or Instant first"
      )

/** `sql"..."`, and helpers for querying inside a view row's JSON payload. */
object SqlSyntax:

  extension (context: StringContext)
    def sql(args: Any*): SqlFragment =
      new SqlFragment(context.parts.toVector, args.toVector.map(SqlParam.of))

  /**
   * A text field inside a view row, as SQL.
   *
   * View rows are stored as JSON in a single column, so querying by an attribute means reaching
   * into it: `jsonText("email")` renders `payload::jsonb->>'email'`. Add an expression index on the
   * same term if the query needs to be fast.
   */
  def jsonText(field: String): SqlFragment =
    SqlFragment.raw(s"payload::jsonb->>'${escapeIdentifier(field)}'")

  /** A nested text field: `jsonText("address", "city")`. */
  def jsonText(path: String*): SqlFragment =
    val steps = path.map(p => s"'${escapeIdentifier(p)}'")
    val nav   = steps.init.map(s => s"->$s").mkString
    SqlFragment.raw(s"payload::jsonb$nav->>${steps.last}")

  /** A numeric field, cast so comparisons and ordering behave numerically. */
  def jsonNumber(field: String): SqlFragment =
    SqlFragment.raw(s"(payload::jsonb->>'${escapeIdentifier(field)}')::numeric")

  private def escapeIdentifier(field: String): String =
    // Field names come from the developer's own row type, but a stray quote would still
    // break the generated SQL, so reject rather than mangle.
    if field.contains('\'') || field.contains('\\') then
      throw IllegalArgumentException(s"invalid JSON field name '$field'")
    else field
