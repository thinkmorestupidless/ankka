package com.thinkmorestupidless.ankka.runtime

/**
 * What a call to another service may not carry from the handler that made it.
 *
 * Who is calling is the certificate's to say, never a header's. The platform's own headers
 * (`X-Ankka-*`) tell a web-hosted service's process who sent a request and, on a developer's
 * machine with the process's token, a runtime who the caller is; `Forwarded` and `X-Forwarded-*`
 * say where a request was sent, which only a proxy of the platform knows. `Host`, `Content-Length`
 * and `Expect` belong to the connection and the body, which the client makes, and the JDK's client
 * refuses them outright. The hop-by-hop headers describe one connection and never cross to another.
 * `Content-Type` is the request's own parameter, so one given among the headers would make a
 * second.
 *
 * The proxy keeps the same list for the same reason; `OutboundHeadersSuite` in `proxy`, which sees
 * both, holds them together.
 */
private[ankka] object OutboundHeaders:

  private val Prefixes: Vector[String] = Vector("x-ankka-", "x-forwarded-")

  private val Names: Set[String] = Set(
    "forwarded",
    "host",
    "content-length",
    "content-type",
    "expect",
    // Hop-by-hop.
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade"
  )

  def removed(name: String): Boolean =
    val lower = name.toLowerCase(java.util.Locale.ROOT)
    Names.contains(lower) || Prefixes.exists(lower.startsWith)

  /** The headers a request is sent with, in the order given. */
  def sent(headers: Seq[(String, String)]): Seq[(String, String)] =
    headers.filterNot((name, _) => removed(name))
