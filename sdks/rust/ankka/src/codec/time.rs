//! The encoding's time types, formatted by the library: `Instant` as ISO-8601 in UTC with a `Z`
//! suffix, `Duration` as an ISO-8601 duration the way `java.time.Duration` writes one, and two
//! calendar types kept as the validated text they are. No clock: a module has none of its own, and
//! asks the runtime for the time through its `Context`.

use std::fmt;
use std::str::FromStr;

use serde::de::Error as _;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

const NANOS_PER_SECOND: i64 = 1_000_000_000;

/// A text that is not the time it claims to be.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TimeError(pub String);

impl fmt::Display for TimeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for TimeError {}

fn bad(what: &str, text: &str) -> TimeError {
    TimeError(format!("not {what}: {text:?}"))
}

// ── Calendar arithmetic (proleptic Gregorian, days since 1970-01-01) ─────────

fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let m = i64::from(month);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + i64::from(day) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, month, day)
}

fn days_in_month(year: i64, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 => 29,
        _ => 28,
    }
}

fn number(text: &str, what: &str, whole: &str) -> Result<i64, TimeError> {
    if text.is_empty() || !text.bytes().all(|b| b.is_ascii_digit()) {
        return Err(bad(what, whole));
    }
    text.parse().map_err(|_| bad(what, whole))
}

/// `YYYY-MM-DD` into (year, month, day), checked.
fn parse_date(text: &str, whole: &str) -> Result<(i64, u32, u32), TimeError> {
    let what = "an ISO-8601 date";
    let (year, rest) = text
        .split_at_checked(text.len().saturating_sub(6))
        .ok_or(bad(what, whole))?;
    let rest = rest.strip_prefix('-').ok_or(bad(what, whole))?;
    let (month, day) = rest.split_once('-').ok_or(bad(what, whole))?;
    let (sign, digits) = match year.strip_prefix('-') {
        Some(d) => (-1, d),
        None => (1, year.strip_prefix('+').unwrap_or(year)),
    };
    if digits.len() < 4 || month.len() != 2 || day.len() != 2 {
        return Err(bad(what, whole));
    }
    let year = sign * number(digits, what, whole)?;
    let month = number(month, what, whole)? as u32;
    let day = number(day, what, whole)? as u32;
    if !(1..=12).contains(&month) || day == 0 || day > days_in_month(year, month) {
        return Err(bad(what, whole));
    }
    Ok((year, month, day))
}

/// `HH:MM[:SS[.fraction]]` into (seconds of the day, nanoseconds), checked.
fn parse_time(text: &str, whole: &str) -> Result<(i64, u32), TimeError> {
    let what = "an ISO-8601 time";
    let (clock, fraction) = match text.split_once(['.', ',']) {
        Some((c, f)) => (c, Some(f)),
        None => (text, None),
    };
    let parts: Vec<&str> = clock.split(':').collect();
    if !(2..=3).contains(&parts.len()) || parts.iter().any(|p| p.len() != 2) {
        return Err(bad(what, whole));
    }
    let hour = number(parts[0], what, whole)?;
    let minute = number(parts[1], what, whole)?;
    let second = if parts.len() == 3 {
        number(parts[2], what, whole)?
    } else {
        0
    };
    if hour > 23 || minute > 59 || second > 59 || (fraction.is_some() && parts.len() != 3) {
        return Err(bad(what, whole));
    }
    let nanos = match fraction {
        None => 0,
        Some(f) if f.is_empty() || f.len() > 9 => return Err(bad(what, whole)),
        Some(f) => (number(f, what, whole)? * 10_i64.pow(9 - f.len() as u32)) as u32,
    };
    Ok((hour * 3600 + minute * 60 + second, nanos))
}

// ── Instant ─────────────────────────────────────────────────────────────────

/// A point on the time-line, to the nanosecond: what `java.time.Instant` is, and what the encoding
/// writes as `"2026-09-23T10:00:00Z"`, with as many fractional digits as needed (none, 3, 6 or 9).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Instant {
    seconds: i64,
    nanos: u32,
}

impl Instant {
    /// The Unix epoch.
    pub const EPOCH: Instant = Instant {
        seconds: 0,
        nanos: 0,
    };

