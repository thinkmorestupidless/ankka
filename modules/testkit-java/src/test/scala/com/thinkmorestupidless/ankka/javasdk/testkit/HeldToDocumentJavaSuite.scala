package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.routes.Players
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}

/**
 * A Java service held to its OpenAPI document by the Java kit, and serving it when it asks (feature
 * 067, T031).
 */
class HeldToDocumentJavaSuite extends munit.FunSuite with LogCapturing:

  private def players = Ankka.service("players").withEndpoint(classOf[Players])

  private def get(kit: AnkkaTestKit, path: String): HttpResponse[String] =
    kit.httpClient.send(
      HttpRequest.newBuilder(kit.http.resolve(path)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("an answer the document names is no violation; one it does not name is") {
    val kit = AnkkaTestKit.start(players, Settings.defaults().heldToDocument())
    try
      assertEquals(get(kit, "/players/p1/messages?page=2").statusCode, 200)
      assertEquals(kit.documentViolations.size, 0, kit.documentViolations)
      assertEquals(get(kit, "/players/p1/teapot").statusCode, 418)
      val found = kit.documentViolations
      assertEquals(found.size, 1, found)
      assert(found.get(0).contains("GET /players/{playerId}/teapot: answered 418"), found)
      val failure = intercept[AssertionError](kit.assertHeldToDocument())
      assert(failure.getMessage.contains("answered 418"), failure.getMessage)
      assertEquals(kit.documentViolations.size, 0, "assertHeldToDocument clears what it reported")
    finally kit.close()
  }

  test("close fails when a violation is left") {
    val kit = AnkkaTestKit.start(players, Settings.defaults().heldToDocument())
    get(kit, "/players/p1/teapot")
    val failure = intercept[AssertionError](kit.close())
    assert(failure.getMessage.contains("teapot"), failure.getMessage)
  }

  test("two held kits keep their own violations, and neither clears the other's") {
    val first  = AnkkaTestKit.start(players, Settings.defaults().heldToDocument())
    val second = AnkkaTestKit.start(players, Settings.defaults().heldToDocument())
    try
      get(first, "/players/p1/teapot")
      assertEquals(first.documentViolations.size, 1, first.documentViolations)
      assertEquals(second.documentViolations.size, 0, second.documentViolations)
      assertEquals(com.thinkmorestupidless.ankka.testkit.HeldToDocument.violations, Vector.empty)
      second.assertHeldToDocument()
      assertEquals(first.documentViolations.size, 1, "the second kit cleared the first's")
      intercept[AssertionError](first.assertHeldToDocument()): Unit
    finally
      second.close()
      first.close()
  }

  test("without heldToDocument nothing is checked") {
    val kit = AnkkaTestKit.start(players)
    try
      get(kit, "/players/p1/teapot")
      assertEquals(kit.documentViolations.size, 0)
    finally kit.close()
  }

  test("withOpenApi serves the document; without it the path is answered as no route") {
    val served = AnkkaTestKit.start(players.withOpenApi(Acl.allowAll()))
    try
      val answer = get(served, "/_ankka/openapi.json")
      assertEquals(answer.statusCode, 200, answer.body)
      assert(answer.body.contains("/players/{playerId}/messages"), answer.body)
    finally served.close()
    val plain = AnkkaTestKit.start(players)
    // Answered as a path nothing serves is: a Java service's routes sit under one endpoint whose
    // own ACL admits no one, so that is 403 rather than the Scala server's 404.
    try
      val unserved = get(plain, "/_ankka/openapi.json")
      assertEquals(unserved.statusCode, get(plain, "/nothing/here").statusCode)
      assert(!unserved.body.contains("openapi"), unserved.body)
    finally plain.close()
  }
