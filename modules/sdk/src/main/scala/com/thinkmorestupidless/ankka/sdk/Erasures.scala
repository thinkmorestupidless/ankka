package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode}

import java.time.LocalDate

/**
 * Asking for an erasure request from code (feature 042): `ctx.erasures.ask(subject)`. The request
 * is asked of the control plane as this service, which needs the `erasure` grant of the project it
 * asks in, its own included, and is named as who asked. Given to endpoints, consumers and erasure
 * handlers.
 */
trait Erasures:
  /**
   * Asks for the erasure of `subject` in `project`, this service's own when `None`. Answers the
   * request's id and where it stands; refused as `Forbidden` without the `erasure` grant.
   */
  def ask(
      subject: DataSubject,
      project: Option[String] = None,
      notBefore: Option[LocalDate] = None,
      reason: Option[String] = None,
      correlation: Option[String] = None
  ): ErasureAsked

/** An erasure request as asked: its id in its project and its state (held, applying, applied). */
final case class ErasureAsked(id: String, state: String)

object Erasures:
  /** Refuses every ask, saying why: no control plane is reachable from here. */
  val unavailable: Erasures = new Erasures:
    def ask(
        subject: DataSubject,
        project: Option[String],
        notBefore: Option[LocalDate],
        reason: Option[String],
        correlation: Option[String]
    ): ErasureAsked =
      throw CommandError(
        "asking for an erasure needs the control plane, and this service reaches none",
        ErrorCode.Unavailable
      )