    /// Seconds and nanoseconds since the Unix epoch; `nanos` beyond a second carries into seconds.
    pub fn from_unix(seconds: i64, nanos: u32) -> Instant {
        let total = i128::from(seconds) * i128::from(NANOS_PER_SECOND) + i128::from(nanos);
        Instant::from_total_nanos(total)
    }

    /// Milliseconds since the Unix epoch, as the runtime's clock is given.
    pub fn from_epoch_millis(millis: i64) -> Instant {
        Instant::from_total_nanos(i128::from(millis) * 1_000_000)
    }

    fn from_total_nanos(total: i128) -> Instant {
        let per = i128::from(NANOS_PER_SECOND);
        Instant {
            seconds: total.div_euclid(per) as i64,
            nanos: total.rem_euclid(per) as u32,
        }
    }

    /// Whole seconds since the Unix epoch (floor).
    pub fn unix_seconds(&self) -> i64 {
        self.seconds
    }

    /// The nanoseconds within the second, 0 to 999,999,999.
    pub fn subsec_nanos(&self) -> u32 {
        self.nanos
    }

    /// Milliseconds since the Unix epoch (floor).
    pub fn epoch_millis(&self) -> i64 {
        self.seconds * 1000 + i64::from(self.nanos / 1_000_000)
    }

    /// This instant moved by `duration`.
    pub fn plus(&self, duration: Duration) -> Instant {
        Instant::from_total_nanos(self.total_nanos() + duration.total_nanos())
    }

    fn total_nanos(&self) -> i128 {
        i128::from(self.seconds) * i128::from(NANOS_PER_SECOND) + i128::from(self.nanos)
    }

    /// Reads the encoding's form, and any ISO-8601 instant with 0 to 9 fractional digits and a `Z`
    /// or numeric offset.
    pub fn parse(text: &str) -> Result<Instant, TimeError> {
        let what = "an ISO-8601 instant";
        let (date, rest) = text.split_once('T').ok_or(bad(what, text))?;
        let (year, month, day) = parse_date(date, text)?;
        let (clock, offset) = if let Some(clock) = rest.strip_suffix('Z') {
            (clock, 0)
        } else {
            let at = rest.rfind(['+', '-']).ok_or(bad(what, text))?;
            let (clock, zone) = rest.split_at(at);
            let sign = if zone.starts_with('-') { -1 } else { 1 };
            let (h, m) = zone[1..].split_once(':').ok_or(bad(what, text))?;
            let seconds = number(h, what, text)? * 3600 + number(m, what, text)? * 60;
            (clock, sign * seconds)
        };
        let (of_day, nanos) = parse_time(clock, text)?;
        let seconds = days_from_civil(year, month, day) * 86_400 + of_day - offset;
        Ok(Instant { seconds, nanos })
    }
}

impl fmt::Display for Instant {
    /// As `java.time.Instant.toString`, which is what the encoding stores.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let days = self.seconds.div_euclid(86_400);
        let of_day = self.seconds.rem_euclid(86_400);
        let (year, month, day) = civil_from_days(days);
        let (hour, minute, second) = (of_day / 3600, of_day % 3600 / 60, of_day % 60);
        if (0..=9999).contains(&year) {
            write!(f, "{year:04}")?;
        } else {
            write!(f, "{year:+}")?;
        }
        write!(f, "-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}")?;
        let n = self.nanos;
        if n == 0 {
        } else if n.is_multiple_of(1_000_000) {
            write!(f, ".{:03}", n / 1_000_000)?;
        } else if n.is_multiple_of(1000) {
            write!(f, ".{:06}", n / 1000)?;
        } else {
            write!(f, ".{n:09}")?;
        }
        f.write_str("Z")
    }
}

impl FromStr for Instant {
    type Err = TimeError;
    fn from_str(s: &str) -> Result<Instant, TimeError> {
        Instant::parse(s)
    }
}

impl From<std::time::SystemTime> for Instant {
    fn from(time: std::time::SystemTime) -> Instant {
        match time.duration_since(std::time::UNIX_EPOCH) {
            Ok(after) => Instant::from_unix(after.as_secs() as i64, after.subsec_nanos()),
            Err(before) => {
                let d = before.duration();
                Instant::from_total_nanos(-(d.as_nanos() as i128))
            }
        }
    }
}

