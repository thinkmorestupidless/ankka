package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, HostnameStatus}

/** A certificate's word: whether it is `Ready`, and a failure to renew one that already is. */
final case class CertificateView(ready: Boolean, renewalFailure: Option[String] = None)

/** The newest ACME challenge for a hostname: cert-manager's state and its reason, verbatim. */
final case class ChallengeView(state: Option[String], reason: Option[String])

/**
 * A listener's conditions in the service's ListenerSet, by type: (status, reason). `Conflicted`,
 * `Accepted`, `ResolvedRefs` and `Programmed` are the ones read.
 */
final case class ListenerView(conditions: Map[String, (Boolean, String)]):
  def is(kind: String): Option[Boolean] = conditions.get(kind).map(_._1)
  def reason(kind: String): String =
    conditions.get(kind).map(_._2).filter(_.nonEmpty).getOrElse(kind)

/** Everything read about one custom hostname on one pass (feature 045). */
final case class HostnameViews(
    hostname: String,
    certificate: Option[CertificateView] = None,
    challenge: Option[ChallengeView] = None,
    listener: Option[ListenerView] = None,
    /** The route's `Accepted` condition for the set's parent: (status, reason). */
    routeAccepted: Option[(Boolean, String)] = None
)

/**
 * Where each custom hostname stands, from what the operator read (feature 045, research R6). Pure:
 * the executor reads, this decides, as `LifecycleRules` decides about the route. The platform never
 * judges for itself whether a name resolves; the reason a pending hostname shows is the
 * authority's.
 */
object HostnameRules:

  val Pending  = "pending"
  val Serving  = "serving"
  val Rejected = "rejected"

  val NoIssuer: String =
    "the operator names no authority for custom hostnames (ANKKA_HOSTNAME_ISSUER)"
  val NoBaseDomain: String =
    "the operator has no base domain (ANKKA_BASE_DOMAIN); no route can be rendered"
  val NoPort: String    = "the service serves neither HTTP nor gRPC"
  val Issuing: String   = "the certificate is being issued"
  val Attaching: String = "the gateway is attaching the hostname"

  /**
   * One status per custom hostname on the spec, in its order, while the service is exposed; none
   * otherwise. `views` is what the executor read, by hostname.
   */
  def statuses(
      spec: AnkkaServiceSpec,
      settings: Settings,
      views: Map[String, HostnameViews]
  ): List[HostnameStatus] =
    if !spec.exposed then Nil
    else
      spec.customHostnames.distinct.map { hostname =>
        val refusal =
          if settings.hostnameIssuer.isEmpty then Some(NoIssuer)
          else if settings.baseDomain.isEmpty then Some(NoBaseDomain)
          else if spec.port.orElse(spec.grpcPort).isEmpty then Some(NoPort)
          else None
        refusal.orElse(HostnameRendering.refusal(hostname, settings)) match
          case Some(reason) => HostnameStatus(hostname, Rejected, Some(reason))
          case None         => status(views.getOrElse(hostname, HostnameViews(hostname)))
      }

  /**
   * The rules, first match wins. A conflict is a rejection whatever else is true. A certificate not
   * yet issued comes before a listener the gateway has not accepted, because a listener whose
   * certificate does not exist yet may say so, and that is the expected early state, not a refusal.
   */
  def status(v: HostnameViews): HostnameStatus =
    def at(state: String, reason: Option[String]) = HostnameStatus(v.hostname, state, reason)
    val listener                                  = v.listener
    if listener.exists(_.is("Conflicted").contains(true)) then
      at(Rejected, listener.map(_.reason("Conflicted")))
    else if !v.certificate.exists(_.ready) then
      v.challenge.flatMap(_.reason).filter(_.trim.nonEmpty) match
        case Some(reason) => at(Pending, Some(s"waiting for the certificate: ${reason.trim}"))
        case None         => at(Pending, Some(Issuing))
    else if listener.exists(_.is("Accepted").contains(false)) then
      at(Rejected, listener.map(_.reason("Accepted")))
    else if v.routeAccepted.exists(!_._1) then at(Rejected, v.routeAccepted.map(_._2))
    else if !listener.exists(l =>
        l.is("Programmed").contains(true) && !l.is("ResolvedRefs").contains(false)
      )
    then at(Pending, Some(Attaching))
    else at(Serving, v.certificate.flatMap(_.renewalFailure).map(m => s"renewal refused: $m"))

/**
 * Reads fields of an object from a CRD the operator does not model (cert-manager's), by path: a
 * typed round trip of another project's schema is the parsing trap `.claude/rules/kubernetes.md`
 * records, and only a handful of strings are needed.
 *
 * The operator's client reads untyped JSON into Scala collections (`AnkkaSerialization` registers
 * the Scala module), and fabric8's own mapper into Java ones; both are read, since a reader that
 * knew only one shape read every field as absent and reported a ready certificate as still being
 * issued.
 */
private[operator] object Generic:

  def field(
      resource: io.fabric8.kubernetes.api.model.GenericKubernetesResource,
      path: String*
  ): Option[Any] =
    path.foldLeft(Option[Any](resource.getAdditionalProperties))((at, key) =>
      at.flatMap(child(_, key))
    )

  private def child(node: Any, key: String): Option[Any] = node match
    case m: scala.collection.Map[?, ?] => m.asInstanceOf[scala.collection.Map[Any, Any]].get(key)
    case m: java.util.Map[?, ?]        => Option(m.get(key))
    case _                             => None

  private def elements(node: Any): Vector[Any] = node match
    case s: scala.collection.Iterable[?] if !node.isInstanceOf[scala.collection.Map[?, ?]] =>
      s.toVector
    case l: java.util.List[?] => l.toArray.toVector
    case _                    => Vector.empty

  def string(
      resource: io.fabric8.kubernetes.api.model.GenericKubernetesResource,
      path: String*
  ): Option[String] =
    field(resource, path*).collect { case s: String => s }

  /** `status.conditions` by type: (status, reason, message). */
  def conditions(
      resource: io.fabric8.kubernetes.api.model.GenericKubernetesResource
  ): Map[String, (String, String, String)] =
    field(resource, "status", "conditions").toVector
      .flatMap(elements)
      .map { c =>
        def text(key: String) = child(c, key).map(_.toString).getOrElse("")
        text("type") -> (text("status"), text("reason"), text("message"))
      }
      .toMap
