package com.thinkmorestupidless.ankka.core.personal

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, ErrorCode, Serializer}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate

final case class Address(street: String, city: String)
final case class PlayerRegistered(
    playerId: String,
    email: Personal[String],
    name: Personal[String],
    dateOfBirth: Personal[LocalDate],
    address: Option[Personal[Address]],
    aliases: Vector[Personal[String]],
    currency: String,
    registeredAt: Long
)
final case class Transfer(from: Personal[String], to: Personal[String], amount: BigDecimal)

class PersonalCodecSuite extends munit.FunSuite:

  private val serializer = Codecs.serializer[PlayerRegistered]("player-registered")
  private val transfers  = Codecs.serializer[Transfer]("transfer")

  private def registered(subject: String = "player/8c1f") = PlayerRegistered(
    playerId = "8c1f",
    email = Personal.present(subject, "ada@example.com"),
    name = Personal.present(subject, "Ada Byron"),
    dateOfBirth = Personal.present(subject, LocalDate.of(1815, 12, 10)),
    address = Some(Personal.present(subject, Address("St James's Square", "London"))),
    aliases = Vector(Personal.present(subject, "Ada Lovelace")),
    currency = "GBP",
    registeredAt = 1_700_000_000_000L
  )

  private def text(bytes: Array[Byte]) = String(bytes, UTF_8)

  test(
    "a record with personal fields derives with Codecs.make and no declaration, and round-trips"
  ) {
    val keyring = TestKeyring()
    PersonalScope.within(keyring, "brand") {
      val bytes = serializer.toBytes(registered())
      assertEquals(serializer.fromBytes(bytes), registered())
    }
  }

  test("every personal field is an envelope, at any depth, and every other field is as written") {
    val json = PersonalScope.within(TestKeyring(), "brand")(text(serializer.toBytes(registered())))
    Vector("ada@example.com", "Ada Byron", "1815", "St James", "London", "Lovelace").foreach {
      plain =>
        assert(!json.contains(plain), s"$plain appears in $json")
    }
    assert(json.contains("\"currency\":\"GBP\""), json)
    assert(json.contains("\"registeredAt\":1700000000000"), json)
    assert(json.contains("\"playerId\":\"8c1f\""), json)
    val envelope = """\{"subject":"player/8c1f","project":"brand","data":"[A-Za-z0-9+/=]+"\}""".r
    assertEquals(envelope.findAllIn(json).size, 5, json)
  }

  test("one subject key per subject, made on the first write") {
    val keyring = TestKeyring()
    PersonalScope.within(keyring, "brand")(serializer.toBytes(registered())): Unit
    assertEquals(keyring.created, 1)
  }

  test("two subjects in one event: erasing one leaves the other readable") {
    val keyring = TestKeyring()
    val value = Transfer(
      Personal.present("player/a", "Alice"),
      Personal.present("player/b", "Bob"),
      BigDecimal(10)
    )
    val bytes = PersonalScope.within(keyring, "brand")(transfers.toBytes(value))
    keyring.destroy("brand", "player/a")
    val read = PersonalScope.within(keyring, "brand")(transfers.fromBytes(bytes))
    assertEquals(read.from, Personal.Erased("player/a"))
    assertEquals(read.to.toOption, Some("Bob"))
    assertEquals(read.amount, BigDecimal(10))
  }

  test(
    "a destroyed key decodes as erased, never as an error, and every other field is as written"
  ) {
    val keyring = TestKeyring()
    val bytes   = PersonalScope.within(keyring, "brand")(serializer.toBytes(registered()))
    keyring.destroy("brand", "player/8c1f")
    val read = PersonalScope.within(keyring, "brand")(serializer.fromBytes(bytes))
    assertEquals(read.email, Personal.Erased("player/8c1f"))
    assertEquals(read.address, Some(Personal.Erased("player/8c1f")))
    assertEquals(read.currency, "GBP")
    assertEquals(read.registeredAt, 1_700_000_000_000L)
  }

  test(
    "a present value for an erased subject cannot be written, and the refusal names the subject"
  ) {
    val keyring = TestKeyring()
    keyring.destroy("brand", "player/8c1f")
    val error = intercept[CommandError](
      PersonalScope.within(keyring, "brand")(serializer.toBytes(registered()))
    )
    assert(error.message.contains("player/8c1f"), error.message)
    assert(error.message.contains("erased"), error.message)
  }

  test("an erased value can always be written, and writes the subject-only form") {
    val keyring = TestKeyring()
    keyring.destroy("brand", "player/8c1f")
    val value =
      Transfer(Personal.Erased("player/8c1f"), Personal.Erased("player/8c1f"), BigDecimal(1))
    val json = PersonalScope.within(keyring, "brand")(text(transfers.toBytes(value)))
    assert(json.contains("""{"subject":"player/8c1f","project":"brand"}"""), json)
  }

  test("the erased form decodes with no scope at all") {
    val bytes =
      """{"from":{"subject":"player/a","project":"brand"},"to":{"subject":"player/b","project":"brand"},"amount":3}"""
        .getBytes(UTF_8)
    assertEquals(transfers.fromBytes(bytes).from, Personal.Erased("player/a"))
  }

  test("a present value written outside any scope is refused as unavailable, naming the keyring") {
    val error = intercept[CommandError](serializer.toBytes(registered()))
    assertEquals(error.code, ErrorCode.Unavailable)
    assert(error.message.contains("keyring"), error.message)
  }

  test("an envelope with data read outside any scope is refused as unavailable") {
    val bytes = PersonalScope.within(TestKeyring(), "brand")(serializer.toBytes(registered()))
    val error = intercept[CommandError](serializer.fromBytes(bytes))
    assertEquals(error.code, ErrorCode.Unavailable)
  }

  test(
    "an envelope moved to another subject or another project fails as corrupt, never as erased"
  ) {
    val keyring = TestKeyring()
    PersonalScope.within(keyring, "brand")(
      transfers.toBytes(
        Transfer(Personal.present("player/b", "x"), Personal.present("player/b", "x"), 0)
      )
    ): Unit
    val bytes = PersonalScope.within(keyring, "brand")(
      transfers.toBytes(
        Transfer(Personal.present("player/a", "Alice"), Personal.present("player/a", "Alice"), 0)
      )
    )
    val moved = text(bytes).replace("\"subject\":\"player/a\"", "\"subject\":\"player/b\"")
    val failure = intercept[JsonReaderException](
      PersonalScope.within(keyring, "brand")(transfers.fromBytes(moved.getBytes(UTF_8)))
    )
    assert(failure.getMessage.contains("corrupt"), failure.getMessage)
  }

  test("an envelope with an unknown key is refused") {
    val bytes =
      """{"from":{"subject":"player/a","project":"brand","extra":1},"to":{"subject":"b","project":"brand"},"amount":0}"""
    intercept[JsonReaderException](transfers.fromBytes(bytes.getBytes(UTF_8))): Unit
  }

  test(
    "a lookup token is written only where the scope allows it, and is the same for the same value"
  ) {
    final case class Row(email: Personal[String])
    val rows    = Codecs.serializer[Row]("row")
    val keyring = TestKeyring()
    val value   = Row(Personal.lookup("player/a", "ada@example.com"))
    val journal = PersonalScope.within(keyring, "brand")(text(rows.toBytes(value)))
    assert(!journal.contains("lookup"), journal)
    val first =
      PersonalScope.within(keyring, "brand", lookupAllowed = true)(text(rows.toBytes(value)))
    val second =
      PersonalScope.within(keyring, "brand", lookupAllowed = true)(text(rows.toBytes(value)))
    val token = """"lookup":"([0-9a-f]{64})"""".r
    val a     = token.findFirstMatchIn(first).map(_.group(1))
    assert(a.isDefined, first)
    assertEquals(a, token.findFirstMatchIn(second).map(_.group(1)))
    assertEquals(a, Some(LookupTokens.forText(keyring.lookupKey("brand"), "ada@example.com")))
    assertEquals(
      PersonalScope.within(keyring, "brand")(rows.fromBytes(first.getBytes(UTF_8))),
      value
    )
  }

  test("a value decoded from another project's envelope is written again under that project") {
    final case class Row(name: Personal[String])
    val rows    = Codecs.serializer[Row]("row")
    val keyring = TestKeyring()
    val brand =
      PersonalScope.within(keyring, "brand")(rows.toBytes(Row(Personal.present("player/a", "Ada"))))
    val read  = PersonalScope.within(keyring, "payments")(rows.fromBytes(brand))
    val again = PersonalScope.within(keyring, "payments")(text(rows.toBytes(read)))
    assert(again.contains("\"project\":\"brand\""), again)
    keyring.destroy("brand", "player/a")
    assertEquals(
      PersonalScope.within(keyring, "payments")(rows.fromBytes(again.getBytes(UTF_8))).name,
      Personal.Erased("player/a")
    )
  }

  test("the textual form names the subject and nothing else") {
    val p = Personal.present("player/8c1f", "ada@example.com")
    assertEquals(p.toString, "Personal(player/8c1f)")
    assertEquals(Personal.Erased("player/8c1f").toString, "Personal(player/8c1f)")
    assert(!registered().toString.contains("ada@example.com"))
  }

  test("a data subject is 1 to 253 characters of letters, digits and . _ - / :") {
    assert(DataSubject.problems("player/8c1f:x_y-z.1").isEmpty)
    assert(DataSubject.problems("").nonEmpty)
    assert(DataSubject.problems("a" * 254).nonEmpty)
    assert(DataSubject.problems("a b").nonEmpty)
    assert(DataSubject.problems("a\"b").nonEmpty)
    intercept[IllegalArgumentException](Personal.present("", "x")): Unit
  }

  test("equality is the subject and the value, whatever project or lookup the value carries") {
    assertEquals(Personal.Present("s", 1, Some("brand"), lookup = true), Personal.present("s", 1))
    assertNotEquals(Personal.present("s", 1), Personal.present("t", 1))
    assertEquals(Personal.Erased("s", Some("brand")), Personal.Erased("s"))
  }
