package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{MoveStatus, ObjectStorageMoveRequest}

import java.time.{Duration, Instant}
import scala.util.Try

/**
 * A move of one service's bucket from Garage to Google Cloud Storage (feature 039), as the operator
 * drives it, one transition per reconcile pass, with its state kept in the service's status.
 *
 * Pure: `next` reads the request, the state the last pass wrote and what this pass observed, and
 * returns the state to write and what to do. Rendering turns the steps into actions. The state is
 * the status's because the facts the operator would otherwise derive — whether a Job its
 * time-to-live has removed had succeeded — do not last.
 *
 * ```text
 * Requested ─▶ Copying ─▶ Pausing ─▶ Verifying ─▶ Switched
 *                 │          │           │
 *                 └──────────┴───────────┴──▶ Failed  (writes given back once paused)
 * ```
 */
/** The mover's two runs, and the argument each is started with (contracts/mover.md). */
enum MovePhase(val mode: String):
  case Copy   extends MovePhase("copy")
  case Verify extends MovePhase("verify")

object StorageMove:

  /** Where a move is. Written to `status.objectStorage.move.state`, and declared in the schema. */
  enum State:
    case Requested, Copying, Pausing, Verifying, Switched, Failed

  /** The names the schema's enum must hold, exactly. */
  val States: Set[String] = State.values.map(_.toString).toSet

  /** What the cloud provider has answered about the service's bucket in Google Cloud Storage. */
  enum Requests:
    /** Not every request is answered yet; the detail says which, when one says why. */
    case Waiting(detail: Option[String])

    /** The bucket and its credential are made; `bucket` is its name as the provider reported it. */
    case Ready(bucket: String)

    case Failed(detail: String)

  /** What a run of the mover reported in its termination message (contracts/mover.md). */
  final case class MoveReport(
      counted: Int = 0,
      copied: Int = 0,
      verified: Int = 0,
      failedObject: Option[String] = None,
      reason: Option[String] = None
  )

  /** The mover's Job for the phase in hand. */
  enum JobOutcome:
    case Absent, Running
    case Succeeded(report: MoveReport)
    case Failed(report: MoveReport)

  final case class MoveObservation(requests: Requests, job: JobOutcome, now: Instant)

  /** What a pass does about a move; rendering turns each into actions. */
  enum Act:
    /** Keep the bucket and credential requests in Google Cloud Storage asked for. */
    case AskForBucket

    /** Run (or keep) the copy Job into `bucket`. */
    case Copy(bucket: String)

    /** Take write from the service's key in Garage. */
    case PauseWrites

    /** Run (or keep) the verify Job, which Kubernetes stops after `deadlineSeconds`. */
    case Verify(bucket: String, deadlineSeconds: Long)

    /** Give the service's key in Garage its writes back. */
    case ResumeWrites

    /** Give the service the variables of its bucket in Google Cloud Storage. */
    case Switch(bucket: String)

  final case class Step(status: Option[MoveStatus], actions: Vector[Act])

  private val json = new com.fasterxml.jackson.databind.ObjectMapper()

  /**
   * The mover's report, from the last line of its termination message, or `None` when the message
   * is not one: a mover that died before reporting leaves the tail of its log instead.
   */
  def report(message: String): Option[MoveReport] =
    val last = message.trim.linesIterator.toVector.lastOption.getOrElse("")
    Try(json.readTree(last)).toOption.filter(_.has("mode")).map { node =>
      def text(field: String) =
        Option(node.get(field)).filterNot(_.isNull).map(_.asText()).filter(_.nonEmpty)
      MoveReport(
        counted = node.path("counted").asInt(0),
        copied = node.path("copied").asInt(0),
        verified = node.path("verified").asInt(0),
        failedObject = text("failed"),
        reason = text("reason")
      )
    }

  /** A bound written as a whole number of seconds, minutes or hours: `90s`, `10m`, `2h`. */
  def seconds(bound: String): Option[Long] =
    val unit = bound.lastOption
    Try(bound.dropRight(1).toLong).toOption.filter(_ > 0).flatMap { n =>
      unit match
        case Some('s') => Some(n)
        case Some('m') => Some(n * 60)
        case Some('h') => Some(n * 3600)
        case _         => None
    }

  def next(
      request: Option[ObjectStorageMoveRequest],
      status: Option[MoveStatus],
      observed: MoveObservation
  ): Step =
    request match
      case None => Step(status, Vector.empty)
      case Some(asked) if status.forall(_.generation < asked.generation) =>
        // A request this status has not seen: a first move, or one asked for again after a failure.
        Step(
          Some(
            MoveStatus(
              generation = asked.generation,
              state = State.Requested.toString,
              startedAt = observed.now.toString
            )
          ),
          Vector(Act.AskForBucket)
        )
      case Some(asked) => advance(asked, status.get, observed)

  private def advance(asked: ObjectStorageMoveRequest, s: MoveStatus, o: MoveObservation): Step =
    def to(state: State, update: MoveStatus => MoveStatus = identity) =
      Some(update(s.copy(state = state.toString)))
    def failed(detail: String, report: Option[MoveReport] = None) =
      to(
        State.Failed,
        m => report.fold(m)(r => withReport(m, r)).copy(detail = Some(detail))
      )
    val bound   = s.pauseBound.orElse(Some(asked.writePauseBound)).flatMap(seconds).getOrElse(600L)
    val elapsed = s.pauseStartedAt.map(at => Duration.between(Instant.parse(at), o.now).getSeconds)
    val bucket = o.requests match
      case Requests.Ready(name) => Some(name)
      case _                    => None
    State.valueOf(s.state) match
      case State.Requested =>
        o.requests match
          case Requests.Ready(name) =>
            Step(to(State.Copying), Vector(Act.AskForBucket, Act.Copy(name)))
          case Requests.Failed(detail) => Step(failed(detail), Vector.empty)
          case Requests.Waiting(_)     => Step(Some(s), Vector(Act.AskForBucket))

      case State.Copying =>
        (o.job, bucket) match
          case (JobOutcome.Succeeded(report), _) =>
            Step(
              to(
                State.Pausing,
                m =>
                  withReport(m, report).copy(
                    pauseStartedAt = Some(o.now.toString),
                    pauseBound = Some(asked.writePauseBound)
                  )
              ),
              Vector(Act.AskForBucket, Act.PauseWrites)
            )
          case (JobOutcome.Failed(report), _) =>
            Step(failed(describe("the copy", report), Some(report)), Vector.empty)
          case (_, Some(name)) => Step(Some(s), Vector(Act.AskForBucket, Act.Copy(name)))
          case (_, None)       => Step(Some(s), Vector(Act.AskForBucket))

      case State.Pausing | State.Verifying if elapsed.exists(_ > bound) =>
        Step(
          failed(s"the write pause reached its bound of ${asked.writePauseBound}"),
          Vector(Act.ResumeWrites)
        )

      case State.Pausing =>
        val remaining = math.max(1L, bound - elapsed.getOrElse(0L))
        bucket match
          case Some(name) =>
            Step(
              to(State.Verifying),
              Vector(Act.AskForBucket, Act.PauseWrites, Act.Verify(name, remaining))
            )
          case None => Step(Some(s), Vector(Act.AskForBucket, Act.PauseWrites))

      case State.Verifying =>
        (o.job, bucket) match
          case (JobOutcome.Succeeded(report), Some(name)) =>
            Step(
              to(State.Switched, withReport(_, report)),
              Vector(Act.AskForBucket, Act.Switch(name))
            )
          case (JobOutcome.Failed(report), _) =>
            Step(failed(describe("the check", report), Some(report)), Vector(Act.ResumeWrites))
          case (_, Some(name)) =>
            val remaining = math.max(1L, bound - elapsed.getOrElse(0L))
            Step(
              Some(s),
              Vector(Act.AskForBucket, Act.PauseWrites, Act.Verify(name, remaining))
            )
          case (_, None) => Step(Some(s), Vector(Act.AskForBucket, Act.PauseWrites))

      case State.Switched =>
        Step(Some(s), Vector(Act.AskForBucket) ++ bucket.map(Act.Switch(_)))

      case State.Failed => Step(Some(s), Vector.empty)

  private def withReport(m: MoveStatus, r: MoveReport): MoveStatus =
    m.copy(
      counted = Some(r.counted),
      copied = Some(r.copied),
      verified = Some(r.verified),
      failedObject = r.failedObject
    )

  private def describe(what: String, r: MoveReport): String =
    val reason = r.reason.getOrElse("it stopped without saying why")
    r.failedObject.fold(s"$what failed: $reason")(o => s"$what failed at '$o': $reason")
