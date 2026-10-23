package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls

/** Why an ACL did not admit a request, before it is rendered as a response. */
private[ankka] enum Refusal:
  /** Rendered as `401` with `WWW-Authenticate: Bearer <challenge>`. */
  case Unauthenticated(challenge: String)
  case Forbidden(reason: String)
  case Unavailable(reason: String)

/**
 * Decides ACLs for requests to one server: who this service is, and the grants it holds. The one
 * place an `Acl` is turned into a decision for HTTP; the router asks it for every request, and an
 * endpoint asks it again from inside a handler through `Admission.decide`.
 */
private[ankka] final class Admitter(val self: RotatingTls.Identity, grants: Grants):

  def decide(
      acl: Acl,
      context: RequestContext,
      target: Option[GrantTarget]
  ): Either[Refusal, RequestContext] =
    def forbidden = Left(Refusal.Forbidden("not permitted by this endpoint's acl"))
    acl match
      case Acl.DenyAll            => forbidden
      case Acl.AllowAll           => Right(context)
      case Acl.AllowIf(predicate) =>
        if predicate(context) then Right(context) else forbidden
      case Acl.AllowCallers(matchers) =>
        // The same refusal text as every other ACL: naming the callers that would have been
        // admitted tells an unauthorised caller whose certificate to go looking for — and says
        // nothing of which grants exist.
        if matchers.exists(_.admits(context.caller, self, target, grants)) then Right(context)
        else forbidden
      case Acl.Authenticate(decide) =>
        decide(context) match
          case AuthDecision.Allow(principal) => Right(Admission.withPrincipal(context, principal))
          case AuthDecision.Unauthenticated(challenge) => Left(Refusal.Unauthenticated(challenge))
          case AuthDecision.Forbidden(reason)          => Left(Refusal.Forbidden(reason))
          case AuthDecision.Unavailable(reason)        => Left(Refusal.Unavailable(reason))
      case Acl.AnyOf(acls) => anyOf(acls, context, target)

  /**
   * The first member that admits. When none does: an unavailable member's answer, since it might
   * have admitted; else the first challenge that says what was wrong with the credential presented
   * (`error=`), then the first challenge; else the first refusal.
   */
  private def anyOf(
      acls: Vector[Acl],
      context: RequestContext,
      target: Option[GrantTarget]
  ): Either[Refusal, RequestContext] =
    val refusals = Vector.newBuilder[Refusal]
    val admitted = acls.iterator
      .map(acl =>
        decide(acl, context, target) match
          case Right(admitted) => Some(admitted)
          case Left(refused)   =>
            refusals += refused
            None
      )
      .collectFirst { case Some(admitted) => admitted }
    admitted match
      case Some(context) => Right(context)
      case None          =>
        val all         = refusals.result()
        val unavailable = all.collectFirst { case r: Refusal.Unavailable => r }
        val challenges  = all.collect { case r: Refusal.Unauthenticated => r }
        val pointed     = challenges.find(_.challenge.contains("error="))
        Left(
          unavailable
            .orElse(pointed)
            .orElse(challenges.headOption)
            .orElse(all.headOption)
            .getOrElse(Refusal.Forbidden("not permitted by this endpoint's acl"))
        )

private[ankka] object Admitter:
  /** Outside a server: no certificate, no grants. Every caller is `Caller.Local` there. */
  val local: Admitter = Admitter(RotatingTls.Identity("local", "local"), Grants.none)

/**
 * Admission asked from inside a handler (feature 054): an MCP endpoint decides each tool's ACL
 * against the request it is serving, with the server's own `Admitter`, on the handler's thread.
 */
private[ankka] object Admission:

  private val current = ThreadLocal[(Admitter, Option[GrantTarget])]()

  /** Runs `body` with `admitter` deciding for `target`; the router wraps every dispatch in it. */
  def within[A](admitter: Admitter, target: Option[GrantTarget])(body: => A): A =
    val previous = current.get()
    current.set((admitter, target))
    try body
    finally if previous == null then current.remove() else current.set(previous)

  /**
   * Decides `acl` for the request on this thread, as the server decided its route's — from the
   * request as it arrived, with no principal another ACL established.
   */
  def decide(acl: Acl): Either[Refusal, RequestContext] =
    val (_, target) = Option(current.get()).getOrElse((Admitter.local, None))
    decide(acl, target)

  /**
   * As `decide`, for what a grant would name instead of the route: an MCP endpoint's tool, which a
   * grant opens by its own record (`MCP /mcp get_cart`), not by the route it is served on.
   */
  def decide(acl: Acl, target: Option[GrantTarget]): Either[Refusal, RequestContext] =
    val context = RequestScope.currentContext.getOrElse(
      throw IllegalStateException("admission is only decided inside a route handler")
    )
    val (admitter, _) = Option(current.get()).getOrElse((Admitter.local, None))
    admitter.decide(acl, withoutPrincipal(context), target)

  def withPrincipal(context: RequestContext, principal: Principal): RequestContext =
    context match
      case simple: SimpleRequestContext => simple.copy(principal = Some(principal))
      case other                        => copyOf(other).copy(principal = Some(principal))

  private def withoutPrincipal(context: RequestContext): RequestContext =
    context match
      case simple: SimpleRequestContext => simple.copy(principal = None)
      case other                        => copyOf(other)

  private def copyOf(context: RequestContext): SimpleRequestContext =
    SimpleRequestContext(
      context.method,
      context.path,
      context.query,
      context.headers,
      context.remoteAddress,
      None,
      context.caller
    )
