package com.thinkmorestupidless.ankka.operator

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicReference

/** What the kubelet's probes read: 200 while nothing is wrong, 503 naming each problem. */
class HealthCheckSuite extends munit.FunSuite:

  test("200 ok with no problem, 503 with one a line, and back") {
    val problems = new AtomicReference(Vector.empty[String])
    val check    = HealthCheck.serve(0, () => problems.get())
    try
      val client = HttpClient.newHttpClient()
      def get() =
        client.send(
          HttpRequest
            .newBuilder(URI.create(s"http://127.0.0.1:${check.port}${HealthCheck.Path}"))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      val healthy = get()
      assertEquals(healthy.statusCode, 200)
      assertEquals(healthy.body, "ok\n")

      problems.set(
        Vector("the AnkkaService informer has stopped", "the services queue has 0 of 16 workers")
      )
      val sick = get()
      assertEquals(sick.statusCode, 503)
      assertEquals(
        sick.body,
        "the AnkkaService informer has stopped\nthe services queue has 0 of 16 workers\n"
      )

      problems.set(Vector.empty)
      assertEquals(get().statusCode, 200)
    finally check.close()
  }

  test("a check that throws is a problem, not a healthy answer") {
    val check = HealthCheck.serve(0, () => throw new IllegalStateException("boom"))
    try
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest
            .newBuilder(URI.create(s"http://127.0.0.1:${check.port}${HealthCheck.Path}"))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      assertEquals(response.statusCode, 503)
      assert(response.body.contains("boom"), response.body)
    finally check.close()
  }
