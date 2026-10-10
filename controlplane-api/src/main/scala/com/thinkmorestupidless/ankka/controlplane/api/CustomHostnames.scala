package com.thinkmorestupidless.ankka.controlplane.api

/**
 * A custom hostname: a name under a domain its owner brings, added to an exposed service beside the
 * hostname the platform derives (feature 045). These are the rules a name must pass before anything
 * else is asked of it — whether it is held, whether it is proved — in the order a member is told
 * them, and in the words the refusal uses.
 *
 * Pure and here, beside the descriptor's rules, so the CLI could apply them before sending; the
 * control plane is what applies them. The cap is here too, though the service entity enforces it,
 * since only it knows how many a service holds.
 */
object CustomHostnames:

  /** A service holds at most this many. Each is a certificate and a listener on the gateway. */
  val MaxPerService: Int = 5

  /**
   * RFC 1035: a label is at most 63 characters, a name at most 253 written without the root dot.
   */
  val MaxLabel: Int = 63
  val MaxName: Int  = 253

  private val Label = "[a-z0-9]([a-z0-9-]*[a-z0-9])?".r

  /** Lowercase, and without the root's trailing dot: `App.Example.COM.` is `app.example.com`. */
  def normalise(hostname: String): String =
    val lower = hostname.trim.toLowerCase(java.util.Locale.ROOT)
    if lower.endsWith(".") then lower.dropRight(1) else lower

  /** A name of exactly two labels: one a `CNAME` cannot sit at, so the record to create differs. */
  def isApex(hostname: String): Boolean = normalise(hostname).split('.').length == 2

  /**
   * Why `hostname` cannot be a custom hostname on an installation whose base domain is
   * `baseDomain`, or `None`. The name is normalised first; the caller records the normalised form.
   */
  def problem(hostname: String, baseDomain: Option[String]): Option[String] =
    val h = normalise(hostname)
    if h.contains("://") || h.contains('/') || h.contains(':') || h.exists(_.isWhitespace) then
      Some(s"a custom hostname is a name alone: '$hostname' has a scheme, a path or a port")
    else if h.contains('*') then Some("a custom hostname cannot be a wildcard")
    else if h.length > MaxName then
      Some(s"'$h' is ${h.length} characters, over the $MaxName character limit for a hostname")
    else
      val labels = h.split("\\.", -1).toVector
      labels
        .collectFirst {
          case l if l.isEmpty => s"'$h' is not a hostname: it has an empty label"
          case l if l.length > MaxLabel =>
            s"'$h' is not a hostname: label '$l' is ${l.length} characters, over the $MaxLabel " +
              "character limit"
          case l if !Label.matches(l) =>
            s"'$h' is not a hostname: label '$l' must be letters, digits and '-', and cannot " +
              "start or end with '-'"
        }
        .orElse(
          Option.when(labels.length < 2)(
            s"'$h' is not a name on the internet: a custom hostname has at least two labels"
          )
        )
        .orElse(baseDomain.map(normalise).filter(_.nonEmpty).flatMap { base =>
          Option.when(h == base || h.endsWith(s".$base"))(
            s"a custom hostname cannot be under the base domain '$base': those names are the " +
              "platform's"
          )
        })
