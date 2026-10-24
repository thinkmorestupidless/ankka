package ankka

import (
	"errors"
	"math"
	"strconv"
	"strings"
)

var errNotFinite = errors.New("NaN and infinities are not JSON numbers")

// formatDouble renders f as Scala's Double.toString does (which is Java's): the shortest digits that
// read back as f, plain with at least one fractional digit when 10⁻³ ≤ |f| < 10⁷ ("1.0", "123.456"),
// otherwise "d.dddE±n" ("1.0E10", "1.0E-5"); "-0.0" for negative zero. bits is 64 for a double and
// 32 for a float. NaN and infinities are refused: JSON has no form for them.
func formatDouble(f float64, bits int) (string, error) {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return "", errNotFinite
	}
	if f == 0 {
		if math.Signbit(f) {
			return "-0.0", nil
		}
		return "0.0", nil
	}
	// "-d.ddde±xx": the shortest digits that round-trip, and the decimal exponent.
	e := strconv.FormatFloat(f, 'e', -1, bits)
	if !strings.Contains(e, ".") {
		// Java's rule (JDK 19 and later): when one digit is the shortest, it writes the two-digit decimal
		// closest to the value instead, so the smallest double is 4.9E-324, not 5.0E-324.
		e = strconv.FormatFloat(f, 'e', 1, bits)
	}
	sign := ""
	if e[0] == '-' {
		sign, e = "-", e[1:]
	}
	at := strings.IndexByte(e, 'e')
	mantissa, exp := e[:at], e[at+1:]
	exponent, _ := strconv.Atoi(exp)
	digits := strings.TrimRight(strings.Replace(mantissa, ".", "", 1), "0")
	if digits == "" {
		digits = "0"
	}

	magnitude := math.Abs(f)
	if magnitude >= 1e-3 && magnitude < 1e7 {
		// Plain: the point sits after exponent+1 digits.
		point := exponent + 1
		var out string
		switch {
		case point <= 0:
			out = "0." + strings.Repeat("0", -point) + digits
		case point >= len(digits):
			out = digits + strings.Repeat("0", point-len(digits)) + ".0"
		default:
			out = digits[:point] + "." + digits[point:]
		}
		return sign + out, nil
	}
	frac := digits[1:]
	if frac == "" {
		frac = "0"
	}
	return sign + digits[:1] + "." + frac + "E" + strconv.Itoa(exponent), nil
}
