package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

/**
 * The personal field (feature 042) against `protocol/fixtures/personal/`, which an implementation
 * independent of every SDK's wrote: the same envelope byte for byte, the erased and corrupt forms,
 * FR-002's rule, and the edge form where no serializer is encoding.
 */
final class PersonalSuite extends munit.FunSuite:

  import PersonalSuite.*

  private def fixtures: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/fixtures/personal"))
      .find(Files.isDirectory(_))
      .getOrElse(fail("could not find protocol/fixtures/personal"))

  private def read[A](name: String)(using JsonValueCodec[A]): A =
    readFromArray[A](Files.readAllBytes(fixtures.resolve(name)))

  private lazy val envelopes = read[Envelopes]("envelopes.json")
  private lazy val subjects  = read[Subjects]("subjects.json")
  private lazy val edges     = read[Edges]("edge.json")

  private def b64(s: String): Array[Byte] = Base64.getDecoder.decode(s)

  /** A keyring of one project holding the given keys, every other subject destroyed. */
  private def keyring(own: String, keys: Map[String, Array[Byte]]): Keyring =
    new Keyring:
      val project: String                                                        = own
      def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
        keys.get(s"$project/${subject.value}") match
          case Some(k) => KeyAnswer.Key(IArray.unsafeFromArray(k))
          case None    => KeyAnswer.Destroyed

  private def within[T](k: Keyring, manifest: String, nonce: Option[Array[Byte]] = None)(
      body: => T
  ): T =
    Keyring.binding(k)(PersonalScope.within(manifest, nonce)(body))

  /** `json` with the whitespace outside its strings removed. */
  private def compact(json: String): String =
    val out      = StringBuilder()
    var inString = false
    var escaped  = false
    json.foreach { ch =>
      if inString then
        out += ch
        if escaped then escaped = false
        else if ch == '\\' then escaped = true
        else if ch == '"' then inString = false
      else if ch == '"' then
        inString = true
        out += ch
      else if !ch.isWhitespace then out += ch
    }
    out.result()

  test("each fixture's envelope is written byte for byte, and read back to the value") {
    assert(envelopes.cases.nonEmpty)
    envelopes.cases.foreach { c =>
      val k        = keyring(c.project, Map(s"${c.project}/${c.subject}" -> b64(c.key)))
      val value    = readFromString[Json](c.plaintext)
      val personal = Personal(DataSubject(c.subject), value)
      val written  = within(k, c.manifest, Some(b64(c.nonce)))(writeToArray(personal))
      assertEquals(new String(written, UTF_8), compact(writeToString(c.envelope)), c.name)
      val back = within(k, c.manifest)(readFromArray[Personal[Json]](written))
      assertEquals(back, Personal.Present(DataSubject(c.subject), value), c.name)
    }
  }

  test("the subject-only envelope reads as erased, and is what an erased field is written as") {
    envelopes.erased.foreach { e =>
      val k    = keyring(e.project, Map.empty)
      val json = writeToArray(e.envelope)
      val back = within(k, "player-registered")(readFromArray[Personal[Json]](json))
      assertEquals(back, Personal.Erased[Json](DataSubject(e.subject)))
      val again = within(k, "player-registered")(writeToArray(back))
      assertEquals(new String(again, UTF_8), compact(writeToString(e.envelope)))
    }
  }

  test("an envelope with a sealed value reads as erased once the subject key is destroyed") {
    val c    = envelopes.cases.head
    val back = within(keyring(c.project, Map.empty), c.manifest)(
      readFromArray[Personal[Json]](writeToArray(c.envelope))
    )
    assertEquals(back, Personal.Erased[Json](DataSubject(c.subject)))
  }

  test("a spliced, misread or wrongly keyed envelope fails as corrupt, never as erased") {
    assertEquals(envelopes.corrupt.size, 3)
    envelopes.corrupt.foreach { c =>
      val project = c.envelope.project
      val subject = c.envelope.subject
      val k       = keyring(project, Map(s"$project/$subject" -> b64(c.key)))
      val e       = intercept[CommandError](
        within(k, c.manifest)(readFromArray[Personal[Json]](writeToArray(c.envelope)))
      )
      assertEquals(e.details.get(Personal.ErrorKey), Some(Personal.Corrupt), c.name)
      assert(e.getMessage.contains(s"'$subject'"), e.getMessage)
    }
  }

  test("an envelope of a version this reader does not know fails as corrupt") {
    val c = envelopes.cases.head
    val k = keyring(c.project, Map(s"${c.project}/${c.subject}" -> b64(c.key)))
    val e = intercept[CommandError](
      within(k, c.manifest)(readFromArray[Personal[Json]](writeToArray(envelopes.unknownVersion)))
    )
    assertEquals(e.details.get(Personal.ErrorKey), Some(Personal.Corrupt))
  }

  test("a value cannot be written for a destroyed subject key") {
    val e = intercept[CommandError](
      within(keyring("brand", Map.empty), "player-registered")(
        writeToArray(Personal(DataSubject("player/8c1f"), "ada@example.com"))
      )
    )
    assertEquals(e.details.get(Personal.ErrorKey), Some(Personal.ErasedRefusal))
    assertEquals(e.code, ErrorCode.Conflict)
    assert(e.getMessage.contains("'player/8c1f' is erased"), e.getMessage)
  }

  test("with no keyring installed a personal field is refused as unavailable, naming the keyring") {
    val thrown = intercept[KeyringUnavailable](
      Keyring.binding(Keyring.unavailable)(
        PersonalScope.within("player-registered")(
          writeToArray(Personal(DataSubject("player/8c1f"), "ada@example.com"))
        )
      )
    )
    // A caller reads it as the keyring's own refusal.
    val e = CommandError.from(thrown).getOrElse(fail("no CommandError behind it"))
    assertEquals(e.code, ErrorCode.Unavailable)
    assert(e.getMessage.contains("keyring"), e.getMessage)
  }

  test("the textual form names the data subject and never the value") {
    val s = DataSubject("player/8c1f")
    assertEquals(Personal(s, "ada@example.com").toString, "Personal(player/8c1f)")
    assertEquals(Personal.Erased[String](s).toString, "Personal(player/8c1f)")
    assertEquals(s"${Option(Personal(s, "ada@example.com"))}", "Some(Personal(player/8c1f))")
  }

  test("a data subject follows FR-002's rule, refusing with the shared message") {
    subjects.valid.foreach(v => assertEquals(DataSubject(v).value, v))
    subjects.invalid.foreach { v =>
      val e = intercept[IllegalArgumentException](DataSubject(v))
      assert(e.getMessage.contains(subjects.message), e.getMessage)
      assert(DataSubject.from(v).isLeft, v)
    }
    // The refusal names the rule, never the value refused (FR-029).
    val email = DataSubject.from("ada@example.com?")
    assert(email.left.exists(!_.contains("ada@example.com")), email.toString)
  }

  test("a derived codec finds a personal field in an Option, a List and a nested record") {
    val s     = DataSubject("player/8c1f")
    val k     = keyring("brand", Map("brand/player/8c1f" -> Array.fill(32)(7.toByte)))
    val value = Profile(
      Some(Personal(s, "ada@example.com")),
      List(Personal(s, "Ada"), Personal(s, "Lovelace")),
      Address(Personal(s, "1 High St"), "Leeds")
    )
    val bytes = within(k, "profile")(writeToArray(value))
    val text  = new String(bytes, UTF_8)
    Vector("ada@example.com", "Ada", "Lovelace", "1 High St").foreach(v =>
      assert(!text.contains(v), s"$v is in the clear: $text")
    )
    assert(text.contains("Leeds"), text)
    assertEquals(within(k, "profile")(readFromArray[Profile](bytes)), value)
  }

  test("the edge form, where no serializer is encoding, matches edge.json both ways") {
    edges.cases.foreach { c =>
      val subject  = DataSubject(c.subject)
      val personal =
        c.value.fold(Personal.Erased[Json](subject))(v => Personal(subject, Json(compact(v.text))))
      assertEquals(
        new String(writeToArray(personal), UTF_8),
        compact(writeToString(c.edge)),
        c.name
      )
      val read = readFromString[Personal[Json]](compact(c.edge.text))
      assertEquals(read, personal, c.name)
    }
  }

  test("the associated data binds the manifest: one read under another manifest fails") {
    val c = envelopes.cases.head
    val k = keyring(c.project, Map(s"${c.project}/${c.subject}" -> b64(c.key)))
    intercept[CommandError](
      within(k, c.manifest + "-v2")(readFromArray[Personal[Json]](writeToArray(c.envelope)))
    )
  }

