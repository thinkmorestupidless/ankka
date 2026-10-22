package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.serialization.SerializationExtension

import java.nio.charset.StandardCharsets.UTF_8

/**
 * A replicated entity's journal records (feature 051) through the serializer the journal writes
 * them with: the installation a record names, and the five platform records a replicated entity
 * adds beside a domain event, a deletion and an expiry. The release before's bytes are pinned by
 * `JournalRecordCompatibilitySuite` in the test kit.
 */
class JournalRecordSuite extends munit.FunSuite:

  private val system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "journal-record",
    ConfigFactory
      .parseString("""
        pekko.actor.provider = local
        pekko.remote.artery.canonical.port = 0
      """)
      .withFallback(ConfigFactory.load())
  )
  private val serialization = SerializationExtension(system.classicSystem)

  override def afterAll(): Unit = system.terminate()

  private def bytesOf(record: AnyRef): Array[Byte] = serialization.serialize(record).get

  private def roundTrip(record: JournalRecord): JournalRecord =
    serialization.deserialize(bytesOf(record), classOf[JournalRecord]).get

  private def text(bytes: Array[Byte]): String = String(bytes, UTF_8)

  test("a record naming the installation that wrote it reads back naming it") {
    val written = JournalRecord
      .domain("vehicle-event", """{"step":"frame"}""".getBytes(UTF_8))
      .at(Some("leipzig"))
    val back = roundTrip(written)
    assertEquals(back.installation, Some("leipzig"))
    assertEquals(back.kind, JournalRecord.KindDomain)
    assertEquals(back.manifest, "vehicle-event")
    assertEquals(text(back.payload), """{"step":"frame"}""")
  }

  test("a record naming no installation is written without the field and reads back without it") {
    val written = JournalRecord.domain("vehicle-event", "{}".getBytes(UTF_8))
    assert(!text(bytesOf(written)).contains("installation"), text(bytesOf(written)))
    assertEquals(roundTrip(written).installation, None)
  }

  test("a record carrying who acted and the installation that forwarded it reads both back") {
    val written = JournalRecord
      .domain("vehicle-event", "{}".getBytes(UTF_8))
      .at(Some("leipzig"))
      .copy(attribution = Some("token:abc"), forwardedFrom = Some("vienna"))
    val back = roundTrip(written)
    assertEquals(back.attribution, Some("token:abc"))
    assertEquals(back.forwardedFrom, Some("vienna"))
    assertEquals(back.installation, Some("leipzig"))
  }

  test("a record with neither is written without the fields, as the release before wrote it") {
    val written = JournalRecord.domain("vehicle-event", "{}".getBytes(UTF_8)).at(Some("leipzig"))
    val bytes   = text(bytesOf(written))
    assert(!bytes.contains("attribution") && !bytes.contains("forwardedFrom"), bytes)
    val back = roundTrip(written)
    assertEquals((back.attribution, back.forwardedFrom), (None, None))
  }

  test("each platform record reads back its kind, manifest and payload") {
    val cases = Vector(
      (JournalRecord.home("leipzig"), JournalRecord.KindHome, "ankka.home", "leipzig"),
      (
        JournalRecord.replicationSet(Set("vienna", "leipzig")),
        JournalRecord.KindReplicationSet,
        "ankka.replication-set",
        """["leipzig","vienna"]"""
      ),
      (JournalRecord.handover("vienna"), JournalRecord.KindHandover, "ankka.handover", "vienna"),
      (
        JournalRecord.handoverAbandoned("vienna"),
        JournalRecord.KindHandoverAbandoned,
        "ankka.handover-abandoned",
        "vienna"
      ),
      (
        JournalRecord.conflict("bratislava"),
        JournalRecord.KindConflict,
        "ankka.conflict",
        "bratislava"
      )
    )
    cases.foreach { (record, kind, manifest, payload) =>
      val back = roundTrip(record.at(Some("leipzig")))
      assertEquals(back.kind, kind, manifest)
      assertEquals(back.manifest, manifest)
      assertEquals(text(back.payload), payload, manifest)
      assertEquals(back.installation, Some("leipzig"), manifest)
    }
  }

  test("a replication set reads back as a set, written the same whatever the order it was given") {
    val one     = JournalRecord.replicationSet(Set("vienna", "leipzig", "bratislava"))
    val another = JournalRecord.replicationSet(Set("bratislava", "vienna", "leipzig"))
    assertEquals(roundTrip(one).names, Set("vienna", "leipzig", "bratislava"))
    assertEquals(text(bytesOf(one)), text(bytesOf(another)))
    assertEquals(JournalRecord.replicationSet(Set.empty).names, Set.empty[String])
  }

  test("a home, handover, abandonment or conflict names its installation") {
    assertEquals(roundTrip(JournalRecord.home("leipzig")).named, "leipzig")
    assertEquals(roundTrip(JournalRecord.handover("vienna")).named, "vienna")
    assertEquals(roundTrip(JournalRecord.conflict("bratislava")).named, "bratislava")
  }

  test("only a conflict is not mirrored") {
    val kinds = (0 to 7).filterNot(JournalRecord.mirrored)
    assertEquals(kinds.toVector, Vector(JournalRecord.KindConflict))
  }

  test("a snapshot written without the replication fields reads back with none of them") {
    val state = StateRecord("vehicle", "{}".getBytes(UTF_8), deleted = false, expiryMillis = 0L)
    val bytes = bytesOf(state)
    Vector("home", "replicationSet", "pendingHandover", "conflict").foreach(field =>
      assert(!text(bytes).contains(field), s"$field in ${text(bytes)}")
    )
    val back = serialization.deserialize(bytes, classOf[StateRecord]).get
    assertEquals(back.home, None)
    assertEquals(back.replicationSet, None)
    assertEquals(back.pendingHandover, None)
    assertEquals(back.conflict, None)
  }

  test("a snapshot carrying a replicated entity's mode reads it back") {
    val state = StateRecord(
      "vehicle",
      "{}".getBytes(UTF_8),
      deleted = false,
      expiryMillis = 0L,
      home = Some("leipzig"),
      replicationSet = Some(Vector("leipzig", "vienna")),
      pendingHandover = Some("vienna"),
      conflict = Some("bratislava")
    )
    val back = serialization.deserialize(bytesOf(state), classOf[StateRecord]).get
    assertEquals(back.home, Some("leipzig"))
    assertEquals(back.replicationSet, Some(Vector("leipzig", "vienna")))
    assertEquals(back.pendingHandover, Some("vienna"))
    assertEquals(back.conflict, Some("bratislava"))
  }