impl Serialize for Instant {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.collect_str(self)
    }
}

impl<'de> Deserialize<'de> for Instant {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Instant, D::Error> {
        let text = String::deserialize(deserializer)?;
        Instant::parse(&text).map_err(D::Error::custom)
    }
}

// ── Duration ────────────────────────────────────────────────────────────────

/// An amount of time to the nanosecond, positive or negative: what `java.time.Duration` is, written
/// as its ISO-8601 form (`"PT1.5S"`, `"PT1H30M"`) inside JSON and as whole milliseconds when it is a
/// payload of its own (`duration-millis`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Duration {
    /// Seconds, rounded down: `nanos` is always 0 to 999,999,999, as Java keeps it.
    seconds: i64,
    nanos: u32,
}

impl Duration {
    /// No time at all.
    pub const ZERO: Duration = Duration {
        seconds: 0,
        nanos: 0,
    };

    fn from_total_nanos(total: i128) -> Duration {
        let per = i128::from(NANOS_PER_SECOND);
        Duration {
            seconds: total.div_euclid(per) as i64,
            nanos: total.rem_euclid(per) as u32,
        }
    }

    fn total_nanos(&self) -> i128 {
        i128::from(self.seconds) * i128::from(NANOS_PER_SECOND) + i128::from(self.nanos)
    }

    /// A duration of `millis` milliseconds.
    pub fn of_millis(millis: i64) -> Duration {
        Duration::from_total_nanos(i128::from(millis) * 1_000_000)
    }

    /// A duration of `seconds` seconds.
    pub fn of_seconds(seconds: i64) -> Duration {
        Duration { seconds, nanos: 0 }
    }

    /// A duration of `minutes` minutes.
    pub fn of_minutes(minutes: i64) -> Duration {
        Duration::of_seconds(minutes * 60)
    }

    /// A duration of `hours` hours.
    pub fn of_hours(hours: i64) -> Duration {
        Duration::of_seconds(hours * 3600)
    }

    /// A duration of `nanos` nanoseconds.
    pub fn of_nanos(nanos: i64) -> Duration {
        Duration::from_total_nanos(i128::from(nanos))
    }

    /// The whole milliseconds in this duration, rounded towards negative infinity as Java's
    /// `toMillis` does for the stored form.
    pub fn to_millis(&self) -> i64 {
        (self.total_nanos().div_euclid(1_000_000)) as i64
    }

    /// Whether this is a negative amount of time.
    pub fn is_negative(&self) -> bool {
        self.seconds < 0
    }

    /// Reads an ISO-8601 duration as `java.time.Duration.parse` does: `PnDTnHnMn.nS`, each part
    /// optional and signed, the whole optionally negated.
    pub fn parse(text: &str) -> Result<Duration, TimeError> {
        let what = "an ISO-8601 duration";
        let upper = text.to_ascii_uppercase();
        let (negate, rest) = match upper.as_bytes().first() {
            Some(b'-') => (true, &upper[1..]),
            Some(b'+') => (false, &upper[1..]),
            _ => (false, &upper[..]),
        };
        let rest = rest.strip_prefix('P').ok_or(bad(what, text))?;
        let (days, time) = match rest.split_once('T') {
            Some((d, t)) => (d, Some(t)),
            None => (rest, None),
        };
        let mut total: i128 = 0;
        let mut any = false;
        let signed = |part: &str| -> Result<(bool, String), TimeError> {
            match part.strip_prefix('-') {
                Some(p) => Ok((true, p.to_string())),
                None => Ok((false, part.strip_prefix('+').unwrap_or(part).to_string())),
            }
        };
        if !days.is_empty() {
            let d = days.strip_suffix('D').ok_or(bad(what, text))?;
            let (neg, digits) = signed(d)?;
            let n = i128::from(number(&digits, what, text)?);
            total += if neg { -n } else { n } * 86_400 * i128::from(NANOS_PER_SECOND);
            any = true;
        }
        if let Some(mut time) = time {
            if time.is_empty() {
                return Err(bad(what, text));
            }
            for (unit, scale) in [('H', 3600_i128), ('M', 60)] {
                if let Some((part, after)) = time.split_once(unit) {
                    let (neg, digits) = signed(part)?;
                    let n = i128::from(number(&digits, what, text)?);
                    total += if neg { -n } else { n } * scale * i128::from(NANOS_PER_SECOND);
                    time = after;
                    any = true;
                }
            }
            if !time.is_empty() {
                let part = time.strip_suffix('S').ok_or(bad(what, text))?;
                let (neg, digits) = signed(part)?;
                let (whole, fraction) = match digits.split_once(['.', ',']) {
                    Some((w, f)) => (w.to_string(), f.to_string()),
                    None => (digits.clone(), String::new()),
                };
                if fraction.len() > 9 {
                    return Err(bad(what, text));
                }
                let mut n = i128::from(number(&whole, what, text)?) * i128::from(NANOS_PER_SECOND);
                if !fraction.is_empty() {
                    let f = i128::from(number(&fraction, what, text)?);
                    n += f * 10_i128.pow(9 - fraction.len() as u32);
                }
                total += if neg { -n } else { n };
                any = true;
            }
        }
        if !any {
            return Err(bad(what, text));
        }
        Ok(Duration::from_total_nanos(if negate {
            -total
        } else {
            total
        }))
    }
}

