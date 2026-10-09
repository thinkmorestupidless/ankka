package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.application.*
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

import java.time.{Instant, LocalDate}

/**
 * One erasure request's life, with no runtime: asked, released, logged, destroyed, completed by
 * each service, applied, final, settled — every step attributed and timed, every repeat recording
 * nothing.
 */
class ErasureEntitySuite extends munit.FunSuite with LogCapturing:

  private val at    = Instant.parse("2026-10-09T10:00:00Z")
  private val alice = ErasureWho("member", "alice", Some("alice@example.test"))

  private def kit = EventSourcedTestKit.of(ErasureEntity, "brand/e1")

  private def ask(
      kit: EventSourcedTestKit[ErasureEntity, Option[ErasureRequest], ErasureEvent],
      request: RequestErasure
  ) =
    kit.call(ErasureEntity.ask)(AskErasure(request, "e1", "brand", alice, at))

  private def step(minutes: Long, sequence: Option[Long] = None) =
    Step(at.plusSeconds(minutes * 60), sequence)

  private def completion(service: String, handler: Option[String] = None) =
    ErasureServiceCompletion(service, at.plusSeconds(600), handler)

  test("a request with no date is applying at once, attributed to who asked and when") {
    val k     = kit
    val asked = ask(k, RequestErasure("player/8c1f"))
    assertEquals(asked.replyValue.state, ErasureState.Applying)
    assertEquals(asked.replyValue.askedBy, alice)
    assertEquals(asked.replyValue.askedAt, at)
    assertEquals(asked.events.size, 1)
  }

  test("a request with a date is held, and released by the sweeper's step") {
    val k = kit
    val held = ask(
      k,
      RequestErasure(
        "player/8c1f",
        notBefore = Some(LocalDate.of(2026, 11, 1)),
        reason = Some("aml")
      )
    )
    assertEquals(held.replyValue.state, ErasureState.Held)
    assertEquals(k.call(ErasureEntity.release)(step(1)).replyValue.state, ErasureState.Applying)
    assertEquals(k.call(ErasureEntity.release)(step(2)).events, Vector.empty, "released once")
  }

  test("asking again answers the request already there and records nothing") {
    val k = kit
    ask(k, RequestErasure("player/8c1f"))
    val again = ask(k, RequestErasure("player/8c1f"))
    assertEquals(again.events, Vector.empty)
    assertEquals(again.replyValue.id, "e1")
  }

  test(
    "applied: the log written, the key destroyed, each service completed, then final, then settled"
  ) {
    val k = kit
    ask(k, RequestErasure("player/8c1f"))
    k.call(ErasureEntity.logWritten)(step(1, Some(7)))
    k.call(ErasureEntity.keyDestroyed)(step(2))
    Seq("players", "wallet", "engagement").foreach(s =>
      k.call(ErasureEntity.completed)(completion(s))
    )
    k.call(ErasureEntity.applied)(step(3))
    val finalised = k.call(ErasureEntity.finalised)(step(4)).replyValue
    assertEquals(finalised.state, ErasureState.Final)
    assertEquals(finalised.sequence, Some(7L))
    assertEquals(finalised.keyDestroyedAt, Some(at.plusSeconds(120)))
    assertEquals(finalised.completions.map(_.service), Vector("players", "wallet", "engagement"))
    assertEquals(finalised.appliedAt, Some(at.plusSeconds(180)))
    assertEquals(finalised.finalAt, Some(at.plusSeconds(240)))
    assertEquals(k.call(ErasureEntity.settled)(step(5)).replyValue.state, ErasureState.Settled)
    // Every step once: repeats are what the sweeper does every pass.
    assertEquals(k.call(ErasureEntity.logWritten)(step(6, Some(8))).events, Vector.empty)
    assertEquals(k.call(ErasureEntity.keyDestroyed)(step(6)).events, Vector.empty)
    assertEquals(k.call(ErasureEntity.applied)(step(6)).events, Vector.empty)
  }

  test("a service's completion is recorded only when it says something new") {
    val k = kit
    ask(k, RequestErasure("player/8c1f"))
    assertEquals(
      k.call(ErasureEntity.completed)(completion("players", Some("noted"))).events.size,
      1
    )
    assertEquals(
      k.call(ErasureEntity.completed)(completion("players", Some("noted"))).events,
      Vector.empty
    )
    assertEquals(
      k.call(ErasureEntity.completed)(completion("players", Some("nothing left"))).events.size,
      1
    )
  }

  test("a failure is recorded once with its reason, and a retry applies again") {
    val k = kit
    ask(k, RequestErasure("player/8c1f"))
    val failed = k.call(ErasureEntity.failed)(Step(at, reason = Some("keyring unreachable")))
    assertEquals(failed.replyValue.state, ErasureState.Failed)
    assertEquals(failed.replyValue.failure, Some("keyring unreachable"))
    assertEquals(k.call(ErasureEntity.retry)(step(1)).replyValue.state, ErasureState.Applying)
  }

  test("only a held request is withdrawn or overridden") {
    val k = kit
    ask(k, RequestErasure("player/8c1f"))
    val refused = k.call(ErasureEntity.withdraw)(Withdraw(alice, at))
    assert(refused.isError, refused.toString)
    assert(k.call(ErasureEntity.overrideHold)(Override(alice, "regulator", at)).isError)
  }

  test("a certificate's form has no field that can carry a personal value") {
    val k = kit
    ask(k, RequestErasure("player/8c1f", correlationId = Some("ticket-41")))
    Seq("players").foreach(s => k.call(ErasureEntity.completed)(completion(s, Some("noted"))))
    val request = k.call(ErasureEntity.get).replyValue
    val json    = writeToString(ErasureCertificate(request, at, "statement"))
    // Every key the form can hold: ids, the pseudonymous subject, who asked, times, counts, states.
    val keys = "\"([A-Za-z]+)\":".r.findAllMatchIn(json).map(_.group(1)).toSet
    val allowed = Set(
      "request",
      "id",
      "projectId",
      "subject",
      "state",
      "askedBy",
      "kind",
      "display",
      "askedAt",
      "correlationId",
      "completions",
      "service",
      "completedAt",
      "handler",
      "issuedAt",
      "statement",
      "project"
    )
    assert(keys.subsetOf(allowed), (keys -- allowed).toString)
  }
