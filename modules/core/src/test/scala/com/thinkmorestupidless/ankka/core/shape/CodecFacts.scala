package com.thinkmorestupidless.ankka.core.shape

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Codecs

import java.util.UUID

/**
 * What `Codecs.make` writes and reads, pinned by round trip. The shape derivation (research R1 of
 * feature 067) says these facts in JSON Schema; if one of them changes, the shapes are wrong, and
 * this suite is where that is found first.
 */
object CodecFacts:
  final case class Defaulted(name: String, quantity: Int = 1)
  final case class Nicknamed(name: String, nickname: Option[String])
  final case class Listed(name: String, tags: List[String])
  final case class Mapped(name: String, counts: Map[String, Int])
  final case class Numbers(amount: BigDecimal, id: UUID)

  sealed trait Event
  object Event:
    final case class Added(item: String) extends Event
    case object Cleared                  extends Event

  enum Status:
    case Ready, Paused

  enum Figure:
    case Circle(radius: Double)
    case Square(side: Double)

  given JsonValueCodec[Defaulted] = Codecs.make
  given JsonValueCodec[Nicknamed] = Codecs.make
  given JsonValueCodec[Listed]    = Codecs.make
  given JsonValueCodec[Mapped]    = Codecs.make
  given JsonValueCodec[Numbers]   = Codecs.make
  given JsonValueCodec[Event]     = Codecs.make
  given JsonValueCodec[Status]    = Codecs.make
  given JsonValueCodec[Figure]    = Codecs.make

class CodecFacts extends munit.FunSuite:
  import CodecFacts.*

  private def write[A: JsonValueCodec](a: A): String = writeToString(a)
  private def read[A: JsonValueCodec](s: String): A  = readFromString[A](s)

  test("a field equal to its default is omitted on write") {
    assertEquals(write(Defaulted("a")), """{"name":"a"}""")
    assertEquals(write(Defaulted("a", 2)), """{"name":"a","quantity":2}""")
  }

  test("a field with a default may be absent on read") {
    assertEquals(read[Defaulted]("""{"name":"a"}"""), Defaulted("a"))
  }

  test("None is written as null") {
    assertEquals(write(Nicknamed("a", None)), """{"name":"a","nickname":null}""")
  }

  test("an absent Option reads as None") {
    assertEquals(read[Nicknamed]("""{"name":"a"}"""), Nicknamed("a", None))
  }

  test("an empty collection is written, and a missing one reads as empty") {
    assertEquals(write(Listed("a", Nil)), """{"name":"a","tags":[]}""")
    assertEquals(read[Listed]("""{"name":"a"}"""), Listed("a", Nil))
    assertEquals(write(Mapped("a", Map.empty)), """{"name":"a","counts":{}}""")
    assertEquals(read[Mapped]("""{"name":"a"}"""), Mapped("a", Map.empty))
  }

  test("a missing field with no default, not an Option and not a collection, is refused") {
    intercept[JsonReaderException](read[Defaulted]("""{"quantity":2}"""))
  }

  test("a field of the wrong JSON type is refused") {
    intercept[JsonReaderException](read[Defaulted]("""{"name":3}"""))
    intercept[JsonReaderException](read[Defaulted]("""{"name":"a","quantity":"2"}"""))
  }

  test("a sealed case carries \"type\", and a case object is an object with only \"type\"") {
    assertEquals(write[Event](Event.Added("x")), """{"type":"Added","item":"x"}""")
    assertEquals(write[Event](Event.Cleared), """{"type":"Cleared"}""")
    assertEquals(read[Event]("""{"item":"x","type":"Added"}"""), Event.Added("x"))
  }

  test("a fieldless enumeration case is an object with \"type\"") {
    assertEquals(write(Status.Ready), """{"type":"Ready"}""")
  }

  test("an enumeration with fields carries \"type\" per case") {
    assertEquals(write[Figure](Figure.Circle(1.5)), """{"type":"Circle","radius":1.5}""")
  }

  test("BigDecimal is a JSON number and UUID a string") {
    val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    assertEquals(
      write(Numbers(BigDecimal("1.50"), id)),
      """{"amount":1.50,"id":"00000000-0000-0000-0000-000000000001"}"""
    )
  }

  test("an unknown field is skipped on read") {
    assertEquals(read[Defaulted]("""{"name":"a","extra":true}"""), Defaulted("a"))
  }
