package com.thinkmorestupidless.ankka.runtime.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs

/**
 * The keyring's HTTP routes' bodies (`contracts/keyring-api.md`), shared by the keyring and the
 * control plane, its one caller for erasures, so the two cannot disagree.
 */
object KeyringApi:

  /** Apply an erasure the control plane has written to both copies of the log, at `sequence`. */
  final case class ApplyErasure(erasureId: String, subject: String, sequence: Long)

  final case class ChannelStatus(
      service: String,
      instance: String,
      acknowledged: Boolean,
      closedUnacknowledged: Boolean
  )

  final case class ServiceCompletion(
      service: String,
      instance: String,
      completedAt: Long,
      viewsRedacted: Vector[String],
      rowsRedacted: Long,
      sessionsMarked: Int,
      instancesStopped: Int,
      handlerOk: Option[Boolean],
      handlerDetail: Option[String],
      objectsErased: Option[Long],
      objectsFinalAt: Option[Long]
  )

  /** Where an erasure stands in the keyring: the key destroyed, who was told, who has completed. */
  final case class ErasureStatus(
      erasureId: String,
      project: String,
      subject: String,
      sequence: Long,
      keyDestroyedAt: Long,
      everExisted: Boolean,
      channels: Vector[ChannelStatus],
      completions: Vector[ServiceCompletion]
  ):
    /**
     * The services told of the erasure and their completions, a service complete when every one of
     * its channels answered and one of its instances completed.
     */
    def completedServices: Vector[String] =
      val told = channels.groupBy(_.service)
      completions.map(_.service).distinct.filter { service =>
        told.get(service).forall(_.forall(c => c.acknowledged || c.closedUnacknowledged))
      }

  final case class KeyringStatus(
      ready: Boolean,
      copies: Int,
      replayed: Int,
      behind: Option[String],
      channels: Int
  )

  /** One entry of the erasure log, as the control plane keeps it and the keyring replays it. */
  /** A personal envelope a machine outside the installation asks the keyring to open. */
  final case class DecryptRequest(subject: String, project: String, data: String)

  /** Its value, as the JSON the field was written as. */
  final case class DecryptReply(value: String)

  final case class LogEntry(
      erasureId: String,
      project: String,
      subject: String,
      sequence: Long,
      destroyedAt: Long
  )

  given applyCodec: JsonValueCodec[ApplyErasure]            = Codecs.make[ApplyErasure]
  given statusCodec: JsonValueCodec[ErasureStatus]          = Codecs.make[ErasureStatus]
  given keyringStatusCodec: JsonValueCodec[KeyringStatus]   = Codecs.make[KeyringStatus]
  given logCodec: JsonValueCodec[Vector[LogEntry]]          = Codecs.make[Vector[LogEntry]]
  given logEntryCodec: JsonValueCodec[LogEntry]             = Codecs.make[LogEntry]
  given decryptRequestCodec: JsonValueCodec[DecryptRequest] = Codecs.make[DecryptRequest]
  given decryptReplyCodec: JsonValueCodec[DecryptReply]     = Codecs.make[DecryptReply]
