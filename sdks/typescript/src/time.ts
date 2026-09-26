// Time values with the precision the encoding needs. `Date` holds milliseconds; an `Instant` in a
// journal may carry nanoseconds, and `ENCODING.md` writes 0, 3, 6 or 9 fractional digits according to
// the value. `Temporal` would do, but it is a global only from Node 26. These are the four small
// value classes in between: immutable, comparable, convertible to and from `Date` where that makes sense.

const NANOS_PER_SECOND = 1_000_000_000
const NANOS_PER_MILLI = 1_000_000

const INSTANT = /^(-?\d{4,9})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/

/** A point on the time line, to the nanosecond, in UTC. */
export class Instant {
  /** Seconds since 1970-01-01T00:00:00Z, negative before it. */
  readonly epochSecond: number
  /** Nanoseconds within the second, 0 to 999_999_999. */
  readonly nano: number

  private constructor(epochSecond: number, nano: number) {
    this.epochSecond = epochSecond
    this.nano = nano
    Object.freeze(this)
  }

  static ofEpochSecond(epochSecond: number, nanoAdjustment = 0): Instant {
    if (!Number.isInteger(epochSecond) || !Number.isInteger(nanoAdjustment)) {
      throw new RangeError("Instant.ofEpochSecond takes integers")
    }
    const seconds = epochSecond + Math.floor(nanoAdjustment / NANOS_PER_SECOND)
    const nano = ((nanoAdjustment % NANOS_PER_SECOND) + NANOS_PER_SECOND) % NANOS_PER_SECOND
    return new Instant(seconds, nano)
  }

  static ofEpochMilli(epochMilli: number): Instant {
    if (!Number.isInteger(epochMilli)) throw new RangeError("Instant.ofEpochMilli takes an integer")
    return Instant.ofEpochSecond(Math.floor(epochMilli / 1000), (((epochMilli % 1000) + 1000) % 1000) * NANOS_PER_MILLI)
  }

  static now(): Instant {
    return Instant.ofEpochMilli(Date.now())
  }

  static fromDate(date: Date): Instant {
    const millis = date.getTime()
    if (Number.isNaN(millis)) throw new RangeError("Instant.fromDate: invalid Date")
    return Instant.ofEpochMilli(millis)
  }

  /** Parses ISO-8601 with `Z` or an offset and up to nine fractional digits. */
  static parse(text: string): Instant {
    const m = INSTANT.exec(text)
    if (!m) throw new RangeError(`not an ISO-8601 instant: ${JSON.stringify(text)}`)
    const [, year, month, day, hour, minute, second, fraction, zone] = m
    const millis = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second))
    const check = new Date(millis)
    // Date.UTC rolls an out-of-range field over (month 13 becomes January); a real instant reads back as itself.
    if (
      Number.isNaN(millis) ||
      check.getUTCMonth() !== Number(month) - 1 ||
      check.getUTCDate() !== Number(day) ||
      check.getUTCHours() !== Number(hour) ||
      check.getUTCMinutes() !== Number(minute) ||
      check.getUTCSeconds() !== Number(second)
    ) {
      throw new RangeError(`not an ISO-8601 instant: ${JSON.stringify(text)}`)
    }
    let seconds = Math.floor(millis / 1000)
    if (zone !== "Z") {
      const sign = zone!.startsWith("-") ? -1 : 1
      const offsetMinutes = sign * (Number(zone!.slice(1, 3)) * 60 + Number(zone!.slice(4, 6)))
      seconds -= offsetMinutes * 60
    }
    const nano = fraction ? Number(fraction.padEnd(9, "0")) : 0
    return new Instant(seconds, nano)
  }

  toEpochMilli(): number {
    return this.epochSecond * 1000 + Math.floor(this.nano / NANOS_PER_MILLI)
  }

  /** Millisecond precision; sub-millisecond nanoseconds are dropped. */
  toDate(): Date {
    return new Date(this.toEpochMilli())
  }

  plusMillis(millis: number): Instant {
    return Instant.ofEpochSecond(this.epochSecond, this.nano + millis * NANOS_PER_MILLI)
  }

  plus(duration: Duration): Instant {
    return Instant.ofEpochSecond(this.epochSecond + duration.seconds, this.nano + duration.nano)
  }

  isBefore(other: Instant): boolean {
    return Instant.compare(this, other) < 0
  }

  isAfter(other: Instant): boolean {
    return Instant.compare(this, other) > 0
  }

  equals(other: unknown): boolean {
    return other instanceof Instant && other.epochSecond === this.epochSecond && other.nano === this.nano
  }

  static compare(a: Instant, b: Instant): number {
    return a.epochSecond !== b.epochSecond ? a.epochSecond - b.epochSecond : a.nano - b.nano
  }

  /** ISO-8601 in UTC with `Z` and 0, 3, 6 or 9 fractional digits, as the Scala codecs write it. */
  toString(): string {
    const date = new Date(this.epochSecond * 1000)
    const base = date.toISOString().slice(0, 19) // "YYYY-MM-DDTHH:MM:SS"
    return base + fractionOf(this.nano) + "Z"
  }

  toJSON(): string {
    return this.toString()
  }
}