impl fmt::Display for Duration {
    /// As `java.time.Duration.toString`, character for character.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if *self == Duration::ZERO {
            return f.write_str("PT0S");
        }
        let nanos = i64::from(self.nanos);
        let effective = if self.seconds < 0 && nanos > 0 {
            self.seconds + 1
        } else {
            self.seconds
        };
        let hours = effective / 3600;
        let minutes = (effective % 3600) / 60;
        let secs = effective % 60;
        let mut out = String::from("PT");
        if hours != 0 {
            out.push_str(&format!("{hours}H"));
        }
        if minutes != 0 {
            out.push_str(&format!("{minutes}M"));
        }
        if secs == 0 && nanos == 0 && out.len() > 2 {
            return f.write_str(&out);
        }
        if self.seconds < 0 && nanos > 0 && secs == 0 {
            out.push_str("-0");
        } else {
            out.push_str(&secs.to_string());
        }
        if nanos > 0 {
            let at = out.len();
            let tail = if self.seconds < 0 {
                2 * NANOS_PER_SECOND - nanos
            } else {
                nanos + NANOS_PER_SECOND
            };
            out.push_str(tail.to_string().trim_end_matches('0'));
            out.replace_range(at..at + 1, ".");
        }
        out.push('S');
        f.write_str(&out)
    }
}

impl FromStr for Duration {
    type Err = TimeError;
    fn from_str(s: &str) -> Result<Duration, TimeError> {
        Duration::parse(s)
    }
}

impl From<std::time::Duration> for Duration {
    fn from(d: std::time::Duration) -> Duration {
        Duration::from_total_nanos(d.as_nanos() as i128)
    }
}

impl Serialize for Duration {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.collect_str(self)
    }
}

impl<'de> Deserialize<'de> for Duration {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Duration, D::Error> {
        let text = String::deserialize(deserializer)?;
        Duration::parse(&text).map_err(D::Error::custom)
    }
}

// ── Calendar values ─────────────────────────────────────────────────────────

/// A date without a time or a zone, `"2026-09-23"`: kept as the validated text the encoding
/// stores.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct LocalDate(String);

impl LocalDate {
    /// Reads `YYYY-MM-DD`, refusing a date the calendar does not have.
    pub fn parse(text: &str) -> Result<LocalDate, TimeError> {
        parse_date(text, text)?;
        Ok(LocalDate(text.to_string()))
    }

    /// The date as it is written.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A date and time without a zone, `"2026-09-23T10:00:00"`: kept as the validated text the
/// encoding stores.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct LocalDateTime(String);

impl LocalDateTime {
    /// Reads `YYYY-MM-DDTHH:MM[:SS[.fraction]]`.
    pub fn parse(text: &str) -> Result<LocalDateTime, TimeError> {
        let (date, time) = text
            .split_once('T')
            .ok_or(bad("an ISO-8601 local date-time", text))?;
        parse_date(date, text)?;
        parse_time(time, text)?;
        Ok(LocalDateTime(text.to_string()))
    }

