package com.thinkmorestupidless.ankka.javasdk.codec

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  DataSubject as CoreSubject,
  ErrorCode,
  KeyAnswer,
  Keyring,
  Personal as CorePersonal,
  PersonalScope
}
import com.thinkmorestupidless.ankka.javasdk.{DataSubject, Personal}
import com.thinkmorestupidless.ankka.javasdk.codec.PersonalShapes.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate
import java.util.Base64
import scala.jdk.CollectionConverters.*

/**
 * The Java codec's personal field (feature 042) against `protocol/fixtures/personal/`: it writes
 * each envelope byte for byte under the fixture's key and nonce, reads it back, reads the erased
 * form as erased and refuses a corrupt one; outside a scope it writes and reads the edge form.
 */
class PersonalRecordCodecSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()

  private val dir: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/fixtures/personal"))
      .find(Files.isDirectory(_))
      .getOrElse(fail("could not find protocol/fixtures/personal"))

  private def fixture(name: String): JsonNode = mapper.readTree(dir.resolve(name).toFile)

  private def compact(node: JsonNode): String = mapper.writeValueAsString(node)

  private def keyring(own: String, key: String): Keyring =
    val bytes = IArray.from(Base64.getDecoder.decode(key))
    new Keyring:
      def project: String                                            = own
      def key(p: String, s: CoreSubject, create: Boolean): KeyAnswer = KeyAnswer.Key(bytes)

  /** The record holding `plaintext` (a case's own JSON) in its one field, and its serializer. */
  private def holder(subject: String, plaintext: String): (Any, Serializer[Any]) =
    val s = DataSubject.of(subject)
    mapper.readTree(plaintext) match
      case node if node.isObject =>
        val a = new Address(node.get("line1").asText, node.get("city").asText)
        new OfAddress(Personal.of(s, a)) -> Serializer
          .of(classOf[OfAddress])
          .asInstanceOf[Serializer[Any]]
      case node if node.asText.matches("\\d{4}-\\d{2}-\\d{2}") =>
        new OfDate(Personal.of(s, LocalDate.parse(node.asText))) ->
          Serializer.of(classOf[OfDate]).asInstanceOf[Serializer[Any]]
      case node =>
        new OfString(Personal.of(s, node.asText)) ->
          Serializer.of(classOf[OfString]).asInstanceOf[Serializer[Any]]

  private def fieldOf(value: Any): Personal[?] = value match
    case r: OfString  => r.field
    case r: OfDate    => r.field
    case r: OfAddress => r.field
    case other        => fail(s"not a holder: $other")

  for c <- fixture("envelopes.json").get("cases").asScala do
    val name = c.get("name").asText
    test(s"writes the envelope of $name byte for byte, and reads it back") {
      val (value, serializer) = holder(c.get("subject").asText, c.get("plaintext").asText)
      val nonce               = Base64.getDecoder.decode(c.get("nonce").asText)
      val k                   = keyring(c.get("project").asText, c.get("key").asText)
      val manifest            = c.get("manifest").asText
      val written             = Keyring.binding(k)(
        PersonalScope.within(manifest, Some(nonce))(serializer.toBytes(value))
      )
      assertEquals(new String(written, UTF_8), s"""{"field":${compact(c.get("envelope"))}}""")
      val read = Keyring.binding(k)(PersonalScope.within(manifest)(serializer.fromBytes(written)))
      assertEquals(read, value)
      assertEquals(fieldOf(read).toString, s"Personal(${c.get("subject").asText})")
    }

  test("the erased form reads as erased, with its data subject") {
    val erased = fixture("envelopes.json").get("erased").get(0)
    val bytes  = s"""{"field":${compact(erased.get("envelope"))}}""".getBytes(UTF_8)
    val read   = Keyring.binding(keyring("brand", Base64.getEncoder.encodeToString(new Array(32))))(
      PersonalScope.within("player-registered")(Serializer.of(classOf[OfString]).fromBytes(bytes))
    )
    assertEquals(read.field.isErased, true)
    assertEquals(read.field.subject, DataSubject.of("player/8c1f"))
    assertEquals(read.field.orElse("gone"), "gone")
  }

  for c <- fixture("envelopes.json").get("corrupt").asScala do
    test(s"refuses ${c.get("name").asText} as corrupt") {
      val bytes = s"""{"field":${compact(c.get("envelope"))}}""".getBytes(UTF_8)
      val e     = intercept[CommandError](
        Keyring.binding(keyring("brand", c.get("key").asText))(
          PersonalScope.within(c.get("manifest").asText)(
            Serializer.of(classOf[OfString]).fromBytes(bytes)
          )
        )
      )
      assertEquals(e.code, ErrorCode.Internal)
      assertEquals(e.details.get(CorePersonal.ErrorKey), Some(CorePersonal.Corrupt))
    }

  test("an envelope of a version this reader does not know is refused as corrupt") {
    val bytes =
      s"""{"field":${compact(fixture("envelopes.json").get("unknown-version"))}}""".getBytes(UTF_8)
    val e = intercept[CommandError](
      PersonalScope.within("player-registered")(Serializer.of(classOf[OfString]).fromBytes(bytes))
    )
    assertEquals(e.details.get(CorePersonal.ErrorKey), Some(CorePersonal.Corrupt))
  }

  for c <- fixture("edge.json").get("cases").asScala do
    val name = c.get("name").asText
    test(s"writes and reads the edge form of $name outside a scope") {
      val s                    = DataSubject.of(c.get("subject").asText)
      val value                = Option(c.get("value"))
      val (record, serializer) = value match
        case Some(v) if v.isObject =>
          new OfAddress(Personal.of(s, new Address(v.get("line1").asText, v.get("city").asText))) ->
            Serializer.of(classOf[OfAddress]).asInstanceOf[Serializer[Any]]
        case Some(v) =>
          new OfString(Personal.of(s, v.asText)) -> Serializer
            .of(classOf[OfString])
            .asInstanceOf[Serializer[Any]]
        case None =>
          new OfString(Personal.Erased[String](s)) -> Serializer
            .of(classOf[OfString])
            .asInstanceOf[Serializer[Any]]
      val written = serializer.toBytes(record)
      assertEquals(new String(written, UTF_8), s"""{"field":${compact(c.get("edge"))}}""")
      assertEquals(serializer.fromBytes(written), record)
    }

  test("a data subject outside the rule is refused with the rule's own words") {
    val subjects = fixture("subjects.json")
    for invalid <- subjects.get("invalid").asScala do
      val e = intercept[IllegalArgumentException](DataSubject.of(invalid.asText))
      assertEquals(
        e.getMessage,
        s"the value given is not a data subject: ${subjects.get("message").asText}"
      )
    for valid <- subjects.get("valid").asScala do
      assertEquals(DataSubject.of(valid.asText).value, valid.asText)
  }

  test("a personal field written with no keyring is refused as unavailable, naming the keyring") {
    val e = intercept[RuntimeException](
      Keyring.binding(Keyring.unavailable)(
        PersonalScope.within("player-registered")(
          Serializer
            .of(classOf[OfString])
            .toBytes(new OfString(Personal.of(DataSubject.of("a"), "x")))
        )
      )
    )
    val error = CommandError.from(e).getOrElse(fail(s"no CommandError behind $e"))
    assertEquals(error.code, ErrorCode.Unavailable)
    assert(error.getMessage.contains("keyring"), error.getMessage)
  }

  // ── FR-027: a field read from another project stays under that project's key ──────────────────

  private val Envelope =
    """\{"\$personal":1,"project":"([^"]*)","subject":"([^"]*)"(?:,"c":"([^"]*)")?\}""".r

  /**
   * Own project `own`; `players`' key is `foreign` (or destroyed), every other project's `mine`.
   */
  private def twoProjects(own: String, foreign: Option[IArray[Byte]]): Keyring =
    val mine = IArray.fill[Byte](32)(7)
    new Keyring:
      def project: String                                            = own
      def key(p: String, s: CoreSubject, create: Boolean): KeyAnswer =
        if p == "players" then foreign.fold(KeyAnswer.Destroyed)(KeyAnswer.Key(_))
        else KeyAnswer.Key(mine)

  private val playersKey = IArray.fill[Byte](32)(3)
  private val ofString   = Serializer.of(classOf[OfString])

  /** What `players` journals: its own envelope, under its own key. */
  private def fromPlayers(): Array[Byte] =
    Keyring.binding(twoProjects("players", Some(playersKey)))(
      PersonalScope.within("player-registered")(
        ofString.toBytes(
          new OfString(Personal.of(DataSubject.of("player/8c1f"), "ada@example.com"))
        )
      )
    )

  test("a field read from another project's envelope is written again under that project's key") {
    val engagement = twoProjects("engagement", Some(playersKey))
    val written    = Keyring.binding(engagement)(
      PersonalScope.within("engaged") {
        val read = PersonalScope.within("player-registered")(ofString.fromBytes(fromPlayers()))
        assertEquals(read.field.orElse("none"), "ada@example.com")
        new String(ofString.toBytes(read), UTF_8)
      }
    )
    val m = Envelope.findFirstMatchIn(written).getOrElse(fail(s"no envelope in $written"))
    assertEquals(m.group(1), "players")
    val opened = com.thinkmorestupidless.ankka.core.PersonalCipher.open(
      playersKey,
      Base64.getDecoder.decode(m.group(3)),
      com.thinkmorestupidless.ankka.core.PersonalCipher
        .associatedData("player/8c1f", "players", "engaged")
    )
    assertEquals(opened.map(new String(_, UTF_8)), Some("\"ada@example.com\""))
  }

  test("a field a handler makes, with the same value, is sealed under the service's own key") {
    val written = Keyring.binding(twoProjects("engagement", Some(playersKey)))(
      PersonalScope.within("engaged") {
        PersonalScope.within("player-registered")(ofString.fromBytes(fromPlayers())): Unit
        new String(
          ofString.toBytes(
            new OfString(Personal.of(DataSubject.of("player/8c1f"), "ada@example.com"))
          ),
          UTF_8
        )
      }
    )
    assertEquals(Envelope.findFirstMatchIn(written).map(_.group(1)), Some("engagement"))
  }

  test("a field of another project whose key is gone is written erased, under that project") {
    val bytes   = fromPlayers()
    val written = Keyring.binding(twoProjects("engagement", None))(
      PersonalScope.within("engaged") {
        val read = PersonalScope.within("player-registered")(ofString.fromBytes(bytes))
        assertEquals(read.field.isErased, true)
        new String(ofString.toBytes(read), UTF_8)
      }
    )
    val m = Envelope.findFirstMatchIn(written).getOrElse(fail(s"no envelope in $written"))
    assertEquals((m.group(1), Option(m.group(3))), ("players", None))
  }
