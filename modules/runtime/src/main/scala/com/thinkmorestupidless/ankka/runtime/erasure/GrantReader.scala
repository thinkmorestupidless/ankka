package com.thinkmorestupidless.ankka.runtime.erasure

/**
 * One grant (`contracts/grants.md`): `project` gives `principal` — `service:<project>/<name>` or
 * `machine:<org>/<name>` — the `attributes` it names (`erasure`, `decrypt`) on `target`, a topic of
 * the project or `*`, while its state is `active`.
 */
final case class Grant(
    project: String,
    principal: String,
    target: String,
    attributes: Set[String],
    state: String = "active"
)

/**
 * The grants an installation has rendered, as the control plane and the keyring read them. Until
 * spec 040 renders grants an installation has none, and every right a grant would give is refused;
 * a test hands over its own.
 */
trait GrantReader:
  def grants(principal: String): Set[Grant]

  /** Whether `principal` holds an active grant of `attribute` in `project`. */
  def allows(principal: String, project: String, attribute: String): Boolean =
    grants(principal).exists(g =>
      g.project == project && g.state == "active" && g.attributes.contains(attribute)
    )

object GrantReader:
  /** No grant at all: what an installation has before spec 040 renders them. */
  val none: GrantReader = _ => Set.empty

  /** These grants and no others: for a test, and for a local run. */
  def of(granted: Grant*): GrantReader = principal => granted.filter(_.principal == principal).toSet

  /**
   * The grants rendered into `path` (`contracts/grants.md`), re-read when the file changes. Spec
   * 040 renders them; until it does, no installation has the file and this answers no grant.
   */
  def fromFile(path: java.nio.file.Path): GrantReader =
    // TODO(040): read the rendered grants volume, re-read by mtime, in the shape contracts/grants.md gives.
    val _ = path
    none

  /** The principal a service is known by in a grant. */
  def service(project: String, name: String): String = s"service:$project/$name"