/** The fraction text for a nanosecond count: none, or 3, 6 or 9 digits. */
export function fractionOf(nano: number): string {
  if (nano === 0) return ""
  const digits = String(nano).padStart(9, "0")
  if (nano % NANOS_PER_MILLI === 0) return "." + digits.slice(0, 3)
  if (nano % 1000 === 0) return "." + digits.slice(0, 6)
  return "." + digits
}

const DURATION = /^([+-]?)P(?:([+-]?\d+)D)?(?:T(?:([+-]?\d+)H)?(?:([+-]?\d+)M)?(?:([+-]?\d+)(?:[.,](\d{1,9}))?S)?)?$/i

/** An amount of time, to the nanosecond, as `java.time.Duration` holds and prints it. */
export class Duration {
  /** Whole seconds, negative for a negative duration. */
  readonly seconds: number
  /** Nanoseconds within the second, 0 to 999_999_999, always non-negative (the Java convention). */
  readonly nano: number

  private constructor(seconds: number, nano: number) {
    this.seconds = seconds
    this.nano = nano
    Object.freeze(this)
  }

  static readonly ZERO = new Duration(0, 0)

  static ofSeconds(seconds: number, nanoAdjustment = 0): Duration {
    if (!Number.isInteger(seconds) || !Number.isInteger(nanoAdjustment)) {
      throw new RangeError("Duration.ofSeconds takes integers")
    }
    const s = seconds + Math.floor(nanoAdjustment / NANOS_PER_SECOND)
    const n = ((nanoAdjustment % NANOS_PER_SECOND) + NANOS_PER_SECOND) % NANOS_PER_SECOND
    return new Duration(s, n)
  }

  static ofMillis(millis: number): Duration {
    if (!Number.isInteger(millis)) throw new RangeError("Duration.ofMillis takes an integer")
    return Duration.ofSeconds(Math.floor(millis / 1000), (((millis % 1000) + 1000) % 1000) * NANOS_PER_MILLI)
  }

  static ofNanos(nanos: number): Duration {
    if (!Number.isInteger(nanos)) throw new RangeError("Duration.ofNanos takes an integer")
    return Duration.ofSeconds(0, nanos)
  }

  static ofMinutes(minutes: number): Duration {
    return Duration.ofSeconds(minutes * 60)
  }

  static ofHours(hours: number): Duration {
    return Duration.ofSeconds(hours * 3600)
  }

  /** Parses the ISO-8601 form `java.time.Duration` reads: `PT1.5S`, `PT2H30M`, `P1DT12H`, `-PT1S`. */
  static parse(text: string): Duration {
    const m = DURATION.exec(text)
    if (!m || text.length <= 2) throw new RangeError(`not an ISO-8601 duration: ${JSON.stringify(text)}`)
    const [, sign, days, hours, minutes, secs, fraction] = m
    if (days === undefined && hours === undefined && minutes === undefined && secs === undefined) {
      throw new RangeError(`not an ISO-8601 duration: ${JSON.stringify(text)}`)
    }
    let seconds = Number(days ?? 0) * 86400 + Number(hours ?? 0) * 3600 + Number(minutes ?? 0) * 60 + Number(secs ?? 0)
    let nanos = fraction ? Number(fraction.padEnd(9, "0")) : 0
    if (secs?.startsWith("-")) nanos = -nanos
    if (sign === "-") {
      seconds = -seconds
      nanos = -nanos
    }
    return Duration.ofSeconds(seconds, nanos)
  }

