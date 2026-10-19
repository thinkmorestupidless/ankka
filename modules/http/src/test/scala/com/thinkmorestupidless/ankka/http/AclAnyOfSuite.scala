package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls

/**
 * `Acl.anyOf` (feature 054): one endpoint admitting a workload by its certificate, a person by a
 * token and an assistant by a client key. Decided by the `Admitter` the router and an MCP endpoint
 * both ask, so the order of members and the refusal a caller sees are pinned here.
 */
class AclAnyOfSuite extends munit.FunSuite:

  private val self     = RotatingTls.Identity("shop", "orders")
  private val admitter = Admitter(self, Grants.none)

  private def context(caller: Caller, headers: (String, String)*): RequestContext =
    SimpleRequestContext("POST", "/mcp", QueryParams.empty, headers.toVector, None, caller = caller)

  private def token(name: String): Acl = Acl.Authenticate { ctx =>
    ctx.header("authorization") match
      case Some(s"Bearer $presented") if presented == name => AuthDecision.Allow(Principal(name))
      case Some(_) => AuthDecision.Unauthenticated(s"""realm="$name", error="invalid_token"""")
      case None    => AuthDecision.Unauthenticated(s"""realm="$name"""")
  }

  private val unavailable = Acl.Authenticate(_ => AuthDecision.Unavailable("index not ready"))

  private def principalOf(result: Either[Refusal, RequestContext]) =
    result.toOption.flatMap(_.principal).map(_.subject)

  test("the first member that admits decides, and its principal is the request's") {
    val acl = Acl.anyOf(token("ada"), token("bob"))
    val ada = admitter.decide(acl, context(Caller.Gateway, "authorization" -> "Bearer ada"), None)
    assertEquals(principalOf(ada), Some("ada"))
  }

  test("a member that refuses falls through to the next") {
    val acl = Acl.anyOf(token("ada"), token("bob"))
    val bob = admitter.decide(acl, context(Caller.Gateway, "authorization" -> "Bearer bob"), None)
    assertEquals(principalOf(bob), Some("bob"))
  }

  test("with no credential, the first member's challenge is the answer") {
    val acl = Acl.anyOf(Acl.DenyAll, token("ada"), token("bob"))
    assertEquals(
      admitter.decide(acl, context(Caller.Gateway), None),
      Left(Refusal.Unauthenticated("""realm="ada""""))
    )
  }

  test("a challenge that says what was wrong with the credential is preferred") {
    val acl = Acl.anyOf(
      Acl.Authenticate(_ => AuthDecision.Unauthenticated("""realm="keys"""")),
      token("ada")
    )
    assertEquals(
      admitter.decide(acl, context(Caller.Gateway, "authorization" -> "Bearer eve"), None),
      Left(Refusal.Unauthenticated("""realm="ada", error="invalid_token""""))
    )
  }

  test("an unavailable member is the answer when none admits, since it might have") {
    val acl = Acl.anyOf(token("ada"), unavailable)
    assertEquals(
      admitter.decide(acl, context(Caller.Gateway), None),
      Left(Refusal.Unavailable("index not ready"))
    )
    // And one that admits is not held back by it.
    val ada = admitter.decide(acl, context(Caller.Gateway, "authorization" -> "Bearer ada"), None)
    assertEquals(principalOf(ada), Some("ada"))
  }

  test("a named workload is admitted with no credential; a token from the internet too") {
    val acl     = Acl.anyOf(Acl.allowCallers(Callers.service("support")), token("ada"))
    val support = admitter.decide(acl, context(Caller.Service("shop", "support")), None)
    assert(support.isRight, support)
    assertEquals(principalOf(support), None)

    val ada = admitter.decide(acl, context(Caller.Gateway, "authorization" -> "Bearer ada"), None)
    assertEquals(principalOf(ada), Some("ada"))

    // The internet with no credential is asked for one, not merely forbidden.
    assertEquals(
      admitter.decide(acl, context(Caller.Gateway), None),
      Left(Refusal.Unauthenticated("""realm="ada""""))
    )
  }

  test("an all-refusing tree answers the first refusal") {
    val acl = Acl.anyOf(Acl.DenyAll, Acl.AllowIf(_ => false))
    assert(admitter.decide(acl, context(Caller.Gateway), None).left.exists {
      case Refusal.Forbidden(_) => true
      case _                    => false
    })
  }

  test("namesGranted and the authenticators look inside the tree") {
    val acl = Acl.anyOf(Acl.allowCallers(CallerMatcher.Granted), token("ada"))
    assert(Acl.namesGranted(acl))
    assertEquals(Acl.authenticators(Acl.anyOf(token("a"), Acl.anyOf(token("b")))).size, 2)
  }
