package nakka.crd

/**
 * An exposed service's hostname: `<service>-<project>.<base domain>`.
 *
 * One function, here, because two processes derive it and must agree: the control plane, to show it
 * and to refuse a collision; the operator, to render the route. `crd` is the one module both can
 * see, and it depends on nothing — which this object must keep true.
 *
 * One label under the base domain, not `<service>.<project>.<base>`, because a wildcard is exactly
 * one label deep — the platform's single certificate (`*.<base>`) and the Gateway's listener alike
 * — and the certificate belongs to the installation, not to any service. The cost is that a label
 * has 63 characters and names may contain hyphens, so the derivation can be too long or ambiguous
 * (`a-b` in `c`, `a` in `b-c`); the control plane refuses both cases at expose time rather than
 * ever producing a hostname that does not resolve or names the wrong service. Research R2.
 *
 * The control plane's own hostname is a reserved label with no hyphen, `api`; every service label
 * has one, so the two can never coincide.
 */
object Hostnames:

  /** RFC 1035: a DNS label is at most this long. */
  val MaxLabel: Int = 63

  val ControlPlaneLabel: String = "api"

  def label(serviceName: String, projectId: String): String = s"$serviceName-$projectId"

  def of(serviceName: String, projectId: String, baseDomain: String): String =
    s"${label(serviceName, projectId)}.$baseDomain"

  def controlPlane(baseDomain: String): String = s"$ControlPlaneLabel.$baseDomain"

  /** Why this pair cannot be exposed, if it cannot. Empty means it can. */
  def problems(serviceName: String, projectId: String): Vector[String] =
    val l = label(serviceName, projectId)
    if l.length > MaxLabel then
      Vector(
        s"hostname label '$l' is ${l.length} characters, over the $MaxLabel character limit for " +
          "a DNS label; a shorter service name or project id is the only fix"
      )
    else Vector.empty
