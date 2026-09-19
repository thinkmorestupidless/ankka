package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
 * Serializers for the types that turn up in command signatures regardless of domain.
 *
 * Domain payloads still need one explicit line each:
 * {{{
 * given Serializer[LineItem] = Codecs.serializer[LineItem]("line-item")
 * }}}
 * That is deliberate. The manifest is the schema-evolution hinge, and a name derived automatically
 * from a class name would silently change the moment the class is renamed — breaking replay of an
 * existing journal. Naming it makes the commitment visible.
 */
object Serializers:

  private def primitive[A](
      name: String,
      encode: A => Array[Byte],
      decode: Array[Byte] => A
  ): Serializer[A] = new Serializer[A]:
    val manifest                      = name
    def toBytes(value: A)             = encode(value)
    def fromBytes(bytes: Array[Byte]) = decode(bytes)

  private def viaString[A](name: String, parse: String => A): Serializer[A] =
    primitive[A](
      name,
      value => value.toString.getBytes("UTF-8"),
      bytes => parse(String(bytes, "UTF-8"))
    )

  given unit: Serializer[Unit]         = Serializer.unit
  given bytes: Serializer[Array[Byte]] = Serializer.bytes

  given boolean: Serializer[Boolean] = viaString("boolean", _.toBoolean)
  given byte: Serializer[Byte]       = viaString("byte", _.toByte)
  given short: Serializer[Short]     = viaString("short", _.toShort)
  given int: Serializer[Int]         = viaString("int", _.toInt)
  given long: Serializer[Long]       = viaString("long", _.toLong)
  given float: Serializer[Float]     = viaString("float", _.toFloat)
  given double: Serializer[Double]   = viaString("double", _.toDouble)

  /** Raw UTF-8, not JSON — a String reply should not arrive wrapped in quotes. */
  given string: Serializer[String] =
    primitive[String]("string", _.getBytes("UTF-8"), String(_, "UTF-8"))

  /**
   * Encoded as whole milliseconds, not as `toString`. A duration's textual form ("5 seconds") is
   * not round-trippable through `toLong`, and TTLs cross the wire often enough that getting this
   * wrong would be a recurring trap.
   */
  given finiteDuration: Serializer[FiniteDuration] =
    primitive[FiniteDuration](
      "duration-millis",
      value => value.toMillis.toString.getBytes("UTF-8"),
      bytes => FiniteDuration(String(bytes, "UTF-8").toLong, TimeUnit.MILLISECONDS)
    )

  given done: Serializer[Done] =
    primitive[Done]("done", _ => Array.emptyByteArray, _ => Done)

  /** Lifts any serializer to `Option`, encoding `None` as zero bytes. */
  given option[A](using inner: Serializer[A]): Serializer[Option[A]] =
    new Serializer[Option[A]]:
      val manifest = s"option[${inner.manifest}]"
      def toBytes(value: Option[A]) = value match
        case Some(a) => Array[Byte](1) ++ inner.toBytes(a)
        case None    => Array.emptyByteArray
      def fromBytes(bytes: Array[Byte]) =
        if bytes.isEmpty then None else Some(inner.fromBytes(bytes.drop(1)))

  /** For any type with a jsoniter codec in scope, under an explicit manifest. */
  def json[A](manifest: String)(using JsonValueCodec[A]): Serializer[A] =
    Serializer.json[A](manifest)
