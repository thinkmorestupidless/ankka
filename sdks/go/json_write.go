package ankka

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math"
	"reflect"
	"sort"
	"strconv"
	"time"
	"unicode/utf8"
)

// The JSON writer of protocol/ENCODING.md: fields in declared order, every field written, null for an
// absent option, [] for an empty sequence, a map's keys sorted, doubles as Scala's Double.toString,
// strings escaped as jsoniter-scala escapes them. encoding/json does none of that.

// EncodingError is a value the codec cannot write, with where in the value it is.
type EncodingError struct {
	Path    string
	Message string
}

func (e *EncodingError) Error() string {
	if e.Path == "" {
		return "cannot encode: " + e.Message
	}
	return "cannot encode " + e.Path + ": " + e.Message
}

func encodeJSON(p *plan, v reflect.Value) ([]byte, error) {
	var buf bytes.Buffer
	if err := writeValue(p, v, &buf, ""); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func fail(path, format string, args ...any) error {
	return &EncodingError{Path: path, Message: fmt.Sprintf(format, args...)}
}

func writeValue(p *plan, v reflect.Value, buf *bytes.Buffer, path string) error {
	switch p.kind {
	case kindString:
		return writeString(buf, v.String(), path)
	case kindInt:
		n := v.Int()
		if n < math.MinInt32 || n > math.MaxInt32 {
			return fail(path, "%d does not fit an int (32 bits); declare the field int64 for a long", n)
		}
		buf.WriteString(strconv.FormatInt(n, 10))
	case kindLong, kindShort, kindByte:
		buf.WriteString(strconv.FormatInt(v.Int(), 10))
	case kindDouble, kindFloat:
		bits := 64
		if p.kind == kindFloat {
			bits = 32
		}
		text, err := formatDouble(v.Float(), bits)
		if err != nil {
			return fail(path, "%v", err)
		}
		buf.WriteString(text)
	case kindBool:
		if v.Bool() {
			buf.WriteString("true")
		} else {
			buf.WriteString("false")
		}
	case kindInstant:
		return writeString(buf, FormatInstant(v.Interface().(time.Time)), path)
	case kindDuration:
		return writeString(buf, FormatDuration(time.Duration(v.Int())), path)
	case kindLocalDate:
		d := v.Interface().(LocalDate)
		if d.IsZero() {
			return fail(path, "the LocalDate was never set; make one with ankka.ParseLocalDate or ankka.NewLocalDate")
		}
		return writeString(buf, d.String(), path)
	case kindLocalDateTime:
		d := v.Interface().(LocalDateTime)
		if d.IsZero() {
			return fail(path, "the LocalDateTime was never set; make one with ankka.ParseLocalDateTime or ankka.LocalDateTimeOf")
		}
		return writeString(buf, d.String(), path)
	case kindBytes:
		return writeString(buf, base64.StdEncoding.EncodeToString(v.Bytes()), path)
	case kindDone, kindUnit:
		buf.WriteString("null")
	case kindRaw:
		raw := v.Bytes()
		if len(raw) == 0 {
			buf.WriteString("null")
			return nil
		}
		if !json.Valid(raw) {
			return fail(path, "the raw JSON is not JSON: %q", raw)
		}
		if err := json.Compact(buf, raw); err != nil {
			return fail(path, "%v", err)
		}
	case kindOptional:
		if v.IsNil() {
			buf.WriteString("null")
			return nil
		}
		return writeValue(p.elem, v.Elem(), buf, path)
	case kindSequence:
		if p.length < 0 && v.IsNil() {
			buf.WriteString("[]")
			return nil
		}
		buf.WriteByte('[')
		for i := 0; i < v.Len(); i++ {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := writeValue(p.elem, v.Index(i), buf, fmt.Sprintf("%s[%d]", path, i)); err != nil {
				return err
			}
		}
		buf.WriteByte(']')
	case kindMap:
		keys := make([]string, 0, v.Len())
		values := map[string]reflect.Value{}
		for it := v.MapRange(); it.Next(); {
			k := it.Key().String()
			keys = append(keys, k)
			values[k] = it.Value()
		}
		sort.Strings(keys)
		buf.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := writeString(buf, k, path); err != nil {
				return err
			}
			buf.WriteByte(':')
			if err := writeValue(p.elem, values[k], buf, join(path, k)); err != nil {
				return err
			}
		}
		buf.WriteByte('}')
	case kindRecord:
		buf.WriteByte('{')
		if err := writeFields(p, v, buf, path, false); err != nil {
			return err
		}
		buf.WriteByte('}')
	case kindSum:
		if v.IsNil() {
			return fail(path, "a nil %s; a sum type's value is one of its cases", p.describe())
		}
		dyn := v.Elem()
		typ := dyn.Type()
		if typ.Kind() == reflect.Pointer {
			if dyn.IsNil() {
				return fail(path, "a nil %s inside %s", typ, p.describe())
			}
			dyn, typ = dyn.Elem(), typ.Elem()
		}
		c := p.sum.byType[typ]
		if c == nil {
			return fail(path, "%s is not a registered case of %s (ankka.Cases)", typ, p.describe())
		}
		buf.WriteString(`{"type":`)
		_ = writeString(buf, c.discriminator, path)
		if err := writeFields(c.record, dyn, buf, path, true); err != nil {
			return err
		}
		buf.WriteByte('}')
	case kindEnum:
		s := v.String()
		if !p.allows[s] {
			return fail(path, "%q is not a value of %s (ankka.Enumeration)", s, p.describe())
		}
		buf.WriteString(`{"type":`)
		_ = writeString(buf, s, path)
		buf.WriteByte('}')
	default:
		return fail(path, "no encoding for %s", p.describe())
	}
	return nil
}

func writeFields(p *plan, v reflect.Value, buf *bytes.Buffer, path string, continuing bool) error {
	for i, f := range p.fields {
		if i > 0 || continuing {
			buf.WriteByte(',')
		}
		_ = writeString(buf, f.name, path)
		buf.WriteByte(':')
		if err := writeValue(f.plan, v.Field(f.index), buf, join(path, f.name)); err != nil {
			return err
		}
	}
	return nil
}

func join(path, name string) string {
	if path == "" {
		return name
	}
	return path + "." + name
}

const hexDigits = "0123456789abcdef"

// writeString writes s as jsoniter-scala writes a string: `"` and `\` escaped, \b \t \n \f \r in their
// short forms, every other control character and DEL as \u00xx in lower case, everything else as
// its UTF-8 bytes. A string that is not valid UTF-8 cannot be written, as jsoniter refuses a lone
// surrogate.
func writeString(buf *bytes.Buffer, s string, path string) error {
	if !utf8.ValidString(s) {
		return fail(path, "the text %q is not valid UTF-8", s)
	}
	buf.WriteByte('"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == '"':
			buf.WriteString(`\"`)
		case c == '\\':
			buf.WriteString(`\\`)
		case c == '\b':
			buf.WriteString(`\b`)
		case c == '\t':
			buf.WriteString(`\t`)
		case c == '\n':
			buf.WriteString(`\n`)
		case c == '\f':
			buf.WriteString(`\f`)
		case c == '\r':
			buf.WriteString(`\r`)
		case c < 0x20 || c == 0x7f:
			buf.WriteString(`\u00`)
			buf.WriteByte(hexDigits[c>>4])
			buf.WriteByte(hexDigits[c&0xf])
		default:
			buf.WriteByte(c)
		}
	}
	buf.WriteByte('"')
	return nil
}
