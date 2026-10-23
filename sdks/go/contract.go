package ankka

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
)

var contractName = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]$`)

// Contract is what a topic carries, as a project declares it and a component states it: a name and
// the fingerprint of the schema document. The platform refuses, at a service's start, a side whose
// name or fingerprint is not the declared one; the name travels as every message's ce-type. Nothing
// checks a message against the schema.
//
// The fingerprint is "sha256:" and the hex SHA-256 of the document under RFC 8785 (the JSON
// Canonicalization Scheme), so whitespace and key order in a saved file do not change it and a changed
// field does.
type Contract struct {
	Name        string
	Fingerprint string
}

// ContractFromFile is the contract name of the schema document at path.
func ContractFromFile(path, name string) (Contract, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Contract{}, err
	}
	return ContractFromBytes(data, name)
}

// ContractFromBytes is the contract name of a schema document held in memory.
func ContractFromBytes(document []byte, name string) (Contract, error) {
	if !contractName.MatchString(name) {
		return Contract{}, fmt.Errorf("contract name %q is not %s", name, contractName)
	}
	fp, err := Fingerprint(document)
	if err != nil {
		return Contract{}, err
	}
	return Contract{Name: name, Fingerprint: fp}, nil
}

// Fingerprint is "sha256:" and the hex SHA-256 of document canonicalised under RFC 8785.
func Fingerprint(document []byte) (string, error) {
	raw, err := parseJSON(document)
	if err != nil {
		return "", fmt.Errorf("a schema document must be JSON: %w", err)
	}
	var buf bytes.Buffer
	if err := canonical(&buf, raw); err != nil {
		return "", err
	}
	sum := sha256.Sum256(buf.Bytes())
	return "sha256:" + hex.EncodeToString(sum[:]), nil
}

// canonical writes v under RFC 8785: object keys sorted by their UTF-16 code units, numbers as
// ECMAScript's Number.prototype.toString writes them, strings escaped as JSON.stringify escapes them.
func canonical(buf *bytes.Buffer, v any) error {
	switch x := v.(type) {
	case nil:
		buf.WriteString("null")
	case bool:
		buf.WriteString(strconv.FormatBool(x))
	case string:
		canonicalString(buf, x)
	case json.Number:
		f, err := strconv.ParseFloat(string(x), 64)
		if err != nil || math.IsInf(f, 0) {
			return fmt.Errorf("the number %s has no IEEE 754 double form", x)
		}
		buf.WriteString(ecmaNumber(f))
	case *jsonArray:
		buf.WriteByte('[')
		for i, item := range x.items {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := canonical(buf, item); err != nil {
				return err
			}
		}
		buf.WriteByte(']')
	case *jsonObject:
		keys := append([]string{}, x.keys...)
		sort.Slice(keys, func(i, j int) bool { return utf16Less(keys[i], keys[j]) })
		buf.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				buf.WriteByte(',')
			}
			canonicalString(buf, k)
			buf.WriteByte(':')
			if err := canonical(buf, x.values[k]); err != nil {
				return err
			}
		}
		buf.WriteByte('}')
	default:
		return fmt.Errorf("no canonical form for %T", v)
	}
	return nil
}

func utf16Less(a, b string) bool {
	ua, ub := utf16.Encode([]rune(a)), utf16.Encode([]rune(b))
	for i := 0; i < len(ua) && i < len(ub); i++ {
		if ua[i] != ub[i] {
			return ua[i] < ub[i]
		}
	}
	return len(ua) < len(ub)
}

func canonicalString(buf *bytes.Buffer, s string) {
	buf.WriteByte('"')
	for _, r := range s {
		switch {
		case r == '"':
			buf.WriteString(`\"`)
		case r == '\\':
			buf.WriteString(`\\`)
		case r == '\b':
			buf.WriteString(`\b`)
		case r == '\t':
			buf.WriteString(`\t`)
		case r == '\n':
			buf.WriteString(`\n`)
		case r == '\f':
			buf.WriteString(`\f`)
		case r == '\r':
			buf.WriteString(`\r`)
		case r < 0x20:
			fmt.Fprintf(buf, `\u%04x`, r)
		default:
			buf.WriteRune(r)
		}
	}
	buf.WriteByte('"')
}

// ecmaNumber is Number.prototype.toString for a finite double (ECMA-262, Number::toString).
func ecmaNumber(f float64) string {
	if f == 0 {
		return "0"
	}
	sign := ""
	if f < 0 {
		sign, f = "-", -f
	}
	e := strconv.FormatFloat(f, 'e', -1, 64) // d.ddde±xx
	at := strings.IndexByte(e, 'e')
	digits := strings.Replace(e[:at], ".", "", 1)
	exp, _ := strconv.Atoi(e[at+1:])
	k := len(digits)
	n := exp + 1 // the value is digits × 10^(n−k)
	switch {
	case k <= n && n <= 21:
		return sign + digits + strings.Repeat("0", n-k)
	case 0 < n && n <= 21:
		return sign + digits[:n] + "." + digits[n:]
	case -6 < n && n <= 0:
		return sign + "0." + strings.Repeat("0", -n) + digits
	}
	expSign := "+"
	if n-1 < 0 {
		expSign = "-"
	}
	mantissa := digits[:1]
	if k > 1 {
		mantissa += "." + digits[1:]
	}
	return sign + mantissa + "e" + expSign + strconv.Itoa(abs(n-1))
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}
