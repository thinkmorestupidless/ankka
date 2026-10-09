package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.HexFormat

/**
 * Who sent a request, as the platform established it.
 *
 * In a cluster every connection to a service is mutual TLS, and the client's certificate was issued
 * by the installation's own authority for exactly one workload. So the caller is a fact the
 * platform vouches for, not a claim the request makes: a header naming a service is still evidence
 * of nothing, and none is read.
 *
 * Distinct from `Principal`. A principal is *whose behalf* a request is made on — a user, verified
 * from a token by an `Acl.Authenticate` — and a caller is *which workload* sent it. A request from
 * the `orders` service on behalf of a signed-in user has both.
 */
enum Caller:
  /** Arrived through the installation's gateway: from the internet, whoever that was. */
  case Gateway

  /** Another workload of this installation, named by its certificate. */
  case Service(project: String, name: String)

  /**
   * A machine registered on an organization (feature 040), arrived through the gateway with a token
   * the control plane issued and this service verified before any ACL ran. Without a token, or with
   * any other, the same request is `Gateway`.
   */
  case Machine(organization: String, name: String)

  /**
   * Outside a cluster — a developer's machine, a test. There is no perimeter there and no
   * certificate to read, so every caller is admitted as this, by every caller-naming ACL.
   */
  case Local

object Caller:

  /**
   * The caller a client certificate names, for the service whose own identity is `self`, in this
   * order: its `ankka://gateway` URI is the internet; otherwise its first
   * `ankka://<project>/<service>` URI is that service; otherwise an
   * `ankka://<project>/<service>/mount` URI is the internet too, when its project is `self`'s.
   *
   * The last is a request under a web-hosted service's mount (feature 021): the browser's, passed
   * on by that service's proxy, and so the internet's — within a project. A mount of another
   * project is refused, as is any mount with no `self` to compare it with, and a certificate the
   * authority issued that names none of these is refused rather than guessed at, because a caller
   * the platform cannot name is not one an ACL can reason about.
   */
  def fromCertificate(
      certificate: X509Certificate,
      self: Option[RotatingTls.Identity]
  ): Either[String, Caller] =
    val uris = RotatingTls.ankkaUris(certificate)
    if uris.contains(RotatingTls.GatewayUri) then Right(Gateway)
    else
      uris.flatMap(RotatingTls.parseServiceUri).headOption match
        case Some(identity) => Right(Service(identity.project, identity.service))
        case None =>
          uris.flatMap(RotatingTls.parseMountUri).headOption match
            case Some(mount) if self.exists(_.project == mount.project) => Right(Gateway)
            case Some(_) if self.nonEmpty => Left("a request under a mount of another project")
            case _                        => Left("unrecognised caller certificate")

  /**
   * `gateway` | `service:<project>/<name>` | `machine:<organization>/<name>` | `local` — the local
   * impersonation header's form, and a grant's grantee's.
   */
  private[ankka] def encode(caller: Caller): String = caller match
    case Gateway                => "gateway"
    case Service(project, name) => s"service:$project/$name"
    case Machine(org, name)     => s"machine:$org/$name"
    case Local                  => "local"

  private[ankka] def decode(text: String): Option[Caller] = text match
    case "gateway" => Some(Gateway)
    case "local"   => Some(Local)
    case s if s.startsWith("service:") =>
      pair(s.stripPrefix("service:")).map(Service.apply)
    case s if s.startsWith("machine:") =>
      pair(s.stripPrefix("machine:")).map(Machine.apply)
    case _ => None

  private def pair(text: String): Option[(String, String)] =
    text.split('/') match
      case Array(first, second) if first.nonEmpty && second.nonEmpty => Some(first -> second)
      case _                                                         => None

/**
 * One kind of caller an ACL admits — Akka's principals, made honest by a certificate.
 *
 * `project = None` on a named service means this service's own project, which is how a caller in
 * the same project is usually meant; name a project to admit a service from another.
 */
