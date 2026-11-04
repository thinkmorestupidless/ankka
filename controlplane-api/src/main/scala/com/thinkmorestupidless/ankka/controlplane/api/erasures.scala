package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs

import java.time.Instant

/** `POST /projects/{id}/erasures` (feature 042): the data subject, and an optional hold. */
final case class ErasureAsk(
    subject: String,
    notBefore: Option[Instant] = None,
    reason: Option[String] = None,
    correlation: Option[String] = None
)

/** What a service's erasure handler reported for a run. */
final case class ErasureHandlerView(
    objectsErased: Long,
    finality: String,
    error: Option[String] = None
)

/** One service's completion of an erasure. */
final case class ErasureCompletionView(
    service: String,
    completedAt: Option[Instant] = None,
    rowsRedacted: Long = 0,
    tokensDropped: Long = 0,
    sessionsEnded: Long = 0,
    handler: Option[ErasureHandlerView] = None
)

/** An owner's override of a hold. */
final case class ErasureOverrideView(owner: String, reason: String, at: Option[Instant] = None)

/** An erasure request, as a member reads it. */
final case class ErasureRequestView(
    id: String,
    project: String,
    subject: String,
    state: String,
    askedBy: Option[String] = None,
    askedAt: Option[Instant] = None,
    notBefore: Option[Instant] = None,
    reason: Option[String] = None,
    correlation: Option[String] = None,
    lapsedAt: Option[Instant] = None,
    holdOverride: Option[ErasureOverrideView] = None,
    erasureSeq: Option[Long] = None,
    destroyedAt: Option[Instant] = None,
    completions: Vector[ErasureCompletionView] = Vector.empty,
    objectsFinalAt: Option[Instant] = None,
    finalAt: Option[Instant] = None
)

/** One line of a project's erasure requests. */
final case class ErasureRequestSummary(
    id: String,
    subject: String,
    state: String,
    correlation: Option[String] = None,
    notBefore: Option[Instant] = None,
    askedBy: Option[String] = None,
    askedAt: Option[Instant] = None,
    appliedAt: Option[Instant] = None,
    finalAt: Option[Instant] = None
)

/** The hold an erasure waited under, and when it lapsed. */
final case class ErasureHoldView(
    notBefore: Instant,
    reason: Option[String] = None,
    lapsedAt: Option[Instant] = None
)

/** A service's part of an erasure, as the certificate states it. */
final case class ErasureCertificateService(
    service: String,
    completedAt: Option[Instant] = None,
    handler: Option[ErasureHandlerView] = None
)

/**
 * The certificate of an applied erasure (feature 042, `data-model.md`, FR-016): who asked, the
 * hold, the key's destruction and each service's completion. It holds no personal field.
 */
final case class ErasureCertificate(
    request: String,
    project: String,
    subject: String,
    askedBy: Option[String] = None,
    askedAt: Option[Instant] = None,
    hold: Option[ErasureHoldView] = None,
    holdOverride: Option[ErasureOverrideView] = None,
    keyDestroyedAt: Option[Instant] = None,
    services: Vector[ErasureCertificateService] = Vector.empty,
    finalAt: Option[Instant] = None
)

object ErasureWire:
  given erasureAskCodec: JsonValueCodec[ErasureAsk]                 = Codecs.make[ErasureAsk]
  given erasureRequestViewCodec: JsonValueCodec[ErasureRequestView] =
    Codecs.make[ErasureRequestView]
  given erasureSummariesCodec: JsonValueCodec[Vector[ErasureRequestSummary]] =
    Codecs.make[Vector[ErasureRequestSummary]]
  given erasureCertificateCodec: JsonValueCodec[ErasureCertificate] =
    Codecs.make[ErasureCertificate]
