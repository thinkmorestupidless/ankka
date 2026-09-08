package nakka.http

/** The request context's own contract, without a server. */
class RequestContextSuite extends munit.FunSuite:

  private val context = SimpleRequestContext(
    method = "GET",
    path = "/search",
    query = QueryParams(Vector("q" -> "widgets", "tag" -> "red", "tag" -> "large", "flag" -> "")),
    headers = Vector("X-Trace-Id" -> "abc", "Accept" -> "application/json"),
    remoteAddress = None
  )

  test("required reads a present parameter and names a missing one") {
    assertEquals(context.query.required[String]("q"), "widgets")
    val failure = intercept[HttpProblem](context.query.required[String]("missing"))
    assertEquals(failure.status, 400)
    assert(failure.message.contains("'missing'"), failure.message)
  }

  test("optional distinguishes absent from unparseable") {
    assertEquals(context.query.optional[Int]("nope"), None)
    // Present but wrong is the caller's mistake, not a silent None.
    val failure = intercept[HttpProblem](context.query.optional[Int]("q"))
    assertEquals(failure.status, 400)
    assert(failure.message.contains("'q'"), failure.message)
  }

  test("repeated parameters keep their order") {
    assertEquals(context.query.all[String]("tag"), Vector("red", "large"))
    assertEquals(context.query.rawAll("tag"), Vector("red", "large"))
    assertEquals(context.query.raw("tag"), Some("red"), "raw returns the first")
  }

  test("a valueless parameter reads as a set flag") {
    assert(context.query.flag("flag"))
    assert(!context.query.flag("absent"))
  }

  test("header lookup ignores case") {
    assertEquals(context.header("x-trace-id"), Some("abc"))
    assertEquals(context.header("X-TRACE-ID"), Some("abc"))
    assertEquals(context.header("nope"), None)
  }

  test("the scope makes a context reachable, then clears it") {
    assertEquals(RequestScope.currentContext, None)
    RequestScope.withContext(context) {
      assertEquals(RequestScope.currentContext.map(_.path), Some("/search"))
    }
    // Cleared on the way out, so a reused thread cannot see a stale request.
    assertEquals(RequestScope.currentContext, None)
  }

  test("the scope clears even when the handler throws") {
    intercept[RuntimeException] {
      RequestScope.withContext(context)(throw RuntimeException("boom"))
    }
    assertEquals(RequestScope.currentContext, None)
  }
