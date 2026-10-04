package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs

/**
 * What an agent records, and gives its caller instead of an answer, when the model calls a tool
 * that requires approval.
 *
 * It is awaiting a decision while `decision` is empty. It is never edited once decided. Times are
 * epoch milliseconds, as a session's messages are.
 */
final case class ApprovalRequest(
    id: String,
    callId: String,
    tool: String,
    arguments: Json,
    requestedAt: Long,
    expiresAt: Option[Long] = None,
    decision: Option[Decision] = None
):
  def awaiting: Boolean = decision.isEmpty

object ApprovalRequest:
  given JsonValueCodec[ApprovalRequest] = Codecs.make[ApprovalRequest]

/**
 * A person's answer to an approval request, or the platform's when its time limit passed.
 *
 * `by` names who decided. It is required, recorded and shown — and never what authorizes the
 * decision: who may decide is the ACL of whatever route sends it. `approvalId` is carried so that a
 * decided id can still be found, on the tool result it produced, once its turn is over: that is
 * what answers a second decision as a conflict rather than as unknown.
 */
final case class Decision(
    approvalId: String,
    approved: Boolean,
    by: String,
    note: Option[String] = None,
    at: Long,
    expired: Boolean = false
)

object Decision:

  /** Who an expiry is recorded as having been decided by. */
  val Platform: String = "ankka"

  def approved(approvalId: String, by: String): Decision =
    Decision(approvalId, approved = true, by = by, at = System.currentTimeMillis())

  def refused(approvalId: String, by: String, note: String = ""): Decision =
    Decision(
      approvalId,
      approved = false,
      by = by,
      note = Option(note).filter(_.nonEmpty),
      at = System.currentTimeMillis()
    )

  /** The platform's decision when a time limit passed with none made. */
  private[ankka] def expired(approvalId: String, at: Long): Decision =
    Decision(
      approvalId,
      approved = false,
      by = Platform,
      note = Some("the approval request expired with no decision"),
      at = at,
      expired = true
    )

  /** What is wrong with a decision a caller sent, if anything. */
  def problem(decision: Decision): Option[String] =
    Option.when(decision.by.trim.isEmpty)("a decision must name who made it")

  given JsonValueCodec[Decision] = Codecs.make[Decision]

/** What a turn that may wait came to: an answer, or approval requests awaiting a decision. */
enum AgentOutcome[+O]:
  case Answered(value: O)
  case AwaitingApproval(requests: Vector[ApprovalRequest])

/** One part of a streamed turn: text as it arrives, then, if the turn waits, its requests, last. */
enum AgentPart:
  case Text(text: String)
  case AwaitingApproval(requests: Vector[ApprovalRequest])

/**
 * Thrown by the calls that answer only with a value — `call` and `stream` — when the turn is
 * waiting. Use `ask`, `streamParts` or `decide` to be answered with the requests instead.
 */
final class ApprovalAwaited(val requests: Vector[ApprovalRequest])
    extends RuntimeException(
      s"approval awaited for ${requests.map(r => s"'${r.tool}' (${r.id})").mkString(", ")}",
      null,
      false,
      false
    )

private[ankka] object Approvals:

  /** The awaiting requests, as a reply carries them. */
  final case class Awaiting(requests: Vector[ApprovalRequest])

  given JsonValueCodec[Awaiting] = Codecs.make[Awaiting]

  /**
   * A decision as it reaches an agent's host. `handler` is the handler the caller called, which
   * must be the one whose turn is waiting; empty for the platform's own decision at expiry, which
   * goes to whichever turn holds the request.
   */
  final case class DecideRequest(handler: String, decision: Decision)

  given JsonValueCodec[DecideRequest] = Codecs.make[DecideRequest]

  /** The metadata entry that marks a reply as approval requests rather than an answer. */
  val OutcomeKey: String   = "ankka-outcome"
  val OutcomeValue: String = "approval"

  /** The reserved method a decision reaches an agent's host by. */
  val DecideMethod: String = "ankka:decide"

  /** The prefix a developer's handler may not take. */
  val ReservedPrefix: String = "ankka:"

  /** A fresh approval id: opaque, unique within its session or instance. */
  def newId(): String = java.util.UUID.randomUUID().toString
