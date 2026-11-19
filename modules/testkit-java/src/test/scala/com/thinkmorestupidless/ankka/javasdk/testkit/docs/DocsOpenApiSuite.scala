package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt

/** The Java samples of the OpenAPI page: what they serve, and that a kit held to it passes. */
class DocsOpenApiSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("the served document holds the greeting route, its summary and its refusal") {
    val document = OpenApiDocs.document()
    assert(document.contains("\"/greetings/{name}\""), document)
    assert(document.contains("A greeting for one name"), document)
    assert(document.contains("nobody has that name"), document)
  }

  test("a kit held to the document closes quietly when every answer is one it names") {
    OpenApiDocs.held()
  }
