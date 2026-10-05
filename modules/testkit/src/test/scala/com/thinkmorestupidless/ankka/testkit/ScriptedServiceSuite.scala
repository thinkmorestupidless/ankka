package com.thinkmorestupidless.ankka.testkit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*

/** Another service played on loopback records what it is sent and answers as it was told. */
class ScriptedServiceSuite extends munit.FunSuite:

  private val http = HttpClient.newHttpClient()

  private def post(scripted: ScriptedService, path: String, body: String): HttpResponse[String] =
    http.send(
      HttpRequest
        .newBuilder(URI(scripted.address + path))
        .header("Content-Type", "text/plain")
        .header("X-Request-Id", "r1")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("a request is recorded whole: method, path with query, headers, content type and body") {
    val scripted = ScriptedService.start()
    try
      assertEquals(post(scripted, "/a?b=c", "hello").body, "ok")
      val request = scripted.requests.head
      assertEquals((request.method, request.path), ("POST", "/a?b=c"))
      assertEquals(request.contentType, Some("text/plain"))
      assertEquals(request.header("x-request-id"), Some("r1"))
      assertEquals(request.text, "hello")
    finally scripted.stop()
  }

  test("failNext fails exactly one request, and then it answers as before") {
    val scripted = ScriptedService.start()
    try
      scripted.failNext(503)
      assertEquals(post(scripted, "/", "").statusCode, 503)
      assertEquals(post(scripted, "/", "").statusCode, 200)
    finally scripted.stop()
  }

  test("delay waits before answering") {
    val scripted = ScriptedService.start()
    try
      scripted.delay(300.millis)
      val started = System.nanoTime()
      val _       = post(scripted, "/", "")
      assert((System.nanoTime() - started).nanos >= 300.millis)
    finally scripted.stop()
  }
