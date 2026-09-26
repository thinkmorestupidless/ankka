import { test } from "node:test"
import assert from "node:assert/strict"
import { Duration, Instant, LocalDate, LocalDateTime } from "../src/time.ts"

test("Instant writes 0, 3, 6 or 9 fractional digits, as the value needs", () => {
  assert.equal(Instant.parse("2026-09-23T10:00:00Z").toString(), "2026-09-23T10:00:00Z")
  assert.equal(Instant.parse("2026-09-23T10:00:00.123Z").toString(), "2026-09-23T10:00:00.123Z")
  assert.equal(Instant.parse("2026-09-23T10:00:00.120Z").toString(), "2026-09-23T10:00:00.120Z")
  assert.equal(Instant.parse("2026-09-23T10:00:00.123456Z").toString(), "2026-09-23T10:00:00.123456Z")
  assert.equal(Instant.parse("2026-09-23T10:00:00.123456789Z").toString(), "2026-09-23T10:00:00.123456789Z")
  assert.equal(Instant.parse("2026-09-23T10:00:00.1Z").toString(), "2026-09-23T10:00:00.100Z")
})

test("Instant accepts an offset and normalises to UTC", () => {
  assert.equal(Instant.parse("2026-09-23T12:00:00+02:00").toString(), "2026-09-23T10:00:00Z")
  assert.equal(Instant.parse("2026-09-23T08:30:00-01:30").toString(), "2026-09-23T10:00:00Z")
})

test("Instant converts to and from Date at millisecond precision", () => {
  const i = Instant.parse("2026-09-23T10:00:00.123456Z")
  assert.equal(i.toDate().toISOString(), "2026-09-23T10:00:00.123Z")
  assert.equal(Instant.fromDate(new Date("2026-09-23T10:00:00.123Z")).toString(), "2026-09-23T10:00:00.123Z")
  assert.equal(Instant.ofEpochMilli(-1).toString(), "1969-12-31T23:59:59.999Z")
  assert.ok(Instant.parse("2026-09-23T10:00:00Z").isBefore(Instant.parse("2026-09-23T10:00:00.000000001Z")))
})

test("Instant refuses text that is not an instant", () => {
  assert.throws(() => Instant.parse("2026-09-23"), RangeError)
  assert.throws(() => Instant.parse("2026-13-40T10:00:00Z"), RangeError)
  assert.throws(() => Instant.parse("yesterday"), RangeError)
})

test("Duration prints as java.time.Duration does", () => {
  assert.equal(Duration.ZERO.toString(), "PT0S")
  assert.equal(Duration.ofMillis(1500).toString(), "PT1.5S")
  assert.equal(Duration.ofSeconds(90).toString(), "PT1M30S")
  assert.equal(Duration.ofSeconds(3600 * 2 + 60 * 30).toString(), "PT2H30M")
  assert.equal(Duration.ofSeconds(86400).toString(), "PT24H")
  assert.equal(Duration.ofNanos(1).toString(), "PT0.000000001S")
  assert.equal(Duration.ofMillis(-500).toString(), "PT-0.5S")
  assert.equal(Duration.ofMillis(-1500).toString(), "PT-1.5S")
  assert.equal(Duration.ofSeconds(-90).toString(), "PT-1M-30S")
})

test("Duration parses what it prints, and days", () => {
  for (const text of ["PT0S", "PT1.5S", "PT1M30S", "PT2H30M", "PT0.000000001S", "PT-0.5S", "PT-1.5S", "PT-1M-30S"]) {
    assert.equal(Duration.parse(text).toString(), text)
  }
  assert.equal(Duration.parse("P1DT12H").toString(), "PT36H")
  assert.equal(Duration.parse("-PT1.5S").toString(), "PT-1.5S")
  assert.equal(Duration.parse("PT1,5S").toMillis(), 1500)
  assert.equal(Duration.parse("PT1.5S").toMillis(), 1500)
  assert.throws(() => Duration.parse("PT"), RangeError)
  assert.throws(() => Duration.parse("1500"), RangeError)
  assert.throws(() => Duration.parse("P"), RangeError)
})

test("LocalDate and LocalDateTime keep their text and refuse nonsense", () => {
  assert.equal(LocalDate.parse("2026-09-23").toString(), "2026-09-23")
  assert.equal(LocalDate.of(2026, 9, 23).toString(), "2026-09-23")
  assert.equal(LocalDateTime.parse("2026-09-23T10:00:00").toString(), "2026-09-23T10:00:00")
  assert.equal(LocalDateTime.parse("2026-09-23T10:00:00.5").toString(), "2026-09-23T10:00:00.5")
  assert.throws(() => LocalDate.parse("2026-9-3"), RangeError)
  assert.throws(() => LocalDateTime.parse("2026-09-23"), RangeError)
  assert.ok(LocalDate.parse("2026-09-23").equals(LocalDate.parse("2026-09-23")))
})
