package ankka

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"reflect"
	"strconv"
	"strings"
)

// The JSON reader of protocol/ENCODING.md: lenient where writing is strict (fields in any order, an
// unknown field ignored, an absent optional read as nil, a bare string accepted for an enumeration),
// strict on what is required (a missing field, a fraction where a whole number is declared, a "type"
// the sum type does not have). Numbers are read as their text, so an int64 past 2⁵³ is exact.

// DecodingError is a document the codec refuses, with where in the document the refusal is.
type DecodingError struct {
	Path    string
	Message string
}

func (e *DecodingError) Error() string {
	if e.Path == "" {
		return "cannot decode: " + e.Message
	}
	return "cannot decode " + e.Path + ": " + e.Message
}

func refuse(path, format string, args ...any) error {
	return &DecodingError{Path: path, Message: fmt.Sprintf(format, args...)}
}

func decodeJSON(p *plan, data []byte, target reflect.Value) error {
	raw, err := parseJSON(data)
	if err != nil {
		return err
	}
	return readValue(p, raw, target, "")
}

func kindOfRaw(raw any) string {
	switch raw.(type) {
	case nil:
		return "null"
	case string:
		return "a string"
	case json.Number:
		return "a number"
	case bool:
		return "a boolean"
	case *jsonArray:
		return "an array"
	case *jsonObject:
		return "an object"
	}
	return fmt.Sprintf("%T", raw)
}

// rawText is a parsed value as compact JSON text: an object or an array as its own bytes compacted,
// a scalar written again.
func rawText(raw any) []byte {
	var buf bytes.Buffer
	switch r := raw.(type) {
	case nil:
		buf.WriteString("null")
	case bool:
		buf.WriteString(strconv.FormatBool(r))
	case json.Number:
		buf.WriteString(string(r))
	case string:
		_ = writeString(&buf, r, "")
	case *jsonObject:
		_ = json.Compact(&buf, r.raw)
	case *jsonArray:
		_ = json.Compact(&buf, r.raw)
	}
	return buf.Bytes()
}

