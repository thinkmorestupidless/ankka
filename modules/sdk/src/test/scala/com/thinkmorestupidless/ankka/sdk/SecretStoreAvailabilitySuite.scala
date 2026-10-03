package com.thinkmorestupidless.ankka.sdk

/**
 * An entity or a view is given no secret store: the member does not exist on its context, so a
 * handler that reaches for one does not compile.
 *
 * Each refusal is checked beside a context that does have one, written the same way, so this cannot
 * pass because the expression was mistyped.
 */
class SecretStoreAvailabilitySuite extends munit.FunSuite:

  test("an entity or a view is given no secret store") {
    val missing = "is not a member"
    assert(
      compileErrors("(??? : EventSourcedEntityContext).secrets").contains(missing),
      "an event sourced entity's context must have no secrets"
    )
    assert(
      compileErrors("(??? : KeyValueEntityContext).secrets").contains(missing),
      "a key value entity's context must have no secrets"
    )
    assert(
      compileErrors("(??? : ViewComponentContext).secrets").contains(missing),
      "a view's context must have no secrets"
    )
  }

  test("a consumer, a workflow and a timed action are given one") {
    assertEquals(compileErrors("(??? : ConsumerContext).secrets"), "")
    assertEquals(compileErrors("(??? : WorkflowContext).secrets"), "")
    assertEquals(compileErrors("(??? : TimedActionContext).secrets"), "")
  }
