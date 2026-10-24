package ankka

import (
	"encoding/json"
	"fmt"
	"strconv"
	"unicode/utf16"
	"unicode/utf8"
)

// A small JSON parser that keeps what encoding/json's decoding into `any` loses: the order of an
// object's keys, a number's text, and each object's and array's own bytes, so a json.RawMessage
// field reads back exactly as it was written.

type jsonObject struct {
	keys   []string
	values map[string]any
	raw    []byte
}

func (o *jsonObject) get(key string) (any, bool) {
	v, ok := o.values[key]
	return v, ok
}

type jsonArray struct {
	items []any
	raw   []byte
}

type parser struct {
	data []byte
	pos  int
}

// parseJSON reads one JSON document into *jsonObject, *jsonArray, json.Number, string, bool and nil.
func parseJSON(data []byte) (any, error) {
	p := &parser{data: data}
	p.space()
	v, err := p.value(0)
	if err != nil {
		return nil, refuse("", "not JSON: %v", err)
	}
	p.space()
	if p.pos != len(p.data) {
		return nil, refuse("", "not JSON: text after the document at byte %d", p.pos)
	}
	return v, nil
}

const maxDepth = 512

func (p *parser) space() {
	for p.pos < len(p.data) {
		switch p.data[p.pos] {
		case ' ', '\t', '\n', '\r':
			p.pos++
		default:
			return
		}
	}
}

func (p *parser) errorf(format string, args ...any) error {
	return fmt.Errorf("at byte %d: %s", p.pos, fmt.Sprintf(format, args...))
}

func (p *parser) value(depth int) (any, error) {
	if depth > maxDepth {
		return nil, p.errorf("nested more than %d deep", maxDepth)
	}
	if p.pos >= len(p.data) {
		return nil, p.errorf("unexpected end")
	}
	switch c := p.data[p.pos]; {
	case c == '{':
		return p.object(depth)
	case c == '[':
		return p.array(depth)
	case c == '"':
		return p.str()
	case c == 't':
		return true, p.literal("true")
	case c == 'f':
		return false, p.literal("false")
	case c == 'n':
		return nil, p.literal("null")
	case c == '-' || (c >= '0' && c <= '9'):
		return p.number()
	default:
		return nil, p.errorf("unexpected %q", c)
	}
}

func (p *parser) literal(word string) error {
	if len(p.data)-p.pos < len(word) || string(p.data[p.pos:p.pos+len(word)]) != word {
		return p.errorf("expected %s", word)
	}
	p.pos += len(word)
	return nil
}

func (p *parser) object(depth int) (any, error) {
	start := p.pos
	p.pos++
	o := &jsonObject{values: map[string]any{}}
	p.space()
	if p.pos < len(p.data) && p.data[p.pos] == '}' {
		p.pos++
		o.raw = p.data[start:p.pos]
		return o, nil
	}
	for {
		p.space()
		if p.pos >= len(p.data) || p.data[p.pos] != '"' {
			return nil, p.errorf("expected a key")
		}
		key, err := p.str()
		if err != nil {
			return nil, err
		}
		p.space()
		if p.pos >= len(p.data) || p.data[p.pos] != ':' {
			return nil, p.errorf("expected ':'")
		}
		p.pos++
		p.space()
		v, err := p.value(depth + 1)
		if err != nil {
			return nil, err
		}
		if _, dup := o.values[key]; !dup {
			o.keys = append(o.keys, key)
		}
		o.values[key] = v
		p.space()
		if p.pos >= len(p.data) {
			return nil, p.errorf("unexpected end in an object")
		}
		if p.data[p.pos] == ',' {
			p.pos++
			continue
		}
		if p.data[p.pos] == '}' {
			p.pos++
			o.raw = p.data[start:p.pos]
			return o, nil
		}
		return nil, p.errorf("expected ',' or '}'")
	}
}