func readValue(p *plan, raw any, target reflect.Value, path string) error {
	switch p.kind {
	case kindString:
		s, ok := raw.(string)
		if !ok {
			return refuse(path, "expected a string, got %s", kindOfRaw(raw))
		}
		target.SetString(s)
	case kindInt, kindLong, kindShort, kindByte:
		n, ok := raw.(json.Number)
		if !ok {
			return refuse(path, "expected a whole number, got %s", kindOfRaw(raw))
		}
		bits := map[planKind]int{kindInt: 32, kindLong: 64, kindShort: 16, kindByte: 8}[p.kind]
		v, err := strconv.ParseInt(string(n), 10, bits)
		if err != nil {
			if strings.ContainsAny(string(n), ".eE") {
				return refuse(path, "%s is not a whole number, and %s is declared", n, kindNames[p.kind])
			}
			return refuse(path, "%s does not fit %s", n, kindNames[p.kind])
		}
		target.SetInt(v)
	case kindDouble, kindFloat:
		n, ok := raw.(json.Number)
		if !ok {
			return refuse(path, "expected a number, got %s", kindOfRaw(raw))
		}
		bits := 64
		if p.kind == kindFloat {
			bits = 32
		}
		v, err := strconv.ParseFloat(string(n), bits)
		if err != nil {
			return refuse(path, "%s is not a %s", n, kindNames[p.kind])
		}
		target.SetFloat(v)
	case kindBool:
		b, ok := raw.(bool)
		if !ok {
			return refuse(path, "expected a boolean, got %s", kindOfRaw(raw))
		}
		target.SetBool(b)
	case kindInstant, kindDuration, kindLocalDate, kindLocalDateTime, kindBytes:
		s, ok := raw.(string)
		if !ok {
			return refuse(path, "expected %s as a string, got %s", kindNames[p.kind], kindOfRaw(raw))
		}
		v, err := parseText(p.kind, s)
		if err != nil {
			return refuse(path, "%v", err)
		}
		target.Set(reflect.ValueOf(v).Convert(target.Type()))
	case kindRaw:
		target.SetBytes(rawText(raw))
	case kindDone, kindUnit:
		// Any value, usually null: there is nothing to read.
	case kindOptional:
		if raw == nil {
			target.SetZero()
			return nil
		}
		inner := reflect.New(p.typ.Elem())
		if err := readValue(p.elem, raw, inner.Elem(), path); err != nil {
			return err
		}
		target.Set(inner)
	case kindSequence:
		arr, ok := raw.(*jsonArray)
		if !ok {
			return refuse(path, "expected an array, got %s", kindOfRaw(raw))
		}
		if p.length >= 0 {
			if len(arr.items) != p.length {
				return refuse(path, "expected %d items, got %d", p.length, len(arr.items))
			}
		} else {
			target.Set(reflect.MakeSlice(p.typ, len(arr.items), len(arr.items)))
		}
		for i, item := range arr.items {
			if err := readValue(p.elem, item, target.Index(i), fmt.Sprintf("%s[%d]", path, i)); err != nil {
				return err
			}
		}
	case kindMap:
		obj, ok := raw.(*jsonObject)
		if !ok {
			return refuse(path, "expected an object, got %s", kindOfRaw(raw))
		}
		m := reflect.MakeMapWithSize(p.typ, len(obj.keys))
		for _, k := range obj.keys {
			v := reflect.New(p.typ.Elem()).Elem()
			if err := readValue(p.elem, obj.values[k], v, join(path, k)); err != nil {
				return err
			}
			m.SetMapIndex(reflect.ValueOf(k).Convert(p.typ.Key()), v)
		}
		target.Set(m)
	case kindRecord:
		obj, ok := raw.(*jsonObject)
		if !ok {
			return refuse(path, "expected an object for %s, got %s", p.describe(), kindOfRaw(raw))
		}
		return readFields(p, obj, target, path)
	case kindSum:
		obj, ok := raw.(*jsonObject)
		if !ok {
			return refuse(path, "expected an object for %s, got %s", p.describe(), kindOfRaw(raw))
		}
		discriminator, _ := obj.get("type")
		name, ok := discriminator.(string)
		if !ok {
			return refuse(path, "%s needs a \"type\"", p.describe())
		}
		c := p.sum.byName[name]
		if c == nil {
			return refuse(path, "%s has no case %q", p.describe(), name)
		}
		value := reflect.New(c.record.typ)
		if err := readFields(c.record, obj, value.Elem(), path); err != nil {
			return err
		}
		if c.pointer {
			target.Set(value)
		} else {
			target.Set(value.Elem())
		}
	case kindEnum:
		var name string
		switch r := raw.(type) {
		case string:
			name = r
		case *jsonObject:
			v, _ := r.get("type")
			name, _ = v.(string)
		}
		if !p.allows[name] {
			return refuse(path, "expected one of %s for %s, got %s", quoted(p.values), p.describe(), rawText(raw))
		}
		target.SetString(name)
	default:
		return refuse(path, "no decoding for %s", p.describe())
	}
	return nil
}

func readFields(p *plan, obj *jsonObject, target reflect.Value, path string) error {
	for _, f := range p.fields {
		fieldPath := join(path, f.name)
		raw, present := obj.get(f.name)
		if !present {
			if f.plan.kind == kindOptional {
				target.Field(f.index).SetZero()
				continue
			}
			return refuse(fieldPath, "missing required field")
		}
		if err := readValue(f.plan, raw, target.Field(f.index), fieldPath); err != nil {
			return err
		}
	}
	return nil
}

func parseText(kind planKind, s string) (any, error) {
	switch kind {
	case kindInstant:
		return ParseInstant(s)
	case kindDuration:
		return ParseDuration(s)
	case kindLocalDate:
		return ParseLocalDate(s)
	case kindLocalDateTime:
		return ParseLocalDateTime(s)
	case kindBytes:
		b, err := base64.StdEncoding.DecodeString(s)
		if err != nil {
			return nil, fmt.Errorf("not base64: %v", err)
		}
		return b, nil
	}
	return nil, fmt.Errorf("no text form for %s", kindNames[kind])
}

func quoted(values []string) string {
	out := make([]string, len(values))
	for i, v := range values {
		out[i] = strconv.Quote(v)
	}
	return strings.Join(out, ", ")
}