    /// The date-time as it is written.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

macro_rules! text_backed {
    ($t:ident) => {
        impl fmt::Display for $t {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(&self.0)
            }
        }
        impl FromStr for $t {
            type Err = TimeError;
            fn from_str(s: &str) -> Result<$t, TimeError> {
                $t::parse(s)
            }
        }
        impl Serialize for $t {
            fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
                serializer.serialize_str(&self.0)
            }
        }
        impl<'de> Deserialize<'de> for $t {
            fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<$t, D::Error> {
                let text = String::deserialize(deserializer)?;
                $t::parse(&text).map_err(D::Error::custom)
            }
        }
    };
}

text_backed!(LocalDate);
text_backed!(LocalDateTime);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_instant_is_written_with_as_many_fraction_digits_as_it_needs() {
        for text in [
            "2026-09-23T10:00:00Z",
            "2026-09-23T10:00:00.123Z",
            "2026-09-23T10:00:00.123456Z",
            "2026-09-23T10:00:00.123456789Z",
            "1969-12-31T23:59:59.999Z",
            "2000-02-29T00:00:00Z",
        ] {
            assert_eq!(Instant::parse(text).unwrap().to_string(), text);
        }
        // Read with any number of digits; written as Java writes it.
        assert_eq!(
            Instant::parse("2026-09-23T10:00:00.5Z")
                .unwrap()
                .to_string(),
            "2026-09-23T10:00:00.500Z"
        );
        assert_eq!(
            Instant::parse("2026-09-23T12:00:00+02:00")
                .unwrap()
                .to_string(),
            "2026-09-23T10:00:00Z"
        );
    }

    #[test]
    fn an_instant_knows_the_epoch() {
        assert_eq!(
            Instant::parse("1970-01-01T00:00:00Z").unwrap(),
            Instant::EPOCH
        );
        let t = Instant::from_epoch_millis(1_790_157_600_000);
        assert_eq!(t.to_string(), "2026-09-23T10:00:00Z");
        assert_eq!(t.epoch_millis(), 1_790_157_600_000);
    }

    #[test]
    fn an_instant_refuses_what_is_not_one() {
        for text in [
            "2026-02-30T00:00:00Z",
            "2026-09-23 10:00:00Z",
            "2026-09-23T25:00:00Z",
            "x",
        ] {
            assert!(Instant::parse(text).is_err(), "{text}");
        }
    }

    #[test]
    fn a_duration_is_written_as_java_writes_it() {
        let cases = [
            (Duration::ZERO, "PT0S"),
            (Duration::of_millis(1500), "PT1.5S"),
            (Duration::of_seconds(90), "PT1M30S"),
            (Duration::of_hours(1), "PT1H"),
            (Duration::of_seconds(3661), "PT1H1M1S"),
            (Duration::of_millis(-1500), "PT-1.5S"),
            (Duration::of_millis(-500), "PT-0.5S"),
            (Duration::of_seconds(-90), "PT-1M-30S"),
            (Duration::of_nanos(1), "PT0.000000001S"),
        ];
        for (d, text) in cases {
            assert_eq!(d.to_string(), text);
            assert_eq!(Duration::parse(text).unwrap(), d, "{text}");
        }
    }

    #[test]
    fn a_duration_reads_days_and_signs() {
        assert_eq!(Duration::parse("P1D").unwrap(), Duration::of_hours(24));
        assert_eq!(Duration::parse("-PT1M").unwrap(), Duration::of_minutes(-1));
        assert_eq!(Duration::parse("PT0.5S").unwrap().to_millis(), 500);
        assert!(Duration::parse("PT").is_err());
        assert!(Duration::parse("1S").is_err());
    }

    #[test]
    fn calendar_values_are_validated_text() {
        assert_eq!(
            LocalDate::parse("2026-09-23").unwrap().as_str(),
            "2026-09-23"
        );
        assert!(LocalDate::parse("2026-13-01").is_err());
        assert!(LocalDateTime::parse("2026-09-23T10:00:00.25").is_ok());
        assert!(LocalDateTime::parse("2026-09-23").is_err());
    }
}
