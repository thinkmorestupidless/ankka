package com.thinkmorestupidless.ankka.core.personal

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

/**
 * `protocol/fixtures/personal/`: a fixed subject key and lookup key (`keys.json`), and envelopes
 * written by this codec under them (`envelopes.json`), each with what it must decode to. Every SDK
 * reads both and must decode each row as it says, which is what makes "every language writes and
 * reads the same envelope" a fact.
 *
 * The nonce is random, so the file is not compared byte for byte: it is written under
 * `-Dankka.fixtures.regenerate=on`, and otherwise its rows must be this suite's rows, and every
 * envelope in it must decode as its row says.
 */
class PersonalFixturesSuite extends munit.FunSuite:
  import PersonalFixturesSuite.*

  test("envelopes.json holds this suite's rows, and each decodes as its row says") {
    if sys.props.get("ankka.fixtures.regenerate").contains("on") then regenerate()
    assert(
      Files.exists(envelopesFile),
      s"$envelopesFile is missing: run with -Dankka.fixtures.regenerate=on"
    )
    val stored = readRows()
    assertEquals(
      stored.map(r => (r.name, r.project, r.subject, r.plaintext, r.expect)),
      Rows.map(r => (r.name, r.project, r.subject, r.plaintext, r.expect))
    )
    stored.foreach(check)
  }

  test("a lookup row's token is the HMAC of its plaintext under the lookup key") {
    readRows().filter(_.lookup.nonEmpty).foreach { row =>
      assertEquals(row.lookup, Some(LookupTokens.token(LookupKey, row.plaintext.getBytes(UTF_8))))
    }
  }

  private def check(row: Row): Unit =
    val result =
      try
        PersonalScope.within(FixtureKeyring, OwnProject) {
          readFromArray(row.envelope.getBytes(UTF_8))(using RawCodec)
        } match
          case Personal.Present(_, raw, _, _) => s"value:${String(raw.bytes, UTF_8)}"
          case Personal.Erased(_, _)          => "erased"
      catch case _: JsonReaderException => "corrupt"
    val expected = row.expect match
      case "value" => s"value:${row.plaintext}"
      case other   => other
    assertEquals(result, expected, s"row ${row.name}")

