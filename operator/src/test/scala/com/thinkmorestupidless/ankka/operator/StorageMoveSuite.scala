package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{MoveStatus, ObjectStorageMoveRequest}
import com.thinkmorestupidless.ankka.operator.StorageMove.*

import java.time.Instant

/**
 * A move of a service's bucket from Garage to Google Cloud Storage (feature 039,
 * contracts/operator.md "The move"), one transition per pass, every row of the table.
 */
class StorageMoveSuite extends munit.FunSuite:

  private val t0      = Instant.parse("2026-10-09T10:00:00Z")
  private val request = ObjectStorageMoveRequest(generation = 1, writePauseBound = "10m")

  private def observed(
      at: Instant = t0,
      requests: Requests = Requests.Ready("t-casino-kyc-3f9a1c2e"),
      job: JobOutcome = JobOutcome.Absent
  ) = MoveObservation(requests, job, at)

  private def state(
      s: State,
      generation: Int = 1,
      pauseStartedAt: Option[Instant] = None
  ) =
    MoveStatus(
      generation = generation,
      state = s.toString,
      startedAt = t0.toString,
      pauseStartedAt = pauseStartedAt.map(_.toString),
      pauseBound = pauseStartedAt.map(_ => "10m")
    )

  private val done = MoveReport(counted = 3, copied = 1, verified = 3)

  test("no request is no move, and nothing is done") {
    assertEquals(StorageMove.next(None, None, observed()), Step(None, Vector.empty))
  }

  test(
    "a request the status has not seen starts a move, asking for the bucket in Google Cloud Storage"
  ) {
    val step = StorageMove.next(Some(request), None, observed(requests = Requests.Waiting(None)))
    assertEquals(step.status.map(_.state), Some("Requested"))
    assertEquals(step.status.map(_.startedAt), Some(t0.toString))
    assertEquals(step.actions, Vector(Act.AskForBucket))
  }

  test("once the bucket and its credential are ready, the copy starts") {
    val step = StorageMove.next(Some(request), Some(state(State.Requested)), observed())
    assertEquals(step.status.map(_.state), Some("Copying"))
    assertEquals(step.actions, Vector(Act.AskForBucket, Act.Copy("t-casino-kyc-3f9a1c2e")))
  }

  test("a bucket the cloud provider cannot make fails the move with its words") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Requested)),
      observed(requests = Requests.Failed("the name is held by someone else"))
    )
    assertEquals(step.status.map(_.state), Some("Failed"))
    assertEquals(step.status.flatMap(_.detail), Some("the name is held by someone else"))
    assertEquals(step.actions, Vector.empty)
  }

  test("while the copy runs, it is asked for again and nothing else changes") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Copying)),
      observed(job = JobOutcome.Running)
    )
    assertEquals(step.status.map(_.state), Some("Copying"))
    assertEquals(step.actions, Vector(Act.AskForBucket, Act.Copy("t-casino-kyc-3f9a1c2e")))
  }

  test("a copy that succeeded pauses the service's writes and records when and for how long") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Copying)),
      observed(job = JobOutcome.Succeeded(done))
    )
    val s = step.status.get
    assertEquals(s.state, "Pausing")
    assertEquals(s.pauseStartedAt, Some(t0.toString))
    assertEquals(s.pauseBound, Some("10m"))
    assertEquals((s.counted, s.copied), (Some(3), Some(1)))
    assertEquals(step.actions, Vector(Act.AskForBucket, Act.PauseWrites))
  }

  test("a copy that failed fails the move, naming the object, and the service still writes") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Copying)),
      observed(job =
        JobOutcome.Failed(MoveReport(failedObject = Some("big.tiff"), reason = Some("over 5 GiB")))
      )
    )
    val s = step.status.get
    assertEquals(s.state, "Failed")
    assertEquals(s.failedObject, Some("big.tiff"))
    assert(s.detail.exists(_.contains("over 5 GiB")), s.toString)
    assert(!step.actions.contains(Act.ResumeWrites), "nothing was paused")
  }

  test("the next pass after the pause verifies, with what remains of the bound as its deadline") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Pausing, pauseStartedAt = Some(t0))),
      observed(at = t0.plusSeconds(60))
    )
    assertEquals(step.status.map(_.state), Some("Verifying"))
    assertEquals(
      step.actions,
      Vector(
        Act.AskForBucket,
        Act.PauseWrites,
        Act.Verify("t-casino-kyc-3f9a1c2e", deadlineSeconds = 540)
      )
    )
  }

  test("a verify that succeeded switches the service to its bucket in Google Cloud Storage") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Verifying, pauseStartedAt = Some(t0))),
      observed(at = t0.plusSeconds(120), job = JobOutcome.Succeeded(done))
    )
    assertEquals(step.status.map(_.state), Some("Switched"))
    assertEquals(step.status.flatMap(_.verified), Some(3))
    assertEquals(step.actions, Vector(Act.AskForBucket, Act.Switch("t-casino-kyc-3f9a1c2e")))
  }

  test(
    "a verify that found an object differ fails the move, naming it, and gives the writes back"
  ) {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Verifying, pauseStartedAt = Some(t0))),
      observed(
        at = t0.plusSeconds(120),
        job = JobOutcome.Failed(
          MoveReport(failedObject = Some("passport.pdf"), reason = Some("differs"))
        )
      )
    )
    assertEquals(step.status.map(_.state), Some("Failed"))
    assertEquals(step.status.flatMap(_.failedObject), Some("passport.pdf"))
    assertEquals(step.actions, Vector(Act.ResumeWrites))
  }

  test(
    "a write pause that reaches its bound fails the move, saying so, and gives the writes back"
  ) {
    for from <- Vector(State.Pausing, State.Verifying) do
      val step = StorageMove.next(
        Some(request),
        Some(state(from, pauseStartedAt = Some(t0))),
        observed(at = t0.plusSeconds(601), job = JobOutcome.Running)
      )
      assertEquals(step.status.map(_.state), Some("Failed"), from.toString)
      assert(step.status.flatMap(_.detail).exists(_.contains("reached its bound")), from.toString)
      assertEquals(step.actions, Vector(Act.ResumeWrites), from.toString)
  }

  test("a switched move stays switched, and keeps the service on its new bucket") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Switched)),
      observed(at = t0.plusSeconds(3600))
    )
    assertEquals(step.status.map(_.state), Some("Switched"))
    assertEquals(step.actions, Vector(Act.AskForBucket, Act.Switch("t-casino-kyc-3f9a1c2e")))
  }

  test("a failed move stays failed until it is asked for again, and then starts afresh") {
    val failed = state(State.Failed)
    assertEquals(
      StorageMove.next(Some(request), Some(failed), observed()).status.map(_.state),
      Some("Failed")
    )
    val again = StorageMove.next(
      Some(request.copy(generation = 2)),
      Some(failed),
      observed(at = t0.plusSeconds(10))
    )
    assertEquals(again.status.map(s => (s.state, s.generation)), Some(("Requested", 2)))
    assertEquals(again.status.map(_.startedAt), Some(t0.plusSeconds(10).toString))
  }

  test("the mover's termination message is read as its report, and anything else as none") {
    val line =
      """{"mode":"verify","counted":10000,"copied":17,"verified":9999,"failed":"passport.pdf","reason":"differs","seconds":412}"""
    assertEquals(
      StorageMove.report(line),
      Some(MoveReport(10000, 17, 9999, Some("passport.pdf"), Some("differs")))
    )
    assertEquals(
      StorageMove.report(
        """{"mode":"copy","counted":3,"copied":3,"verified":0,"failed":null,"reason":null,"seconds":1}"""
      ),
      Some(MoveReport(3, 3, 0, None, None))
    )
    // FallbackToLogsOnError: a mover that died before reporting leaves its log's tail instead.
    assertEquals(StorageMove.report("Exception in thread main ..."), None)
  }

  test("a bound is a whole number of seconds, minutes or hours") {
    assertEquals(StorageMove.seconds("90s"), Some(90L))
    assertEquals(StorageMove.seconds("10m"), Some(600L))
    assertEquals(StorageMove.seconds("2h"), Some(7200L))
    assertEquals(StorageMove.seconds("ten"), None)
  }

  test("the deadline handed to a verify is never below one second") {
    val step = StorageMove.next(
      Some(request),
      Some(state(State.Pausing, pauseStartedAt = Some(t0))),
      observed(at = t0.plusSeconds(600))
    )
    assert(step.actions.contains(Act.Verify("t-casino-kyc-3f9a1c2e", 1)), step.toString)
  }