func (p *parser) array(depth int) (any, error) {
	start := p.pos
	p.pos++
	a := &jsonArray{items: []any{}}
	p.space()
	if p.pos < len(p.data) && p.data[p.pos] == ']' {
		p.pos++
		a.raw = p.data[start:p.pos]
		return a, nil
	}
	for {
		p.space()
		v, err := p.value(depth + 1)
		if err != nil {
			return nil, err
		}
		a.items = append(a.items, v)
		p.space()
		if p.pos >= len(p.data) {
			return nil, p.errorf("unexpected end in an array")
		}
		if p.data[p.pos] == ',' {
			p.pos++
			continue
		}
		if p.data[p.pos] == ']' {
			p.pos++
			a.raw = p.data[start:p.pos]
			return a, nil
		}
		return nil, p.errorf("expected ',' or ']'")
	}
}

func (p *parser) number() (any, error) {
	start := p.pos
	if p.data[p.pos] == '-' {
		p.pos++
	}
	digits := func() int {
		n := 0
		for p.pos < len(p.data) && p.data[p.pos] >= '0' && p.data[p.pos] <= '9' {
			p.pos++
			n++
		}
		return n
	}
	intStart := p.pos
	if digits() == 0 {
		return nil, p.errorf("expected a digit")
	}
	if p.data[intStart] == '0' && p.pos-intStart > 1 {
		return nil, p.errorf("a number with a leading zero")
	}
	if p.pos < len(p.data) && p.data[p.pos] == '.' {
		p.pos++
		if digits() == 0 {
			return nil, p.errorf("expected a digit after '.'")
		}
	}
	if p.pos < len(p.data) && (p.data[p.pos] == 'e' || p.data[p.pos] == 'E') {
		p.pos++
		if p.pos < len(p.data) && (p.data[p.pos] == '+' || p.data[p.pos] == '-') {
			p.pos++
		}
		if digits() == 0 {
			return nil, p.errorf("expected a digit in an exponent")
		}
	}
	return json.Number(p.data[start:p.pos]), nil
}

func (p *parser) str() (string, error) {
	p.pos++ // the opening quote
	var out []byte
	for {
		if p.pos >= len(p.data) {
			return "", p.errorf("unexpected end in a string")
		}
		c := p.data[p.pos]
		switch {
		case c == '"':
			p.pos++
			if !utf8.Valid(out) {
				return "", p.errorf("a string that is not UTF-8")
			}
			return string(out), nil
		case c < 0x20:
			return "", p.errorf("a control character in a string")
		case c == '\\':
			if p.pos+1 >= len(p.data) {
				return "", p.errorf("unexpected end in an escape")
			}
			e := p.data[p.pos+1]
			p.pos += 2
			switch e {
			case '"', '\\', '/':
				out = append(out, e)
			case 'b':
				out = append(out, '\b')
			case 'f':
				out = append(out, '\f')
			case 'n':
				out = append(out, '\n')
			case 'r':
				out = append(out, '\r')
			case 't':
				out = append(out, '\t')
			case 'u':
				r, err := p.hex4()
				if err != nil {
					return "", err
				}
				if utf16.IsSurrogate(r) {
					if p.pos+6 <= len(p.data) && p.data[p.pos] == '\\' && p.data[p.pos+1] == 'u' {
						p.pos += 2
						r2, err := p.hex4()
						if err != nil {
							return "", err
						}
						r = utf16.DecodeRune(r, r2)
					} else {
						r = utf8.RuneError
					}
					if r == utf8.RuneError {
						return "", p.errorf("an unpaired surrogate in a string")
					}
				}
				out = utf8.AppendRune(out, r)
			default:
				return "", p.errorf("the escape \\%c", e)
			}
		default:
			out = append(out, c)
			p.pos++
		}
	}
}

func (p *parser) hex4() (rune, error) {
	if p.pos+4 > len(p.data) {
		return 0, p.errorf("a short \\u escape")
	}
	n, err := strconv.ParseUint(string(p.data[p.pos:p.pos+4]), 16, 32)
	if err != nil {
		return 0, p.errorf("a \\u escape that is not hex")
	}
	p.pos += 4
	return rune(n), nil
}
