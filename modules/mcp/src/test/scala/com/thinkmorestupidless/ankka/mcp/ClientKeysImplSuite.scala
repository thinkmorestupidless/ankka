package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, Expiry}
import com.thinkmorestupidless.ankka.mcp.TestTools.MovableClock

import java.time.Instant
import scala.concurrent.duration.*

/**
 * What `clients.clientKeys.issue` refuses before it writes anything (FR-009): which endpoint a key
 * is for, and whether its scope names that endpoint's tools.
 */
class ClientKeysImplSuite extends munit.FunSuite:

  private final class Tools(at: String, names: String*) extends McpEndpoint(at):
    val acl: Acl = Acl.AllowAll
    names.foreach(n => tool(n).describedAs(n).handle(() => n): Unit)

  private def runtime(endpoints: McpEndpoint*) =
    val r = McpRuntime(MovableClock(Instant.parse("2026-10-19T10:00:00Z")), 1.minute)
    endpoints.foreach(r.register)
    r

  private def refusal(body: => Any): CommandError =
    intercept[CommandError](body)

  test("with two MCP endpoints and none named, issue is refused naming both paths") {
    val keys    = runtime(Tools("/mcp", "list_jobs"), Tools("/office", "close_books")).clientKeys
    val refused = refusal(keys.issue("acme", Expiry.Never))
    assertEquals(refused.code, ErrorCode.BadRequest)
    assertEquals(refused.message, "the service has MCP endpoints /mcp and /office; name one")
  }

  test("a key for one endpoint scoped to a tool of another is refused naming the tool") {
    val keys    = runtime(Tools("/mcp", "list_jobs"), Tools("/office", "close_books")).clientKeys
    val refused = refusal(
      keys.issue("acme", Expiry.Never, endpoint = Some("/office"), scope = Set("list_jobs"))
    )
    assertEquals(refused.message, "the tool 'list_jobs' is not a tool of /office")
  }

  test("with one MCP endpoint, a key is for it without naming it") {
    val keys    = runtime(Tools("/mcp", "list_jobs")).clientKeys
    val refused = refusal(keys.issue("acme", Expiry.Never, scope = Set("empty_cart")))
    assertEquals(refused.message, "the tool 'empty_cart' is not a tool of /mcp")
  }

  test("what a key itself gets wrong is refused before its endpoint is looked for") {
    val keys = runtime().clientKeys
    assertEquals(refusal(keys.issue(" ", Expiry.Never)).message, "the subject is blank")
    assert(
      refusal(keys.issue("acme", Expiry.At(Instant.parse("2026-10-18T10:00:00Z")))).message
        .startsWith("the expiry 2026-10-18T10:00:00Z is in the past")
    )
  }

  test("before the index has replayed, a listing and a revocation are unavailable, not empty") {
    val keys = runtime(Tools("/mcp", "list_jobs")).clientKeys
    assertEquals(refusal(keys.list()).code, ErrorCode.Unavailable)
    assertEquals(refusal(keys.revoke("0123456789abcdef")).code, ErrorCode.Unavailable)
  }
