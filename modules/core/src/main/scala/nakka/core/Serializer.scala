package nakka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * How a payload crosses a boundary: into the event journal, into a shard on another node, onto a
 * Kafka topic.
 *
 * `manifest` is the logical type name persisted alongside the bytes (Pekko's `event_ser_manifest`
 * column). It is nakka's schema-evolution hinge and the equivalent of Akka's `@TypeName`: keep it
 * stable across refactors and you can freely rename the Scala class, because replay resolves by
 * manifest, not by class name.
 */
trait Serializer[A]:
  def manifest: String
  def toBytes(value: A): Array[Byte]
  def fromBytes(bytes: Array[Byte]): A

object Serializer:

  /** Builds a JSON serializer from a jsoniter codec, under an explicit manifest. */
  def json[A](manifest: String)(using codec: JsonValueCodec[A]): Serializer[A] =
    JsonSerializer(manifest, codec)

  private final class JsonSerializer[A](
      val manifest: String,
      codec: JsonValueCodec[A]
  ) extends Serializer[A]:
    def toBytes(value: A): Array[Byte]   = writeToArray(value)(using codec)
    def fromBytes(bytes: Array[Byte]): A = readFromArray(bytes)(using codec)
    override def toString: String        = s"Serializer.json($manifest)"

  /** Passes bytes straight through — for `application/octet-stream` broker messages. */
  val bytes: Serializer[Array[Byte]] = new Serializer[Array[Byte]]:
    val manifest                      = "bytes"
    def toBytes(value: Array[Byte])   = value
    def fromBytes(bytes: Array[Byte]) = bytes

  /** The unit serializer, for handlers that take or return nothing. */
  val unit: Serializer[Unit] = new Serializer[Unit]:
    val manifest                      = "unit"
    def toBytes(value: Unit)          = Array.emptyByteArray
    def fromBytes(bytes: Array[Byte]) = ()

/**
 * Shared jsoniter configuration.
 *
 * A single discriminator field name across the whole platform means a sealed event hierarchy
 * round-trips as `{"type": "ItemAdded", ...}`, which is what makes an event journal readable by
 * anything other than nakka.
 */
object Codecs:

  /**
   * Derives a codec at compile time. Unlike reflection-based serialization this fails the *build*
   * when a type is not serializable, rather than the first replay.
   */
  inline def make[A]: JsonValueCodec[A] =
    JsonCodecMaker.make[A](
      CodecMakerConfig
        .withDiscriminatorFieldName(Some("type"))
        .withAllowRecursiveTypes(true)
        .withRequireDiscriminatorFirst(false)
        .withTransientEmpty(false)
        .withTransientNone(false)
    )

  /** `Codecs.make` plus a `Serializer` under `manifest`, the usual one-liner. */
  inline def serializer[A](manifest: String): Serializer[A] =
    Serializer.json[A](manifest)(using make[A])
