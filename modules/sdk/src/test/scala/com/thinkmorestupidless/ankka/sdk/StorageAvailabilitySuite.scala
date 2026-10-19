package com.thinkmorestupidless.ankka.sdk

/**
 * An entity or a view is given no storage client (feature 055): the member does not exist on its
 * context, so a handler that reaches for one does not compile. Every refusal sits beside a context
 * that does have one, written the same way, so this cannot pass because an expression was mistyped.
 *
 * The scenario "an entity and a view have no storage client" of
 * `features/storage-client/contexts.feature` is this suite's. An endpoint's `clients.storage` and
 * an agent's context are in modules this one cannot see; `StorageClientFeatures` (testkit) keeps
 * through both.
 */
class StorageAvailabilitySuite extends munit.FunSuite:

  private val missing = "is not a member"

  test("an entity and a view have no storage client") {
    Vector(
      "an event sourced entity" -> compileErrors("(??? : EventSourcedEntityContext).storage"),
      "a key value entity"      -> compileErrors("(??? : KeyValueEntityContext).storage"),
      "a command's context"     -> compileErrors("(??? : CommandContext).storage"),
      "a view"                  -> compileErrors("(??? : ViewComponentContext).storage"),
      "a change's context"      -> compileErrors("(??? : ChangeContext).storage")
    ).foreach { (who, errors) =>
      assert(
        errors.contains(missing) && errors.contains("storage"),
        s"$who must have no storage: '$errors'"
      )
    }
  }

  test("a workflow step, a consumer and a timed action are given one") {
    assertEquals(compileErrors("(??? : WorkflowContext).storage.default"), "")
    assertEquals(compileErrors("(??? : ConsumerContext).storage.bucket(\"photos\")"), "")
    assertEquals(compileErrors("(??? : TimedActionContext).storage.buckets"), "")
  }
