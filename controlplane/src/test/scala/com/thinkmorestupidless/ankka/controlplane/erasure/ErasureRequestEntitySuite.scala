package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution}
import com.thinkmorestupidless.ankka.controlplane.erasure.ErasureRequestEvent.*
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.core.keyring.HandlerReport
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

import java.time.Instant

/** Feature 042, T038 (R21): every transition of an erasure request, and every refusal. */
class ErasureRequestEntitySuite extends munit.FunSuite with LogCapturing:

  private val now     = Instant.parse("2026-11-04T10:00:00Z")
  private val ada     = Attribution(Actor("ada", Some("ada@brand.test")), now)
  private val players = Attribution(Actor("ankka://brand/players"), now)
  private val owner   = Attribution(Actor("olu", Some("olu@brand.test")), now.plusSeconds(60))
  private val later   = Attribution(Actor("ankka://platform/control-plane"), now.plusSeconds(3600))

  private def kit() =
    EventSourcedTestKit.of(ErasureRequestEntity, "brand/01JD0000000000000000000000")

  private def asked(notBefore: Option[Instant] = None, by: Attribution = ada) =
    val k = kit()
    val r = k.call(ErasureRequestEntity.ask, by.metadata)(
      AskErasure("brand", "player/8c1f", notBefore, Some("aml-retention"), Some("ticket-41"))
    )
    assert(!r.isError, r.toString)
    k

  test("an ask with no date is applying at once, attributed to its asker") {
    val k = kit()
    val r = k.call(ErasureRequestEntity.ask, players.metadata)(AskErasure("brand", "player/8c1f"))
    assertEquals(
      r.events,
      Vector(Asked("brand", "player/8c1f", actor = Some(players.actor), at = Some(now)))
    )
    assertEquals(r.replyValue.state, Some(ErasureState.Applying))
    assertEquals(r.replyValue.askedBy, Some(players.actor))
    assertEquals(r.replyValue.rid, "01JD0000000000000000000000")
  }

  test("an ask with a date to come is held; one with a date passed is applying") {
    assertEquals(asked(Some(now.plusSeconds(86400))).currentState.state, Some(ErasureState.Held))
    assertEquals(asked(Some(now.minusSeconds(1))).currentState.state, Some(ErasureState.Applying))
  }

  test(
    "an ask is refused for a subject outside the rule, a long reason, a bad correlation, or twice"
  ) {
    def refused(request: AskErasure) =
      val r = kit().call(ErasureRequestEntity.ask, ada.metadata)(request)
      assert(r.isError, r.toString)
      (r.error.code, r.errorMessage)
    assert(refused(AskErasure("brand", "player 8c1f"))._2.contains("1 to 253 characters"))
    assert(
      refused(AskErasure("brand", "player/8c1f", reason = Some("x" * 501)))._2
        .contains("at most 500")
    )
    assert(
      refused(AskErasure("brand", "player/8c1f", correlation = Some("a b")))._2
        .contains("correlation")
    )
    val k = asked()
    val r = k.call(ErasureRequestEntity.ask, ada.metadata)(AskErasure("brand", "player/8c1f"))
    assertEquals(r.error.code, ErrorCode.Conflict)
  }

  test("a held request may be replaced, withdrawn or overridden; nothing else may") {
    val held = asked(Some(now.plusSeconds(86400)))
    val r    = held.call(ErasureRequestEntity.replace, ada.metadata)(
      ReplaceErasure(Some(now.plusSeconds(172800)), Some("court order"))
    )
    assertEquals(r.replyValue.notBefore, Some(now.plusSeconds(172800)))
    assertEquals(r.replyValue.state, Some(ErasureState.Held))
    assertEquals(
      held.call(ErasureRequestEntity.overrideHold, owner.metadata)(OverrideHold(" ")).isError,
      true
    )
    val o = held.call(ErasureRequestEntity.overrideHold, owner.metadata)(
      OverrideHold("the regulator asked")
    )
    assertEquals(
      o.events,
      Vector(HoldOverridden("the regulator asked", Some(owner.actor), Some(owner.at)))
    )
    assertEquals(o.replyValue.state, Some(ErasureState.Applying))
    assertEquals(
      o.replyValue.holdOverride,
      Some(HoldOverride(owner.actor, "the regulator asked", Some(owner.at)))
    )
    // Applying now: none of the three is allowed.
    assertEquals(
      held.call(ErasureRequestEntity.withdraw, ada.metadata).error.code,
      ErrorCode.Conflict
    )
    assertEquals(
      held.call(ErasureRequestEntity.replace, ada.metadata)(ReplaceErasure()).error.code,
      ErrorCode.Conflict
    )

    val other = asked(Some(now.plusSeconds(86400)))
    val w     = other.call(ErasureRequestEntity.withdraw, ada.metadata)
    assertEquals(w.replyValue.state, Some(ErasureState.Withdrawn))
    assertEquals(
      other.call(ErasureRequestEntity.withdraw, ada.metadata).error.code,
      ErrorCode.Conflict
    )
  }

  test("a hold lapses only once its date has passed") {
    val held = asked(Some(now.plusSeconds(1800)))
    assertEquals(held.call(ErasureRequestEntity.lapse, ada.metadata).error.code, ErrorCode.Conflict)
    val l = held.call(ErasureRequestEntity.lapse, later.metadata)
    assertEquals(l.replyValue.state, Some(ErasureState.Applying))
    assertEquals(l.replyValue.lapsedAt, Some(later.at))
  }

  test("applying: the log first, then the key; completions once per service; final last") {
    val k = asked()
    assertEquals(
      k.call(ErasureRequestEntity.keyDestroyed, later.metadata)(now).error.code,
      ErrorCode.Conflict
    )
    assertEquals(k.call(ErasureRequestEntity.logWritten, later.metadata)(12L).events.size, 1)
    assertEquals(k.call(ErasureRequestEntity.logWritten, later.metadata)(12L).events, Vector.empty)
    assertEquals(
      k.call(ErasureRequestEntity.logWritten, later.metadata)(13L).error.code,
      ErrorCode.Conflict
    )
    val completion = ServiceCompletion(Some(later.at), 3, 1, 0, Some(HandlerReport(2, "immediate")))
    assertEquals(
      k.call(ErasureRequestEntity.recordCompletion, later.metadata)(
        RecordCompletion("players", completion)
      ).error
        .code,
      ErrorCode.Conflict
    )
    val d = k.call(ErasureRequestEntity.keyDestroyed, later.metadata)(later.at)
    assertEquals(d.replyValue.state, Some(ErasureState.Applied))
    assertEquals(d.replyValue.destroyedAt, Some(later.at))
    val c = k.call(ErasureRequestEntity.recordCompletion, later.metadata)(
      RecordCompletion("players", completion)
    )
    assertEquals(c.replyValue.completions, Map("players" -> completion))
    assertEquals(
      k.call(ErasureRequestEntity.recordCompletion, later.metadata)(
        RecordCompletion("players", completion.copy(rowsRedacted = 9))
      ).events,
      Vector.empty
    )
    val h = k.call(ErasureRequestEntity.recordHandlerRun, later.metadata)(
      HandlerRun("kyc", HandlerReport.failed("no bucket"))
    )
    assertEquals(h.replyValue.handlerRuns.map(_.service), Vector("kyc"))
    val f = k.call(ErasureRequestEntity.objectsFinal, later.metadata)(later.at.plusSeconds(86400))
    assertEquals(f.replyValue.objectsFinalAt, Some(later.at.plusSeconds(86400)))
    assertEquals(
      k.call(ErasureRequestEntity.finalise, later.metadata).replyValue.finalAt,
      Some(later.at)
    )
    assertEquals(k.call(ErasureRequestEntity.finalise, later.metadata).events, Vector.empty)
    // Applied is final.
    assertEquals(k.call(ErasureRequestEntity.withdraw, ada.metadata).error.code, ErrorCode.Conflict)
    assertEquals(
      k.call(ErasureRequestEntity.overrideHold, owner.metadata)(OverrideHold("again")).error.code,
      ErrorCode.Conflict
    )
  }

  test("a request that does not exist is not found") {
    assertEquals(
      kit().call(ErasureRequestEntity.withdraw, ada.metadata).error.code,
      ErrorCode.NotFound
    )
    assertEquals(kit().call(ErasureRequestEntity.get).error.code, ErrorCode.NotFound)
  }

  test("a replacement with no not-before date, or one passed, is refused and the request held") {
    val held = asked(Some(now.plusSeconds(86400)))
    val none = held.call(ErasureRequestEntity.replace, ada.metadata)(ReplaceErasure())
    assertEquals(none.error.code, ErrorCode.BadRequest)
    assert(none.errorMessage.contains("needs a not-before date"), none.errorMessage)
    val past = held.call(ErasureRequestEntity.replace, ada.metadata)(
      ReplaceErasure(Some(now.minusSeconds(1)))
    )
    assertEquals(past.error.code, ErrorCode.BadRequest)
    assert(past.errorMessage.contains("only an owner releases a hold"), past.errorMessage)
    assertEquals(held.currentState.state, Some(ErasureState.Held))
    assertEquals(held.currentState.notBefore, Some(now.plusSeconds(86400)))
  }

  private def appliedKit() =
    val k = asked()
    k.call(ErasureRequestEntity.logWritten, later.metadata)(12L): Unit
    k.call(ErasureRequestEntity.keyDestroyed, later.metadata)(later.at): Unit
    k

  test("a handler failing the same way again is one event, and a new failure is another") {
    val k                                                = appliedKit()
    def run(service: String, error: String, at: Instant) =
      k.call(ErasureRequestEntity.recordHandlerRun, later.metadata)(
        HandlerRun(service, HandlerReport.failed(error), Some(at))
      ).events
        .size
    assertEquals(run("kyc", "no bucket", later.at), 1)
    assertEquals(run("kyc", "no bucket", later.at.plusSeconds(900)), 0)
    assertEquals(run("kyc", "no bucket", later.at.plusSeconds(1800)), 0)
    assertEquals(run("kyc", "the store refused", later.at.plusSeconds(2700)), 1)
    // Another service's same failure is its own news.
    assertEquals(run("players", "no bucket", later.at), 1)
    assertEquals(k.currentState.handlerRuns.size, 3)
  }

  test("quiet runs are kept per service, so one's quiet run hides no other's") {
    val k = appliedKit()
    val q = k.call(ErasureRequestEntity.ranQuietly, later.metadata)(
      QuietRun("players", later.at.plusSeconds(900))
    )
    assertEquals(q.replyValue.quietRuns, Map("players" -> later.at.plusSeconds(900)))
    // An earlier run of another service is still news.
    val other = k.call(ErasureRequestEntity.ranQuietly, later.metadata)(
      QuietRun("kyc", later.at.plusSeconds(300))
    )
    assertEquals(other.events.size, 1)
    assertEquals(
      k.call(ErasureRequestEntity.ranQuietly, later.metadata)(
        QuietRun("players", later.at.plusSeconds(600))
      ).events,
      Vector.empty
    )
    assertEquals(k.currentState.lastRunAt, Some(later.at.plusSeconds(900)))
  }

  test("a quiet run is recorded only when it changes what the service's last run did") {
    val k                  = appliedKit()
    def quiet(at: Instant) =
      k.call(ErasureRequestEntity.ranQuietly, later.metadata)(QuietRun("players", at)).events.size
    def erased(at: Instant) =
      k.call(ErasureRequestEntity.recordHandlerRun, later.metadata)(
        HandlerRun("players", HandlerReport(2, "immediate"), Some(at))
      ).events
        .size
    k.call(ErasureRequestEntity.recordCompletion, later.metadata)(
      RecordCompletion(
        "players",
        ServiceCompletion(Some(later.at), handler = Some(HandlerReport(0, "immediate")))
      )
    ): Unit
    // After a quiet completion, every quiet re-run of the window is no news.
    assertEquals((1 to 5).map(i => quiet(later.at.plusSeconds(900L * i))).sum, 0)
    // After one that erased, the next quiet one is; the ones after it are not.
    assertEquals(erased(later.at.plusSeconds(6000)), 1)
    assertEquals(quiet(later.at.plusSeconds(6900)), 1)
    assertEquals(quiet(later.at.plusSeconds(7800)), 0)
    assertEquals(k.currentState.quietRuns, Map("players" -> later.at.plusSeconds(6900)))
  }

  test("a failure again after a quiet run is audited again") {
    val k                   = appliedKit()
    def failed(at: Instant) =
      k.call(ErasureRequestEntity.recordHandlerRun, later.metadata)(
        HandlerRun("kyc", HandlerReport.failed("no bucket"), Some(at))
      ).events
        .size
    assertEquals(failed(later.at.plusSeconds(900)), 1)
    assertEquals(
      k.call(ErasureRequestEntity.ranQuietly, later.metadata)(
        QuietRun("kyc", later.at.plusSeconds(1800))
      ).events
        .size,
      1
    )
    assertEquals(failed(later.at.plusSeconds(2700)), 1, "the same failure after a quiet run")
    assertEquals(
      k.currentState.handlerRuns.map(_.at),
      Vector(Some(later.at.plusSeconds(900)), Some(later.at.plusSeconds(2700)))
    )
  }