object PersonalSuite:

  /** Any JSON value, kept as its compact text: a fixture's plaintext is the field's own JSON. */
  final case class Json(text: String)

  given JsonValueCodec[Json] = new JsonValueCodec[Json]:
    def nullValue: Json                                  = null
    def decodeValue(in: JsonReader, default: Json): Json =
      Json(new String(in.readRawValAsBytes(), UTF_8))
    def encodeValue(x: Json, out: JsonWriter): Unit = out.writeRawVal(x.text.getBytes(UTF_8))

  final case class Envelope(
      `$personal`: Int,
      project: String,
      subject: String,
      c: Option[String] = None
  )
  final case class Case(
      name: String,
      key: String,
      nonce: String,
      project: String,
      subject: String,
      manifest: String,
      plaintext: String,
      envelope: Envelope
  )
  final case class Erased(name: String, project: String, subject: String, envelope: Envelope)
  final case class Corrupt(name: String, key: String, manifest: String, envelope: Envelope)
  final case class Envelopes(
      cases: Vector[Case],
      erased: Vector[Erased],
      corrupt: Vector[Corrupt],
      @com.github.plokhotnyuk.jsoniter_scala.macros.named(
        "unknown-version"
      ) unknownVersion: Envelope
  )
  final case class Subjects(message: String, valid: Vector[String], invalid: Vector[String])
  final case class EdgeCase(name: String, subject: String, value: Option[Json] = None, edge: Json)
  final case class Edges(cases: Vector[EdgeCase])

  final case class Address(line1: Personal[String], city: String)
  final case class Profile(
      email: Option[Personal[String]],
      names: List[Personal[String]],
      address: Address
  )

  given JsonValueCodec[Envelope]  = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))
  given JsonValueCodec[Envelopes] = JsonCodecMaker.make
  given JsonValueCodec[Subjects]  = JsonCodecMaker.make
  given JsonValueCodec[Edges]     = JsonCodecMaker.make
  given JsonValueCodec[Profile]   = Codecs.make
