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
   * Outside a cluster — a developer's machine, a test. There is no perimeter there and no
   * certificate to read, so every caller is admitted as this, by every caller-naming ACL.
   */
  case Local

object Caller:

  /**
   * The caller a client certificate names: its `ankka://gateway` or `ankka://<project>/<service>`
   * URI. A certificate the authority issued but that carries neither is refused rather than guessed
   * at, because a caller the platform cannot name is not one an ACL can reason about.
   */
  def fromCertificate(certificate: X509Certificate): Either[String, Caller] =
    val uris = RotatingTls.ankkaUris(certificate)
    if uris.contains(RotatingTls.GatewayUri) then Right(Gateway)
    else
      uris.flatMap(RotatingTls.parseServiceUri).headOption match
        case Some(identity) => Right(Service(identity.project, identity.service))
        case None           => Left("unrecognised caller certificate")

  /** `gateway` | `service:<project>/<name>` | `local` — the local impersonation header's form. */
  private[ankka] def encode(caller: Caller): String = caller match
    case Gateway                => "gateway"
    case Service(project, name) => s"service:$project/$name"
    case Local                  => "local"

  private[ankka] def decode(text: String): Option[Caller] = text match
    case "gateway" => Some(Gateway)
    case "local"   => Some(Local)
    case s if s.startsWith("service:") =>
      s.stripPrefix("service:").split('/') match
        case Array(project, name) if project.nonEmpty && name.nonEmpty =>
          Some(Service(project, name))
        case _ => None
    case _ => None

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

  /** Whether this admits `caller`, for a service whose own identity is `self`. */
  def admits(caller: Caller, self: RotatingTls.Identity): Boolean = (this, caller) match
    case (_, Caller.Local)                                  => true
    case (Internet, Caller.Gateway)                         => true
    case (NamedService(Some(p), n), Caller.Service(cp, cn)) => p == cp && n == cn
    case (NamedService(None, n), Caller.Service(cp, cn))    => cp == self.project && n == cn
    case (AnyInProject, Caller.Service(cp, _))              => cp == self.project
    case (Self, Caller.Service(cp, cn)) => cp == self.project && cn == self.service
    case _                              => false

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

  private[http] def callerFrom(value: String): Caller =
    value.split(' ') match
      case Array(presented, encoded) if presented == token =>
        Caller.decode(encoded).getOrElse(Caller.Local)
      case _ => Caller.Local