  toMillis(): number {
    return this.seconds * 1000 + Math.floor(this.nano / NANOS_PER_MILLI)
  }

  toNanos(): number {
    return this.seconds * NANOS_PER_SECOND + this.nano
  }

  isNegative(): boolean {
    return this.seconds < 0
  }

  isZero(): boolean {
    return this.seconds === 0 && this.nano === 0
  }

  plus(other: Duration): Duration {
    return Duration.ofSeconds(this.seconds + other.seconds, this.nano + other.nano)
  }

  equals(other: unknown): boolean {
    return other instanceof Duration && other.seconds === this.seconds && other.nano === this.nano
  }

  static compare(a: Duration, b: Duration): number {
    return a.seconds !== b.seconds ? a.seconds - b.seconds : a.nano - b.nano
  }

  /** `java.time.Duration.toString`: `PT0S`, `PT1.5S`, `PT2H30M`, `PT-0.5S`. */
  toString(): string {
    if (this.isZero()) return "PT0S"
    const total = this.seconds
    const hours = Math.trunc(total / 3600)
    const minutes = Math.trunc((total % 3600) / 60)
    let secs = total % 60
    let out = "PT"
    if (hours !== 0) out += `${hours}H`
    if (minutes !== 0) out += `${minutes}M`
    if (secs === 0 && this.nano === 0 && out.length > 2) return out
    // Java prints a negative duration with a fraction as e.g. PT-0.5S: seconds -1 and nanos 500_000_000 → "-0.5".
    if (secs < 0 && this.nano > 0) {
      if (secs === -1) out += "-0"
      else out += String(secs + 1)
    } else {
      out += String(secs)
    }
    if (this.nano > 0) {
      const n = secs < 0 ? NANOS_PER_SECOND - this.nano : this.nano
      out += "." + String(n).padStart(9, "0").replace(/0+$/, "")
    }
    return out + "S"
  }

  toJSON(): string {
    return this.toString()
  }
}

const LOCAL_DATE = /^-?\d{4,9}-\d{2}-\d{2}$/
const LOCAL_DATE_TIME = /^-?\d{4,9}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/

/** A calendar date with no zone, as its ISO-8601 text. */
export class LocalDate {
  readonly value: string

  private constructor(value: string) {
    this.value = value
    Object.freeze(this)
  }

  static parse(text: string): LocalDate {
    if (!LOCAL_DATE.test(text) || Number.isNaN(Date.parse(text + "T00:00:00Z"))) {
      throw new RangeError(`not an ISO-8601 date: ${JSON.stringify(text)}`)
    }
    return new LocalDate(text)
  }

  static of(year: number, month: number, day: number): LocalDate {
    return LocalDate.parse(`${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`)
  }

  equals(other: unknown): boolean {
    return other instanceof LocalDate && other.value === this.value
  }

  toString(): string {
    return this.value
  }

  toJSON(): string {
    return this.value
  }
}

/** A date and time with no zone, as its ISO-8601 text. */
export class LocalDateTime {
  readonly value: string

  private constructor(value: string) {
    this.value = value
    Object.freeze(this)
  }

  static parse(text: string): LocalDateTime {
    if (!LOCAL_DATE_TIME.test(text) || Number.isNaN(Date.parse(text + "Z"))) {
      throw new RangeError(`not an ISO-8601 date-time: ${JSON.stringify(text)}`)
    }
    return new LocalDateTime(text)
  }

  equals(other: unknown): boolean {
    return other instanceof LocalDateTime && other.value === this.value
  }

  toString(): string {
    return this.value
  }

  toJSON(): string {
    return this.value
  }
}
