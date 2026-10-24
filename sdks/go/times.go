package ankka

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// The time values of protocol/ENCODING.md. An instant is a time.Time, written in UTC with a "Z" and
// 0, 3, 6 or 9 fractional digits as the Scala codecs write java.time.Instant; a duration is a
// time.Duration, written as java.time.Duration.toString writes it ("PT1.5S"); a date and a date-time
// with no zone are LocalDate and LocalDateTime, which the standard library has no type for.

// FormatInstant renders t as the encoding's instant: UTC, "Z", and as many fractional digits as the
// nanoseconds need (none, 3, 6 or 9). The monotonic clock reading, if any, plays no part.
func FormatInstant(t time.Time) string {
	u := t.UTC()
	return u.Format("2006-01-02T15:04:05") + fractionOf(u.Nanosecond()) + "Z"
}

func fractionOf(nano int) string {
	if nano == 0 {
		return ""
	}
	digits := fmt.Sprintf("%09d", nano)
	switch {
	case nano%1_000_000 == 0:
		return "." + digits[:3]
	case nano%1_000 == 0:
		return "." + digits[:6]
	default:
		return "." + digits
	}
}

var instantPattern = regexp.MustCompile(`^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,9}))?(Z|[+-]\d{2}:\d{2})$`)

// ParseInstant reads an ISO-8601 instant with "Z" or an offset and up to nine fractional digits,
// returning it in UTC.
func ParseInstant(text string) (time.Time, error) {
	m := instantPattern.FindStringSubmatch(text)
	if m == nil {
		return time.Time{}, fmt.Errorf("not an ISO-8601 instant: %q", text)
	}
	n := func(s string) int { v, _ := strconv.Atoi(s); return v }
	year, month, day, hour, minute, second := n(m[1]), n(m[2]), n(m[3]), n(m[4]), n(m[5]), n(m[6])
	nano := 0
	if m[7] != "" {
		nano = n(m[7] + strings.Repeat("0", 9-len(m[7])))
	}
	t := time.Date(year, time.Month(month), day, hour, minute, second, nano, time.UTC)
	// time.Date normalises an out-of-range field (month 13 becomes January); a real instant reads back as itself.
	if t.Year() != year || int(t.Month()) != month || t.Day() != day || t.Hour() != hour || t.Minute() != minute || t.Second() != second {
		return time.Time{}, fmt.Errorf("not an ISO-8601 instant: %q", text)
	}
	if zone := m[8]; zone != "Z" {
		offset := (n(zone[1:3])*60 + n(zone[4:6])) * 60
		if zone[0] == '-' {
			offset = -offset
		}
		t = t.Add(-time.Duration(offset) * time.Second)
	}
	return t, nil
}

// FormatDuration renders d as java.time.Duration.toString does: "PT0S", "PT1.5S", "PT2H30M",
// "PT-0.5S". Hours are never folded into days.
func FormatDuration(d time.Duration) string {
	if d == 0 {
		return "PT0S"
	}
	// Java holds whole seconds (floored) and a non-negative nanosecond part.
	total := int64(d)
	secs := total / 1e9
	nano := total % 1e9
	if nano < 0 {
		nano += 1e9
		secs--
	}
	hours := secs / 3600
	minutes := (secs % 3600) / 60
	s := secs % 60
	var b strings.Builder
	b.WriteString("PT")
	if hours != 0 {
		fmt.Fprintf(&b, "%dH", hours)
	}
	if minutes != 0 {
		fmt.Fprintf(&b, "%dM", minutes)
	}
	if s == 0 && nano == 0 && b.Len() > 2 {
		return b.String()
	}
	if s < 0 && nano > 0 {
		if s == -1 {
			b.WriteString("-0")
		} else {
			fmt.Fprintf(&b, "%d", s+1)
		}
	} else {
		fmt.Fprintf(&b, "%d", s)
	}
	if nano > 0 {
		n := nano
		if s < 0 {
			n = 1e9 - nano
		}
		b.WriteString("." + strings.TrimRight(fmt.Sprintf("%09d", n), "0"))
	}
	b.WriteString("S")
	return b.String()
}

var durationPattern = regexp.MustCompile(`(?i)^([+-]?)P(?:([+-]?\d+)D)?(?:T(?:([+-]?\d+)H)?(?:([+-]?\d+)M)?(?:([+-]?\d+)(?:[.,](\d{1,9}))?S)?)?$`)

// ParseDuration reads the ISO-8601 forms java.time.Duration.parse reads: "PT1.5S", "PT2H30M",
// "P1DT12H", "-PT1S". A day is 24 hours, as Java has it; years, months and weeks are refused, as Java
// refuses them.
func ParseDuration(text string) (time.Duration, error) {
	m := durationPattern.FindStringSubmatch(text)
	if m == nil || len(text) <= 2 || (m[2] == "" && m[3] == "" && m[4] == "" && m[5] == "") || strings.HasSuffix(strings.ToUpper(text), "T") {
		return 0, fmt.Errorf("not an ISO-8601 duration: %q (a duration is days, hours, minutes and seconds, as PT1.5S)", text)
	}
	part := func(s string, unit int64) (int64, error) {
		if s == "" {
			return 0, nil
		}
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return 0, fmt.Errorf("not an ISO-8601 duration: %q", text)
		}
		return v * unit, nil
	}
	var total int64
	for _, p := range []struct {
		s    string
		unit int64
	}{{m[2], 86400}, {m[3], 3600}, {m[4], 60}, {m[5], 1}} {
		v, err := part(p.s, p.unit)
		if err != nil {
			return 0, err
		}
		total += v
	}
	const maxSeconds = int64(1<<63-1) / 1e9
	if total > maxSeconds || total < -maxSeconds {
		return 0, fmt.Errorf("duration %q is beyond what time.Duration holds (about 292 years)", text)
	}
	nanos := total * 1e9
	if m[6] != "" {
		f, _ := strconv.ParseInt(m[6]+strings.Repeat("0", 9-len(m[6])), 10, 64)
		if strings.HasPrefix(m[5], "-") {
			f = -f
		}
		nanos += f
	}
	if m[1] == "-" {
		nanos = -nanos
	}
	return time.Duration(nanos), nil
}

