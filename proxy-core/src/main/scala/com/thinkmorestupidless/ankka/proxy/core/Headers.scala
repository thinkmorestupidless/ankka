package com.thinkmorestupidless.ankka.proxy.core

/**
 * What the proxy does to a request's headers before passing it on.
 *
 * Headers are an ordered list of name and value, because a name may appear more than once and the
 * order is the sender's. Names are compared without regard to case.
 */
object Headers:

  val Caller: String         = "X-Ankka-Caller"
  val ForwardedProto: String = "X-Forwarded-Proto"
  val ForwardedHost: String  = "X-Forwarded-Host"
  val ForwardedPort: String  = "X-Forwarded-Port"
  val ForwardedFor: String   = "X-Forwarded-For"
  val Host: String           = "Host"

  /** Every header a proxy of the platform sets or reads; none of them is the request's to say. */
  val PlatformPrefix: String = "x-ankka-"

  /** The hop-by-hop headers, which describe one connection and never cross a proxy. */
  val HopByHop: Set[String] = Set(
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade"
  )

  private val Forwarded: Set[String] =
    Set(ForwardedProto, ForwardedHost, ForwardedPort).map(_.toLowerCase)

  /**
   * The headers a request is passed to the process with.
   *
   * Removed first, whoever sent the request: every header whose name starts `X-Ankka-`,
   * `Forwarded`, the hop-by-hop headers, and every `X-Forwarded-*` the proxy sets.
   * `X-Forwarded-For` is kept only from the internet, where the gateway wrote its last entry; a
   * service's request carries one only because the service said so.
   *
   * Then set: `X-Ankka-Caller`, and the address the request was sent to as `X-Forwarded-Proto`,
   * `X-Forwarded-Host`, `X-Forwarded-Port` and `Host`. That address is derived, never read: for the
   * internet it is what a mounting proxy stated, else the public authority the operator gave the
   * proxy, and when there is neither the four are left out and `Host` passes as it arrived; for a
   * service it is the web-hosted service's in-cluster address; locally it is the address the proxy
   * listens on, over plain HTTP.
   */
  def inbound(
      sender: Sender,
      settings: ProxySettings,
      received: Vector[(String, String)]
  ): Vector[(String, String)] =
    val authority: Option[(String, String)] = sender match
      case Sender.Internet(stated) => stated.orElse(settings.publicAuthority).map("https" -> _)
      case Sender.Service(_, _)    => Some("https" -> inClusterAuthority(settings))
      case Sender.Local =>
        Some("http" -> settings.publicAuthority.getOrElse(s"127.0.0.1:${settings.port}"))
    val keepForwardedFor = sender match
      case Sender.Internet(_) => true
      case _                  => false

    val kept = received.filterNot { (name, _) =>
      val n = name.toLowerCase
      n.startsWith(PlatformPrefix) || n == "forwarded" || HopByHop(n) || Forwarded(n) ||
      (n == "x-forwarded-for" && !keepForwardedFor) ||
      (n == "host" && authority.isDefined)
    }
    val address = authority.toVector.flatMap { (scheme, raw) =>
      val port      = portOf(raw).getOrElse(defaultPort(scheme))
      val authority = if port == defaultPort(scheme) then hostOf(raw) else s"${hostOf(raw)}:$port"
      Vector(
        ForwardedProto -> scheme,
        ForwardedHost  -> authority,
        ForwardedPort  -> port.toString,
        Host           -> authority
      )
    }
    (kept :+ (Caller -> Sender.describe(sender))) ++ address

  /** `<service>.<prefix>-<project>.svc.cluster.local:<port>`: how a service reaches this one. */
  def inClusterAuthority(settings: ProxySettings): String =
    s"${settings.service}.${settings.namespacePrefix}-${settings.project}.svc.cluster.local:${settings.port}"

  /**
   * The headers a call at the calling address is sent on with: the process's own, less every header
   * starting `X-Ankka-` (who called is the certificate's to say), the hop-by-hop headers, and
   * `Host`, which is the called service's.
   */
  def outbound(received: Vector[(String, String)]): Vector[(String, String)] =
    received.filterNot { (name, _) =>
      val n = name.toLowerCase
      n.startsWith(PlatformPrefix) || HopByHop(n) || n == "host"
    }

  /**
   * A response header the proxy does not pass back: the hop-by-hop ones, and the length it sets.
   */
  def droppedFromResponse(name: String): Boolean =
    val n = name.toLowerCase
    HopByHop(n) || n == "content-length"

  private def defaultPort(scheme: String): Int = if scheme == "https" then 443 else 80

  /** The port in an authority, when it names one; an IPv6 literal's brackets are not a port. */
  def portOf(authority: String): Option[Int] =
    val afterHost = authority.lastIndexOf(':')
    val bracket   = authority.lastIndexOf(']')
    if afterHost > bracket then authority.substring(afterHost + 1).toIntOption else None

  def hostOf(authority: String): String =
    val afterHost = authority.lastIndexOf(':')
    val bracket   = authority.lastIndexOf(']')
    if afterHost > bracket then authority.substring(0, afterHost) else authority
