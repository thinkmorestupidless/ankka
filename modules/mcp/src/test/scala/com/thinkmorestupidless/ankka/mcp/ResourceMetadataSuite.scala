package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig, OidcVerifier, TestIssuer}
import com.thinkmorestupidless.ankka.http.{Acl, Caller, QueryParams, SimpleRequestContext}

/**
 * An MCP endpoint's protected resource metadata (research R10): which ACLs admit tokens, the
 * issuers they name, the document a client reads, and the challenge that tells a client where it
 * is.
 */
class ResourceMetadataSuite extends munit.FunSuite:

  private val customers = FunFixture[TestIssuer](
    _ =>
      TestIssuer(
        issuer = "https://customers.example.test",
        name = "customers",
        defaultAudience = "shop"
      ),
    _.stop()
  )

  private def tokens(issuer: TestIssuer): Acl =
    Oidc.authenticate(OidcVerifier.remote(OidcConfig(Vector(issuer.asIssuer()))), "ankka")

  private def request(host: String, caller: Caller = Caller.Local) =
    SimpleRequestContext(
      "POST",
      "/mcp",
      QueryParams.empty,
      Vector("Host" -> host),
      None,
      None,
      caller
    )

  customers.test("an ACL of the service's issuers admits tokens and names each issuer") { issuer =>
    val acl = tokens(issuer)
    assert(ResourceMetadata.admitsTokens(acl))
    assertEquals(
      ResourceMetadata.authorizationServers(acl),
      Vector("https://customers.example.test")
    )
  }

  customers.test("an ACL of client keys or tokens admits both, and names only the issuers") {
    issuer =>
      val acl = Acl.anyOf(ClientKeys.authenticate(), tokens(issuer))
      assert(ResourceMetadata.admitsTokens(acl))
      assertEquals(
        ResourceMetadata.authorizationServers(acl),
        Vector("https://customers.example.test")
      )
      assertEquals(Acl.flatten(acl).count { case Acl.Authenticate(_) => true; case _ => false }, 2)
  }

  test("an ACL of client keys alone admits no token and names no issuer") {
    val acl = ClientKeys.authenticate()
    assert(!ResourceMetadata.admitsTokens(acl))
    assertEquals(ResourceMetadata.authorizationServers(acl), Vector.empty)
    assert(!ResourceMetadata.admitsTokens(Acl.AllowAll))
    assert(!ResourceMetadata.admitsTokens(null), "an ACL not stated admits nothing")
  }

  test("the document names the resource as the client reached it, and the issuers") {
    val servers = Vector("https://customers.example.test")
    val plain   =
      ResourceMetadata.document(request("cart.shop.example"), "/mcp", secure = false, servers)
    assertEquals(
      plain,
      Json.obj(
        "resource"                 -> Json.str("http://cart.shop.example/mcp"),
        "authorization_servers"    -> Json.arr(Json.str("https://customers.example.test")),
        "bearer_methods_supported" -> Json.arr(Json.str("header"))
      )
    )
    val served =
      ResourceMetadata.document(request("cart.shop.example"), "/mcp", secure = true, servers)
    assertEquals(served("resource"), Some(Json.str("https://cart.shop.example/mcp")))
    // The gateway terminates the client's TLS, so a request through it was made over https.
    val gateway = ResourceMetadata.document(
      request("cart.shop.example", Caller.Gateway),
      "/mcp",
      false,
      servers
    )
    assertEquals(gateway("resource"), Some(Json.str("https://cart.shop.example/mcp")))
  }

  test("the document is at the well-known path, then the endpoint's") {
    assertEquals(ResourceMetadata.documentPath("/mcp"), "/.well-known/oauth-protected-resource/mcp")
    assertEquals(
      ResourceMetadata.documentPath("office/mcp/"),
      "/.well-known/oauth-protected-resource/office/mcp"
    )
    assertEquals(ResourceMetadata.documentPath("/"), "/.well-known/oauth-protected-resource")
  }

  test("a 401's challenge keeps the ACL's words and names where the document is") {
    val named = ResourceMetadata.challenge(
      """realm="ankka"""",
      request("cart.shop.example"),
      "/mcp",
      secure = true
    )
    assertEquals(
      named,
      """realm="ankka", resource_metadata="https://cart.shop.example/.well-known/oauth-protected-resource/mcp""""
    )
    val pointed = ResourceMetadata.challenge(
      """realm="ankka", error="invalid_token"""",
      request("cart.shop.example"),
      "/mcp",
      secure = false
    )
    assert(
      pointed.startsWith("""realm="ankka", error="invalid_token", resource_metadata="http://"""),
      pointed
    )
  }
