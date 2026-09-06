package nakka.core.effect

import nakka.core.{CommandError, ErrorCode, Metadata}

class SimpleEffectsSuite extends munit.FunSuite:

  test("view effects distinguish update, physical delete and skip") {
    val effects = new ViewEffects[String]()
    assertEquals(effects.updateRow("row"), ViewEffect.UpdateRow("row"))
    assertEquals(effects.deleteRow(), ViewEffect.DeleteRow)
    assertEquals(effects.ignore(), ViewEffect.Ignore)
  }

  test("consumer produce defaults to empty metadata") {
    val effects = new ConsumerEffects[String]()
    assertEquals(effects.produce("payload"), ConsumerEffect.Produce("payload", Metadata.empty))
  }

  test("consumer produce carries ce-subject for per-entity ordering") {
    val effects = new ConsumerEffects[String]()
    val meta    = Metadata.empty.withSubject("counter-7")
    assertEquals(effects.produce("payload", meta), ConsumerEffect.Produce("payload", meta))
  }

  test("consumer done and ignore are distinct outcomes") {
    val effects = new ConsumerEffects[String]()
    assertEquals(effects.done(), ConsumerEffect.Done)
    assertEquals(effects.ignore(), ConsumerEffect.Ignore)
    assertNotEquals[ConsumerEffect[String], ConsumerEffect[String]](
      effects.done(),
      effects.ignore()
    )
  }

  test("timed action done completes the timer") {
    assertEquals(new TimedActionEffects().done(), TimedActionEffect.Done)
  }

  test("timed action error asks to be rescheduled") {
    val effects = new TimedActionEffects()
    assertEquals(
      effects.error("entity unreachable", ErrorCode.Unavailable),
      TimedActionEffect.Fail(CommandError("entity unreachable", ErrorCode.Unavailable))
    )
  }

  test("retryable classification drives timer backoff decisions") {
    import nakka.core.ErrorCode.*
    assert(Unavailable.retryable)
    assert(Timeout.retryable)
    assert(!BadRequest.retryable)
    assert(!NotFound.retryable)
    assert(!Internal.retryable)
  }
