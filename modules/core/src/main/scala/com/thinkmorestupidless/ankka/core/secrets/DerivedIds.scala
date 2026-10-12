package com.thinkmorestupidless.ankka.core.secrets

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The one derivation of a secret's id in Secret Manager.
 *
 * A Secret Manager id is letters, digits, `_` and `-`, at most 255 characters, and unique in the
 * Google Cloud project — which every service of the installation shares. So an id must carry the
 * project and the service, two names must never share one, and access must be grantable by a
 * *prefix*: Google Cloud conditions access on `resource.name.startsWith(…)`, and a create cannot be
 * conditioned at all, so a service's prefix is what keeps it to its own secrets.
 *
 * A project id and a service name are DNS labels and never contain `_`, which makes `_` free as the
 * separator:
 *
 *   - a service secret: `s_<project>_<service>_<encoded name>`, prefix `s_<project>_<service>_`
 *   - an entry of a project secret: `p_<project>_<encoded secret>__<encoded entry>`, prefix
 *     `p_<project>_`
 *
 * The encoding writes `_` as `_u`, `.` as `_p` and `/` as `_s` and every other allowed character as
 * itself, so every `_` it writes is followed by `u`, `p` or `s`: it is a prefix code, `__` never
 * occurs in its output, and an encoded name never ends in `_`. That is what makes the entry form
 * readable back (the first `__` ends the secret) and no prefix a prefix of another's:
 * `s_shop_cart_` is not a prefix of `s_shop_cart2_…`, and an `s_` id is never a `p_` one.
 *
 * An id that would be longer than 255 characters is the prefix, `_`, and the SHA-256 of the name in
 * hex. After the prefix's own `_` that is `__` and a hex digit, where an encoded name's `_` is
 * followed by `u`, `p` or `s` — none of them hex — so a digest id is never an encoded one. The name
 * itself is kept on the secret as an annotation a platform administrator can read.
 */
private[ankka] object DerivedIds:

  val MaxLength: Int = 255

  /**
   * Annotation keys on a secret the platform makes. Secret Manager's keys are at most 63 characters
   * of letters, digits, `-`, `_` and `.`, starting and ending with a letter or digit — no `/`, so
   * not Kubernetes' prefixed form.
   */
  val NameAnnotation: String          = "ankka-name"
  val ServiceAnnotation: String       = "ankka-service"
  val ProjectAnnotation: String       = "ankka-project"
  val ProjectSecretAnnotation: String = "ankka-project-secret"
  val EntryAnnotation: String         = "ankka-entry"

  /** What every id of `service`'s service secrets begins with. */
  def servicePrefix(project: String, service: String): String = s"s_${project}_${service}_"

  /** What every id of an entry of `project`'s project secrets begins with. */
  def projectPrefix(project: String): String = s"p_${project}_"

  /** The id of the service secret `name` of `service` in `project`. */
  def service(project: String, service: String, name: String): String =
    bounded(servicePrefix(project, service), encode(name), name)

  /** The id of the entry `entry` of the project secret `secret` of `project`. */
  def projectEntry(project: String, secret: String, entry: String): String =
    bounded(projectPrefix(project), encode(secret) + "__" + encode(entry), s"$secret\u0000$entry")

  /** The annotations a service secret is made with. */
  def serviceAnnotations(project: String, service: String, name: String): Map[String, String] =
    Map(ProjectAnnotation -> project, ServiceAnnotation -> service, NameAnnotation -> name)

  /** The annotations an entry of a project secret is made with. */
  def entryAnnotations(project: String, secret: String, entry: String): Map[String, String] =
    Map(ProjectAnnotation -> project, ProjectSecretAnnotation -> secret, EntryAnnotation -> entry)

  private[secrets] def encode(name: String): String =
    val out = StringBuilder(name.length + 8)
    name.foreach {
      case '_' => out.append("_u")
      case '.' => out.append("_p")
      case '/' => out.append("_s")
      case c   => out.append(c)
    }
    out.result()

  private def bounded(prefix: String, encoded: String, original: String): String =
    val id = prefix + encoded
    if id.length <= MaxLength then id else prefix + "_" + sha256(original)

  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
