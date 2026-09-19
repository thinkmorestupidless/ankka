package com.thinkmorestupidless.ankka.core.effect

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Metadata}

import scala.concurrent.duration.*

/** A minimal domain so the effect algebra can be exercised with no runtime at all. */
private final case class Counter(value: Int, closed: Boolean = false)

private enum CounterEvent:
  case Increased(by: Int)
  case Closed

class EventSourcedEffectSuite extends munit.FunSuite:
  import CounterEvent.*

  private val effects = new EventSourcedEffects[Counter, CounterEvent]()

  private def applyEvent(state: Counter, event: CounterEvent): Counter = event match
    case Increased(by) => state.copy(value = state.value + by)
    case Closed        => state.copy(closed = true)

  private def run[R](
      effect: EventSourcedEffect[Counter, CounterEvent, R],
      from: Counter = Counter(0)
  ) = EventSourcedEffect.materialise(effect, from, applyEvent)

  test("persist folds events in order and replies from the resulting state") {
    val result = run(effects.persist(Increased(2), Increased(3)).thenReply(_.value))
    assertEquals(result.events, Vector(Increased(2), Increased(3)))
    assertEquals(result.newState, Counter(5))
    assertEquals(result.reply, Right(Some(5)))
    assert(result.persisted)
  }

  test("thenReplyState replies with the post-event state, not the pre-event state") {
    val result = run(effects.persist(Increased(7)).thenReplyState)
    assertEquals(result.reply, Right(Some(Counter(7))))
  }

  test("reply persists nothing and leaves state untouched") {
    val result = run(effects.reply("ok"), from = Counter(9))
    assertEquals(result.events, Vector.empty)
    assertEquals(result.newState, Counter(9))
    assertEquals(result.reply, Right(Some("ok")))
    assert(!result.persisted)
  }

  test("error yields no events, no state change, and a typed failure") {
    val result = run(effects.error("counter is closed", ErrorCode.Conflict), Counter(4, true))
    assertEquals(result.events, Vector.empty)
    assertEquals(result.newState, Counter(4, true))
    assertEquals(result.reply, Left(CommandError("counter is closed", ErrorCode.Conflict)))
    assertEquals(result.retention, None)
  }

  test("error defaults to BadRequest, since it models a rule violation") {
    val result = run(effects.error[Int]("quantity must be greater than zero"))
    assertEquals(result.reply.left.map(_.code), Left(ErrorCode.BadRequest))
  }

  test("noReply persists nothing and returns no payload") {
    val result = run(effects.noReply[Int])
    assertEquals(result.reply, Right(None))
  }

  test("persist().thenNoReply still persists") {
    val result = run(effects.persist(Increased(1)).thenNoReply[Int])
    assertEquals(result.events, Vector(Increased(1)))
    assertEquals(result.newState, Counter(1))
    assertEquals(result.reply, Right(None))
  }

  test("deleteEntity after a final event carries both the event and the retention") {
    val result = run(effects.persist(Closed).deleteEntity().thenReply(_ => "done"))
    assertEquals(result.events, Vector(Closed))
    assertEquals(result.newState, Counter(0, closed = true))
    assertEquals(result.retention, Some(Retention.DeleteNow))
  }

  test("deleteEntity without an event records retention only") {
    val result = run(effects.deleteEntity().thenReply(_ => "done"))
    assertEquals(result.events, Vector.empty)
    assertEquals(result.retention, Some(Retention.DeleteNow))
  }

  test("expireAfter records a TTL rather than an immediate delete") {
    val result = run(effects.persist(Increased(1)).expireAfter(30.days).thenReplyState)
    assertEquals(result.retention, Some(Retention.ExpireAfter(30.days)))
  }

  test("the last retention call wins") {
    val result = run(
      effects.persist(Increased(1)).expireAfter(1.day).deleteEntity().thenReplyState
    )
    assertEquals(result.retention, Some(Retention.DeleteNow))
  }

  test("reply metadata is carried on the outcome") {
    val meta = Metadata.of("ce-subject" -> "counter-1")
    effects.persist(Increased(1)).thenReply(_.value, meta).outcome match
      case Outcome.Reply(_, m) => assertEquals(m, meta)
      case other               => fail(s"expected a Reply outcome, got $other")
  }

  test("read-only effects are statically incapable of persisting") {
    // The type, not a runtime check, is what rules this out.
    val readOnly: ReadOnlyEffect[Counter, CounterEvent, String] = effects.reply("x")
    assertEquals(readOnly.events, Vector.empty)
    assertEquals(readOnly.retention, None)
  }

  test("building an effect runs no domain code") {
    // If the builder were eager, this would throw at construction rather than at
    // materialisation — the property the whole declarative model rests on.
    var evaluated = false
    val effect = effects.persist(Increased(1)).thenReply { _ =>
      evaluated = true; "computed"
    }
    assert(!evaluated, "reply function must not run while the effect is being built")
    assertEquals(run(effect).reply, Right(Some("computed")))
    assert(evaluated)
  }