// LocalDate is a date with no time and no zone, "2026-09-23", as java.time.LocalDate.
type LocalDate struct{ text string }

var localDatePattern = regexp.MustCompile(`^(-?\d{4,9})-(\d{2})-(\d{2})$`)

// ParseLocalDate reads "YYYY-MM-DD", refusing a date that does not exist.
func ParseLocalDate(text string) (LocalDate, error) {
	m := localDatePattern.FindStringSubmatch(text)
	if m == nil {
		return LocalDate{}, fmt.Errorf("not an ISO-8601 date: %q", text)
	}
	y, _ := strconv.Atoi(m[1])
	mo, _ := strconv.Atoi(m[2])
	d, _ := strconv.Atoi(m[3])
	t := time.Date(y, time.Month(mo), d, 0, 0, 0, 0, time.UTC)
	if t.Year() != y || int(t.Month()) != mo || t.Day() != d {
		return LocalDate{}, fmt.Errorf("not an ISO-8601 date: %q", text)
	}
	return LocalDate{text: text}, nil
}

// NewLocalDate is the date in the year, month and day given.
func NewLocalDate(year int, month time.Month, day int) LocalDate {
	return LocalDateOf(time.Date(year, month, day, 0, 0, 0, 0, time.UTC))
}

// LocalDateOf is t's date, in t's own location.
func LocalDateOf(t time.Time) LocalDate {
	return LocalDate{text: fmt.Sprintf("%04d-%02d-%02d", t.Year(), int(t.Month()), t.Day())}
}

// String is the ISO-8601 text; the zero LocalDate is the empty string, which the codec refuses to write.
func (d LocalDate) String() string { return d.text }

// IsZero reports whether d was never set.
func (d LocalDate) IsZero() bool { return d.text == "" }

// Time is midnight at the start of d in UTC.
func (d LocalDate) Time() time.Time {
	t, _ := time.Parse("2006-01-02", d.text)
	return t
}

// LocalDateTime is a date and a time of day with no zone, "2026-09-23T10:00:00", as
// java.time.LocalDateTime.
type LocalDateTime struct{ text string }

var localDateTimePattern = regexp.MustCompile(`^(-?\d{4,9})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$`)

// ParseLocalDateTime reads "YYYY-MM-DDTHH:MM[:SS[.fraction]]", refusing a time that does not exist.
// The value keeps the text it was read from, so it is written back exactly.
func ParseLocalDateTime(text string) (LocalDateTime, error) {
	m := localDateTimePattern.FindStringSubmatch(text)
	if m == nil {
		return LocalDateTime{}, fmt.Errorf("not an ISO-8601 date-time: %q", text)
	}
	n := func(s string) int { v, _ := strconv.Atoi(s); return v }
	y, mo, d, h, mi, s := n(m[1]), n(m[2]), n(m[3]), n(m[4]), n(m[5]), n(m[6])
	t := time.Date(y, time.Month(mo), d, h, mi, s, 0, time.UTC)
	if t.Year() != y || int(t.Month()) != mo || t.Day() != d || t.Hour() != h || t.Minute() != mi || t.Second() != s {
		return LocalDateTime{}, fmt.Errorf("not an ISO-8601 date-time: %q", text)
	}
	return LocalDateTime{text: text}, nil
}

// LocalDateTimeOf is t's wall clock in t's own location, written as java.time.LocalDateTime.toString
// writes it: seconds omitted when they and the fraction are zero, the fraction in 3, 6 or 9 digits.
func LocalDateTimeOf(t time.Time) LocalDateTime {
	text := fmt.Sprintf("%04d-%02d-%02dT%02d:%02d", t.Year(), int(t.Month()), t.Day(), t.Hour(), t.Minute())
	if t.Second() != 0 || t.Nanosecond() != 0 {
		text += fmt.Sprintf(":%02d", t.Second()) + fractionOf(t.Nanosecond())
	}
	return LocalDateTime{text: text}
}

// String is the ISO-8601 text.
func (d LocalDateTime) String() string { return d.text }

// IsZero reports whether d was never set.
func (d LocalDateTime) IsZero() bool { return d.text == "" }

// Time is d's wall clock read as UTC.
func (d LocalDateTime) Time() time.Time {
	t, err := time.Parse("2006-01-02T15:04:05.999999999", d.text)
	if err != nil {
		t, _ = time.Parse("2006-01-02T15:04", d.text)
	}
	return t
}
