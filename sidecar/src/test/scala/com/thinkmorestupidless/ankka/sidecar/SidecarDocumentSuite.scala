package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.core.ComponentRegistry

/**
 * A process's or a module's service has no OpenAPI document (feature 067, FR-036): the server the
 * sidecar serves its routes from gives none, so neither `ankka openapi --local` nor the local
 * console is handed a document whose every route is incomplete.
 */
class SidecarDocumentSuite extends munit.FunSuite:

  test("the sidecar's server gives no OpenAPI document, on a port or not") {
    for port <- Seq(None, Some(0)) do
      assertEquals(
        Main.httpServer(Vector.empty, port).document("openapi", Map.empty, ComponentRegistry.empty),
        None,
        s"port $port"
      )
  }
