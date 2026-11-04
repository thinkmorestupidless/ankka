package com.thinkmorestupidless.ankka.core.keyring

/**
 * What the keyring and a service's instance tell each other about an erasure (feature 042), as
 * `contracts/keyring-and-protocol.md` gives them. In `core`, with no Pekko, so the keyring's server
 * and the runtime's client read and write one definition.
 */

/** An erasure the keyring applied: every instance of every service of `project` carries it out. */
final case class KeyringNotice(seq: Long, project: String, subject: String, rerun: Boolean)

/**
 * What an erasure handler reported: how many objects it erased, and when that is final
 * (`immediate`, or `after:` and an ISO-8601 duration); or, for a run that failed, `failed` and why.
 */
final case class HandlerReport(objectsErased: Long, finality: String, error: Option[String] = None)

object HandlerReport:
  /** The objects are gone now. */
  val Immediate: String = "immediate"

  /** The run failed; `error` says why, and the next pass runs it again. */
  val Failed: String = "failed"

  def failed(why: String): HandlerReport = HandlerReport(0, Failed, Some(why))

/** What one instance did for a notice. */
final case class Completion(
    rowsRedacted: Long,
    tokensDropped: Long,
    sessionsEnded: Long,
    handler: Option[HandlerReport] = None
)

object Completion:
  val nothing: Completion = Completion(0, 0, 0, None)

/** An instance's acknowledgement that it carried out notice `seq`. */
final case class KeyringAck(seq: Long, service: String, instance: String, completion: Completion)

/** The control plane's order to destroy a data subject's key, for erasure log entry `seq`. */
final case class ErasureOrder(seq: Long, project: String, subject: String)

/** The keyring's answer to an order: when the key was destroyed (or already had been). */
final case class ErasureDestroyed(destroyedAt: java.time.Instant)

/** One service's completion of an erasure, as the keyring knows it. */
final case class ServiceErasureStatus(
    service: String,
    completedAt: Option[java.time.Instant] = None,
    completion: Option[Completion] = None
)

/** Every service of the project the keyring told of erasure `seq`, and which have completed. */
final case class ErasureStatus(services: Vector[ServiceErasureStatus] = Vector.empty)

/** `POST /v1/keys`: the key of `subject` in `project`, made when absent if `create`. */
final case class KeyRequest(project: String, subject: String, create: Boolean = false)

/**
 * The answer to a `KeyRequest`: the key in standard base64, or `destroyed`, or (with 403) why the
 * caller was refused. Exactly one is present.
 */
final case class KeyReply(
    key: Option[String] = None,
    destroyed: Option[Boolean] = None,
    refused: Option[String] = None
)

/** `POST /v1/lookup-keys`: the project's lookup key. */
final case class LookupKeyRequest(project: String)

/** The project's lookup key, in standard base64. */
final case class LookupKeyReply(key: String)

/**
 * The frames of the notices WebSocket, text frames of JSON told apart by `type`: the instance's
 * `hello` first, then the keyring's `notice`s and `heartbeat`s, and the instance's `ack`s.
 */
sealed trait NoticeFrame

object NoticeFrame:
  /** The instance's first frame: who it is, and the last notice it acknowledged. */
  final case class Hello(project: String, service: String, instance: String, after: Long)
      extends NoticeFrame

  final case class Notice(seq: Long, project: String, subject: String, rerun: Boolean)
      extends NoticeFrame:
    def notice: KeyringNotice = KeyringNotice(seq, project, subject, rerun)

  final case class Ack(seq: Long, service: String, instance: String, completion: Completion)
      extends NoticeFrame:
    def ack: KeyringAck = KeyringAck(seq, service, instance, completion)

  /** The keyring confirming the channel is open when it has no notice to send. */
  final case class Heartbeat(at: java.time.Instant) extends NoticeFrame

  def of(n: KeyringNotice): Notice = Notice(n.seq, n.project, n.subject, n.rerun)
  def of(a: KeyringAck): Ack       = Ack(a.seq, a.service, a.instance, a.completion)

/** A personal field's envelope, or a field's own JSON, carried as it is. */
final case class RawJson(bytes: Array[Byte]):
  def text: String = String(bytes, java.nio.charset.StandardCharsets.UTF_8)

  override def equals(other: Any): Boolean = other match
    case RawJson(b) => java.util.Arrays.equals(bytes, b)
    case _          => false

  override def hashCode: Int = java.util.Arrays.hashCode(bytes)

object RawJson:
  def apply(text: String): RawJson = new RawJson(
    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  )

/**
 * `POST /v1/decrypt`: a machine with a decrypt grant asks for the fields of one message, each an
 * envelope as it was written.
 */
final case class DecryptRequest(
    grant: String,
    project: String,
    manifest: String,
    fields: Vector[RawJson]
)

/** Each field's own JSON in order, or `null` where its data subject is erased. */
final case class DecryptReply(values: Vector[Option[RawJson]])

/**
 * The codecs of every message above, the one definition both ends read: absent options are left
 * out, so each message is the contract's JSON exactly; `KeyringWireSuite` pins each one.
 */
object KeyringWire:
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
  import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

  given rawJsonCodec: JsonValueCodec[RawJson] = new JsonValueCodec[RawJson]:
    def nullValue: RawJson                                     = null
    def encodeValue(x: RawJson, out: JsonWriter): Unit         = out.writeRawVal(x.bytes)
    def decodeValue(in: JsonReader, default: RawJson): RawJson = RawJson(in.readRawValAsBytes())

  given keyRequestCodec: JsonValueCodec[KeyRequest] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
  given keyReplyCodec: JsonValueCodec[KeyReply]                 = JsonCodecMaker.make
  given lookupKeyRequestCodec: JsonValueCodec[LookupKeyRequest] = JsonCodecMaker.make
  given lookupKeyReplyCodec: JsonValueCodec[LookupKeyReply]     = JsonCodecMaker.make
  given noticeFrameCodec: JsonValueCodec[NoticeFrame]           =
    JsonCodecMaker.make(
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withRequireDiscriminatorFirst(false)
        .withTransientDefault(false)
        .withAdtLeafClassNameMapper(x =>
          JsonCodecMaker.enforce_snake_case(JsonCodecMaker.simpleClassName(x))
        )
    )
  given erasureOrderCodec: JsonValueCodec[ErasureOrder]         = JsonCodecMaker.make
  given erasureDestroyedCodec: JsonValueCodec[ErasureDestroyed] = JsonCodecMaker.make
  given erasureStatusCodec: JsonValueCodec[ErasureStatus]       =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false).withTransientEmpty(false))
  given decryptRequestCodec: JsonValueCodec[DecryptRequest] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))
  given decryptReplyCodec: JsonValueCodec[DecryptReply] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false))
