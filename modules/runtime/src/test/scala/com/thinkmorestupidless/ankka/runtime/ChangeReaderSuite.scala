package com.thinkmorestupidless.ankka.runtime

import java.nio.charset.StandardCharsets.UTF_8

/**
 * How a view or a consumer reads a replicated entity's journal (feature 051): a platform record is
 * no change, and an event names the installation that wrote it and whether that is this one.
 */
class ChangeReaderSuite extends munit.FunSuite:

  private val event = JournalRecord.domain("vehicle-event", """{"step":"frame"}""".getBytes(UTF_8))

  private val platform = Vector(
    JournalRecord.home("leipzig"),
    JournalRecord.replicationSet(Set("leipzig", "vienna")),
    JournalRecord.handover("vienna"),
    JournalRecord.handoverAbandoned("vienna"),
    JournalRecord.conflict("bratislava")
  )

  test("a home, a replication set, a handover, an abandoned handover and a conflict are no change") {
    Vector(ChangeReader.journal, ChangeReader.journalAt(Some("vienna"))).foreach { reader =>
      platform.foreach(record =>
        assertEquals(reader.read(record.at(Some("leipzig"))), SourceChange.Skip, record.manifest)
      )
    }
  }

  test("an event, a deletion and an expiry read as they always did") {
    val reader = ChangeReader.journalAt(Some("vienna"))
    reader.read(event) match
      case SourceChange.Changed(payload, standing) =>
        assertEquals(payload.manifest, "vehicle-event")
        assertEquals(String(payload.data, UTF_8), """{"step":"frame"}""")
        assertEquals(standing, None)
      case other => fail(s"read as $other")
    assertEquals(reader.read(JournalRecord.deleted), SourceChange.Deleted)
    assertEquals(reader.read(JournalRecord.expiry(1L)), SourceChange.Skip)
  }

  test("an event mirrored from another installation names it and is not local") {
    val reader   = ChangeReader.journalAt(Some("vienna"))
    val mirrored = event.at(Some("leipzig"))
    assertEquals(reader.installation(mirrored), Some("leipzig"))
    assert(!reader.local(mirrored))
  }

  test("an event written here names this installation and is local") {
    val reader = ChangeReader.journalAt(Some("vienna"))
    assertEquals(reader.installation(event.at(Some("vienna"))), Some("vienna"))
    assert(reader.local(event.at(Some("vienna"))))
    // A record from before replication names nobody: a follower stamps what it mirrors, so it
    // was written here.
    assertEquals(reader.installation(event), Some("vienna"))
    assert(reader.local(event))
  }

  test("an entity that is not replicated names no installation, and every change is local") {
    assertEquals(ChangeReader.journal.installation(event), None)
    assert(ChangeReader.journal.local(event))
    assert(ChangeReader.journal.local(event.at(Some("leipzig"))))
  }
