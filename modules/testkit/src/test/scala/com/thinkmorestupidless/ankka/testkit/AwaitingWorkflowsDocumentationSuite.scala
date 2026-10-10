package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}

/**
 * `features/documentation/awaiting-workflows.feature`: what the published documentation must say
 * about waiting for a workflow's end, held to the pages. One test per scenario, named after it, and
 * one for the limits the pages must state beside it.
 */
class AwaitingWorkflowsDocumentationSuite extends munit.FunSuite:

  private lazy val docs: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir.resolve("docs")

  private def page(path: String): String =
    Files.readString(docs.resolve(path)).replaceAll("\\s+", " ")

  private def says(path: String, statements: String*): Unit =
    val text = page(path)
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  test("the documentation describes starting a workflow and waiting for its end") {
    says(
      "build/workflows.md",
      "## Waiting for the end",
      "Sending a command and waiting for the end is one call",
      "### Waiting for a workflow already started",
      "thenAwaitEnd(",
      "awaitEnd(",
      "then_await_end(",
      "await_end(",
      "thenAwaitEnd<",
      // Each language's sample, from tested code.
      "<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/awaiting/QuoteEndpoint.scala#start-and-await -->",
      "<!-- include: sdks/python/examples/shopping_cart/endpoint.py#start-and-await -->",
      "<!-- include: sdks/typescript/examples/shopping-cart/endpoint.ts#start-and-await -->",
      "<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/awaiting/QuoteEndpoint.scala#await-later -->",
      "<!-- include: sdks/python/examples/shopping_cart/endpoint.py#await-later -->",
      "<!-- include: sdks/typescript/examples/shopping-cart/endpoint.ts#await-later -->"
    )
    says("build/component-client.md", "awaitEnd(", "thenAwaitEnd(")
  }

  test("the documentation says what each ending answers and when to serve a wait as a stream") {
    says(
      "build/workflows.md",
      "| completed | the state it ended with |",
      "| failed | an error whose code is `WorkflowFailed`, whose details name the step that failed and the reason |",
      "| deleted | `WorkflowFailed`, whose details say it was deleted and name no step |",
      "| paused | nothing yet: a paused workflow has not ended, and the wait goes on |",
      "Every wait takes a timeout from the caller, and there is no default",
      "The command's own reply is not kept",
      "A wait is for a workflow that will end",
      "Serve a wait as server-sent events when it may outlast a connection's idle timeout",
      "<!-- include: modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/awaiting/QuoteEndpoint.scala#sse-await -->",
      "start the workflow from a consumer and wait for it from an endpoint"
    )
    says("reference/error-codes.md", "`WorkflowFailed`", "424", "`ABORTED`", "WORKFLOW_FAILED = 8")
  }

  test("the documentation says Akka offers no wait for a workflow's end") {
    says(
      "reference/akka-divergences.md",
      "## A caller can wait for a workflow's end",
      "Akka offers no wait for a workflow's end"
    )
  }

  test("the documentation states a wait's limits where limits are listed") {
    says(
      "reference/limitations.md",
      "**The gateway bounds an HTTP response at fifteen seconds.**",
      "**A module waits whole.**",
      "**A wait needs both halves at the same release.**"
    )
    says("reference/sidecar-protocol.md", "`AwaitEnd`", "`AwaitEndStream`", "`1.15`")
    says("reference/wasm-abi.md", "`await_end`")
  }