enum CallerMatcher:
  /** The gateway, which is to say the internet. */
  case Internet
  case NamedService(project: Option[String], name: String)

  /** Any service in this service's own project. */
  case AnyInProject

  /** This service itself — another of its own instances, or itself through its own address. */
  case Self

  /**
   * A caller holding a grant, made by this service's project, on the route or method being called
   * (feature 040): a service of another project or a registered machine. The grant is data the
   * project's owners change; that a route may be granted at all is this matcher, in reviewed code.
   */
  case Granted

  /** Whether this admits `caller`, for a service whose own identity is `self`. */
  def admits(caller: Caller, self: RotatingTls.Identity): Boolean =
    admits(caller, self, None, Grants.none)

  /**
   * As above, for a call to `target` with `grants` the grants naming this service. A machine is a
   * request from the internet that proved who it is, so the internet admits it too.
   */
  def admits(
      caller: Caller,
      self: RotatingTls.Identity,
      target: Option[GrantTarget],
      grants: Grants
  ): Boolean = (this, caller) match
    case (_, Caller.Local)                                  => true
    case (Internet, Caller.Gateway | _: Caller.Machine)     => true
    case (NamedService(Some(p), n), Caller.Service(cp, cn)) => p == cp && n == cn
    case (NamedService(None, n), Caller.Service(cp, cn))    => cp == self.project && n == cn
    case (AnyInProject, Caller.Service(cp, _))              => cp == self.project
    case (Self, Caller.Service(cp, cn)) => cp == self.project && cn == self.service
    case (Granted, Caller.Service(_, _) | Caller.Machine(_, _)) =>
      target.exists(grants.admits(caller, _))
    case _ => false

/**
 * The spellings an endpoint uses: `Acl.allowCallers(Callers.internet, Callers.service("orders"))`.
 */
object Callers:
  val internet: CallerMatcher              = CallerMatcher.Internet
  def service(name: String): CallerMatcher = CallerMatcher.NamedService(None, name)
  def service(project: String, name: String): CallerMatcher =
    CallerMatcher.NamedService(Some(project), name)
  val anyInProject: CallerMatcher = CallerMatcher.AnyInProject
  val self: CallerMatcher         = CallerMatcher.Self

  /**
   * Whoever this service's project has granted the route or method to (feature 040). A route that
   * does not name this can never be granted, whatever the project's owners do.
   */
  val granted: CallerMatcher = CallerMatcher.Granted

/**
 * How a test names a caller outside a cluster, where there is no certificate to read.
 *
 * A request carrying `X-Ankka-Local-Caller: <token> <caller>` is admitted as that caller when the
 * token is this process's; anything else is `Caller.Local`. The token is random per JVM and never
 * leaves it except to the test kit in the same JVM — or, set through `ANKKA_LOCAL_CALLER_TOKEN`, to
 * a language SDK's test kit that starts a sidecar and so must tell it the token. This is not a
 * header trusted by its value: a caller who does not hold the secret cannot choose who they are,
 * and under TLS the header is not read at all.
 */
object LocalCallers:
  val Header: String = "X-Ankka-Local-Caller"

  lazy val token: String =
    sys.env
      .get("ANKKA_LOCAL_CALLER_TOKEN")
      .filter(_.nonEmpty)
      .getOrElse {
        val bytes = new Array[Byte](24)
        new SecureRandom().nextBytes(bytes)
        HexFormat.of().formatHex(bytes)
      }

  /** The header that makes a local request arrive as `caller`. */
  def header(caller: Caller): (String, String) = Header -> s"$token ${Caller.encode(caller)}"

  private[ankka] def callerFrom(value: String): Caller =
    value.split(' ') match
      case Array(presented, encoded) if presented == token =>
        Caller.decode(encoded).getOrElse(Caller.Local)
      case _ => Caller.Local