object PersonalFixturesSuite:

  final case class Raw(bytes: Array[Byte])
  given rawInner: JsonValueCodec[Raw] = new JsonValueCodec[Raw]:
    def nullValue: Raw                                 = null
    def decodeValue(in: JsonReader, default: Raw): Raw = Raw(in.readRawValAsBytes())
    def encodeValue(x: Raw, out: JsonWriter): Unit     = out.writeRawVal(x.bytes)
  val RawCodec: JsonValueCodec[Personal[Raw]] = PersonalCodec[Raw](rawInner)

  /** Not secrets: fixed bytes every SDK's fixture test opens the envelopes with. */
  val SubjectKey: Array[Byte] = Array.tabulate[Byte](32)(i => (i * 7 + 3).toByte)
  val LookupKey: Array[Byte]  = Array.tabulate[Byte](32)(i => (i * 11 + 5).toByte)
  val OwnProject              = "brand"
  val DestroyedSubject        = "player/forgotten"

  /** Every subject of every project opens with the one key, except the destroyed subject. */
  object FixtureKeyring extends KeyringHandle:
    def key(project: String, subject: String, create: Boolean): KeyResult =
      if subject == DestroyedSubject then KeyResult.Destroyed("erasure-fixture")
      else KeyResult.Available(SubjectKey)
    def lookupKey(project: String): Array[Byte] = LookupKey

  final case class Row(
      name: String,
      project: String,
      subject: String,
      plaintext: String,
      expect: String,
      envelope: String = "",
      lookup: Option[String] = None
  )

  /** The rows, in order. `expect` is `value`, `erased` or `corrupt`. */
  val Rows: Vector[Row] = Vector(
    Row("string", "brand", "player/8c1f", "\"ada@example.com\"", "value"),
    Row("number", "brand", "player/8c1f", "1815", "value"),
    Row(
      "record",
      "brand",
      "player/8c1f",
      """{"street":"St James's Square","city":"London"}""",
      "value"
    ),
    Row(
      "nested",
      "brand",
      "player/8c1f",
      """{"name":"Ada","address":{"city":"London","lines":["12","St James's Square"]}}""",
      "value"
    ),
    Row("unicode", "brand", "customer:u-1", "\"Händler € \\u0001\"", "value"),
    Row("lookup", "brand", "player/8c1f", "\"ada@example.com\"", "value"),
    Row("other-project", "payments", "cardholder/77a0", "\"Ada Byron\"", "value"),
    Row("erased", "brand", "player/8c1f", "", "erased"),
    Row("destroyed", "brand", DestroyedSubject, "\"gone@example.com\"", "erased"),
    Row("corrupt-tag", "brand", "player/8c1f", "\"ada@example.com\"", "corrupt"),
    Row("moved-subject", "brand", "player/other", "\"ada@example.com\"", "corrupt")
  )

  def dir: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("protocol").resolve("fixtures")))
      .map(_.resolve("protocol").resolve("fixtures").resolve("personal"))
      .getOrElse(sys.error("no protocol/fixtures above the working directory"))

  def envelopesFile: Path = dir.resolve("envelopes.json")
  def keysFile: Path      = dir.resolve("keys.json")

  private def b64(bytes: Array[Byte]) = Base64.getEncoder.encodeToString(bytes)

  private def envelopeFor(row: Row): (String, Option[String]) = row.name match
    case "erased" => (s"""{"subject":"${row.subject}","project":"${row.project}"}""", None)
    case "destroyed" =>
      val data = PersonalCipher.encrypt(
        SubjectKey,
        PersonalCipher.associated(row.subject, row.project),
        row.plaintext.getBytes(UTF_8)
      )
      (s"""{"subject":"${row.subject}","project":"${row.project}","data":"${b64(data)}"}""", None)
    case "corrupt-tag" =>
      val data = PersonalCipher.encrypt(
        SubjectKey,
        PersonalCipher.associated(row.subject, row.project),
        row.plaintext.getBytes(UTF_8)
      )
      data(data.length - 1) = (data(data.length - 1) ^ 0x01).toByte
      (s"""{"subject":"${row.subject}","project":"${row.project}","data":"${b64(data)}"}""", None)
    case "moved-subject" =>
      val data = PersonalCipher.encrypt(
        SubjectKey,
        PersonalCipher.associated("player/8c1f", row.project),
        row.plaintext.getBytes(UTF_8)
      )
      (s"""{"subject":"${row.subject}","project":"${row.project}","data":"${b64(data)}"}""", None)
    case name =>
      val lookup = name == "lookup"
      val value =
        Personal.Present(row.subject, Raw(row.plaintext.getBytes(UTF_8)), Some(row.project), lookup)
      val text = PersonalScope.within(FixtureKeyring, OwnProject, lookupAllowed = lookup) {
        String(writeToArray(value)(using RawCodec), UTF_8)
      }
      (
        text,
        if lookup then Some(LookupTokens.token(LookupKey, row.plaintext.getBytes(UTF_8))) else None
      )

  private def quote(s: String): String = String(writeToArray(s)(using TextCodec), UTF_8)
  private val TextCodec: JsonValueCodec[String] =
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[String]

  private def regenerate(): Unit =
    Files.createDirectories(dir)
    Files.writeString(
      keysFile,
      s"""{
         |  "subjectKey": "${b64(SubjectKey)}",
         |  "lookupKey": "${b64(LookupKey)}",
         |  "ownProject": "$OwnProject",
         |  "destroyedSubject": "$DestroyedSubject"
         |}
         |""".stripMargin
    ): Unit
    val items = Rows.map { row =>
      val (envelope, lookup) = envelopeFor(row)
      val lookupField        = lookup.fold("")(t => s""", "lookup": "$t"""")
      s"""  {"name": ${quote(row.name)}, "project": ${quote(row.project)}, "subject": ${quote(
          row.subject
        )}, "plaintext": ${quote(row.plaintext)}, "expect": ${quote(
          row.expect
        )}, "envelope": $envelope$lookupField}"""
    }
    Files.writeString(envelopesFile, items.mkString("[\n", ",\n", "\n]\n")): Unit

  def readRows(): Vector[Row] =
    GraphJson.parse(Files.readAllBytes(envelopesFile)).toOption.get match
      case GraphJson.Arr(items) =>
        items.map {
          case GraphJson.Obj(fields) =>
            val m = fields.toMap
            def text(k: String) = m(k) match
              case GraphJson.Str(s) => s
              case other            => sys.error(s"$k is not text: $other")
            val envelope =
              com.thinkmorestupidless.ankka.core.Contract.Canonical.render(m("envelope"))
            Row(
              text("name"),
              text("project"),
              text("subject"),
              text("plaintext"),
              text("expect"),
              envelope,
              m.get("lookup").collect { case GraphJson.Str(s) => s }
            )
          case other => sys.error(s"not a row: $other")
        }
      case other => sys.error(s"not an array: $other")
