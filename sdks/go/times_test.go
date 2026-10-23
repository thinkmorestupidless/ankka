package ankka

import (
	"strings"
	"testing"
	"time"
)

func TestInstantsAreWrittenInUTCWithThreeSixOrNineDigits(t *testing.T) {
	base := time.Date(2026, 9, 23, 10, 0, 0, 0, time.UTC)
	cases := []struct {
		at   time.Time
		text string
	}{
		{base, "2026-09-23T10:00:00Z"},
		{base.Add(123 * time.Millisecond), "2026-09-23T10:00:00.123Z"},
		{base.Add(123456 * time.Microsecond), "2026-09-23T10:00:00.123456Z"},
		{base.Add(123456789), "2026-09-23T10:00:00.123456789Z"},
		{base.Add(100 * time.Millisecond), "2026-09-23T10:00:00.100Z"},
		{base.In(time.FixedZone("x", 2*3600)), "2026-09-23T10:00:00Z"},
	}
	for _, c := range cases {
		if got := FormatInstant(c.at); got != c.text {
			t.Errorf("FormatInstant(%v) = %q, want %q", c.at, got, c.text)
		}
		back, err := ParseInstant(c.text)
		if err != nil || !back.Equal(c.at) {
			t.Errorf("ParseInstant(%q) = %v, %v; want %v", c.text, back, err, c.at)
		}
	}
}

func TestAnInstantWithAMonotonicReadingIsWrittenAsTheWallClock(t *testing.T) {
	now := time.Now()
	back, err := ParseInstant(FormatInstant(now))
	if err != nil || !back.Equal(now) {
		t.Fatalf("round trip of time.Now(): %v, %v; want %v", back, err, now)
	}
}

func TestInstantsWithAnOffsetAreReadAsUTC(t *testing.T) {
	got, err := ParseInstant("2026-09-23T12:30:00+02:30")
	if err != nil || FormatInstant(got) != "2026-09-23T10:00:00Z" {
		t.Fatalf("got %v, %v", got, err)
	}
}

func TestInstantsThatDoNotExistAreRefused(t *testing.T) {
	for _, text := range []string{"2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-09-23", "2026-09-23T10:00:00", "yesterday", "2026-09-23T10:00:00.1234567890Z"} {
		if _, err := ParseInstant(text); err == nil {
			t.Errorf("ParseInstant(%q) was accepted", text)
		}
	}
}

func TestDurationsAreWrittenAsJavaWritesThem(t *testing.T) {
	cases := []struct {
		d    time.Duration
		text string
	}{
		{0, "PT0S"},
		{1500 * time.Millisecond, "PT1.5S"},
		{-500 * time.Millisecond, "PT-0.5S"},
		{-1500 * time.Millisecond, "PT-1.5S"},
		{150 * time.Minute, "PT2H30M"},
		{3661 * time.Second, "PT1H1M1S"},
		{25 * time.Hour, "PT25H"},
		{time.Nanosecond, "PT0.000000001S"},
		{-90 * time.Second, "PT-1M-30S"},
		{-time.Hour, "PT-1H"},
		{30 * time.Second, "PT30S"},
		{time.Hour + 500*time.Millisecond, "PT1H0.5S"},
	}
	for _, c := range cases {
		if got := FormatDuration(c.d); got != c.text {
			t.Errorf("FormatDuration(%v) = %q, want %q", c.d, got, c.text)
		}
		back, err := ParseDuration(c.text)
		if err != nil || back != c.d {
			t.Errorf("ParseDuration(%q) = %v, %v; want %v", c.text, back, err, c.d)
		}
	}
}

func TestDurationsReadEveryFormJavaReads(t *testing.T) {
	cases := map[string]time.Duration{
		"P1DT12H": 36 * time.Hour,
		"-PT1S":   -time.Second,
		"pt2m":    2 * time.Minute,
		"PT1,5S":  1500 * time.Millisecond,
		"P2D":     48 * time.Hour,
	}
	for text, want := range cases {
		if got, err := ParseDuration(text); err != nil || got != want {
			t.Errorf("ParseDuration(%q) = %v, %v; want %v", text, got, err, want)
		}
	}
}

func TestDurationsWithADatePartAreRefusedNamingTheValue(t *testing.T) {
	for _, text := range []string{"P1Y", "P1M", "P1W", "PT", "P", "1.5S", ""} {
		_, err := ParseDuration(text)
		if err == nil {
			t.Errorf("ParseDuration(%q) was accepted", text)
			continue
		}
		if !strings.Contains(err.Error(), "duration") {
			t.Errorf("ParseDuration(%q): %v does not say what it expected", text, err)
		}
	}
}

func TestLocalDatesAndDateTimes(t *testing.T) {
	d, err := ParseLocalDate("2026-09-23")
	if err != nil || d.String() != "2026-09-23" || !d.Time().Equal(time.Date(2026, 9, 23, 0, 0, 0, 0, time.UTC)) {
		t.Fatalf("got %v, %v", d, err)
	}
	if NewLocalDate(2026, time.February, 3).String() != "2026-02-03" {
		t.Error("NewLocalDate")
	}
	if _, err := ParseLocalDate("2026-02-30"); err == nil {
		t.Error("a date that does not exist was accepted")
	}
	dt, err := ParseLocalDateTime("2026-09-23T10:15:30.5")
	if err != nil || dt.String() != "2026-09-23T10:15:30.5" || dt.Time().Nanosecond() != 500_000_000 {
		t.Fatalf("got %v, %v", dt, err)
	}
	for in, want := range map[time.Time]string{
		time.Date(2026, 9, 23, 10, 0, 0, 0, time.UTC):           "2026-09-23T10:00",
		time.Date(2026, 9, 23, 10, 0, 1, 0, time.UTC):           "2026-09-23T10:00:01",
		time.Date(2026, 9, 23, 10, 0, 1, 120_000_000, time.UTC): "2026-09-23T10:00:01.120",
	} {
		if got := LocalDateTimeOf(in).String(); got != want {
			t.Errorf("LocalDateTimeOf(%v) = %q, want %q", in, got, want)
		}
	}
	if _, err := ParseLocalDateTime("2026-09-23T25:00"); err == nil {
		t.Error("an hour that does not exist was accepted")
	}
}
