package ankka

import (
	"fmt"
	"math"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Content types of protocol/ENCODING.md.
const (
	JSONContent   = "application/json"
	TextContent   = "text/plain"
	BinaryContent = "application/octet-stream"
)

// Codec turns a value of T into the bytes of a Payload and back, under a manifest and a content type.
// The default codec for a type is CodecFor's; a developer may supply their own with CustomCodec, and
// then portability across languages is their contract.
type Codec[T any] interface {
	Manifest() string
	ContentType() string
	Encode(value T) ([]byte, error)
	Decode(data []byte) (T, error)
}

// CodecOption changes a default codec.
type CodecOption func(*codecSettings)

type codecSettings struct {
	manifest string
	edge     bool
}

// Manifest names a default JSON codec's manifest, which is what the journal stores beside each value;
// without it the manifest is the type's name.
func Manifest(name string) CodecOption {
	return func(s *codecSettings) { s.manifest = name }
}

// CodecFor is the default codec for T: the text codec for a top-level string, integer, double,
// boolean or time.Duration ("duration-millis"); the binary codec for []byte, Done, Unit and a
// top-level pointer ("option[<inner>]"); JSON under the type's name for everything else. A type the
// encoding cannot describe gives a codec whose every call fails naming why; a service or a test kit
// that uses it refuses to start.
func CodecFor[T any](opts ...CodecOption) Codec[T] {
	var s codecSettings
	for _, o := range opts {
		o(&s)
	}
	return &defaultCodec[T]{inner: codecAt(reflect.TypeFor[T](), s.manifest, true, s.edge)}
}

// edgeCodec is CodecFor at an endpoint, where no codec seals: a personal field is its value with its
// subject beside it, {"subject", "value"}, or {"subject"} once erased.
func edgeCodec(s *codecSettings) { s.edge = true }

// CustomCodec is a codec the developer writes: what the value's bytes are is theirs to say.
func CustomCodec[T any](manifest, contentType string, encode func(T) ([]byte, error), decode func([]byte) (T, error)) Codec[T] {
	return &customCodec[T]{manifest: manifest, contentType: contentType, encode: encode, decode: decode}
}

type customCodec[T any] struct {
	manifest, contentType string
	encode                func(T) ([]byte, error)
	decode                func([]byte) (T, error)
}

func (c *customCodec[T]) Manifest() string              { return c.manifest }
func (c *customCodec[T]) ContentType() string           { return c.contentType }
func (c *customCodec[T]) Encode(v T) ([]byte, error)    { return c.encode(v) }
func (c *customCodec[T]) Decode(data []byte) (T, error) { return c.decode(data) }
func (c *customCodec[T]) codecProblems() []string {
	var out []string
	if strings.TrimSpace(c.manifest) == "" {
		out = append(out, "a custom codec has an empty manifest")
	}
	if c.encode == nil || c.decode == nil {
		out = append(out, fmt.Sprintf("the custom codec %q has no encode or no decode function", c.manifest))
	}
	return out
}

// home, when set, is the installation whose keyring keeps the keys of a replicated entity's personal
// fields (FR-032); see atHome.
type defaultCodec[T any] struct {
	inner *typeCodec
	home  string
}

func (c *defaultCodec[T]) Manifest() string    { return c.inner.manifest }
func (c *defaultCodec[T]) ContentType() string { return c.inner.contentType }
func (c *defaultCodec[T]) Encode(v T) ([]byte, error) {
	return c.inner.encodeAt(reflect.ValueOf(&v).Elem(), c.home)
}
func (c *defaultCodec[T]) Decode(data []byte) (T, error) {
	var v T
	err := c.inner.decodeAt(data, reflect.ValueOf(&v).Elem(), c.home)
	return v, err
}
func (c *defaultCodec[T]) codecProblems() []string { return c.inner.problems }
func (c *defaultCodec[T]) holdsPersonal() bool     { return c.inner.personal }

// atHome is c sealing and opening personal fields with the keys of home's keyring: a replicated
// entity's home as its records name it (FR-032), "" for this installation's own. A codec that seals
// nothing, or one the developer wrote, is c.
func atHome[T any](c Codec[T], home string) Codec[T] {
	if d, ok := c.(*defaultCodec[T]); ok && d.home != home && d.inner.sealAt != nil {
		return &defaultCodec[T]{inner: d.inner, home: home}
	}
	return c
}

// problematic is a codec that can say what is wrong with it before it is used.
type problematic interface{ codecProblems() []string }

// CodecProblems is what is wrong with a codec, if anything: a default codec for a type the encoding
// cannot describe, or a custom codec with no manifest. A service refuses to start on any.
func CodecProblems[T any](c Codec[T]) []string {
	if c == nil {
		return []string{"no codec"}
	}
	if p, ok := c.(problematic); ok {
		return p.codecProblems()
	}
	return nil
}

// typeCodec is a default codec without its type parameter, which the server and the test kits hold.
type typeCodec struct {
	manifest    string
	contentType string
	problems    []string
	personal    bool // the type holds a personal field
	encode      func(reflect.Value) ([]byte, error)
	decode      func([]byte, reflect.Value) error
	// sealAt and openAt are encode and decode with a replicated entity's home named (FR-032), for
	// a codec that seals; nil for one that does not.
	sealAt func(reflect.Value, string) ([]byte, error)
	openAt func([]byte, reflect.Value, string) error
}

// encodeAt is encode with the keys of home's keyring; "" is this installation's.
func (c *typeCodec) encodeAt(v reflect.Value, home string) ([]byte, error) {
	if home != "" && c.sealAt != nil {
		return c.sealAt(v, home)
	}
	return c.encode(v)
}

// decodeAt is decode with the keys of home's keyring; "" is this installation's.
func (c *typeCodec) decodeAt(data []byte, target reflect.Value, home string) error {
	if home != "" && c.openAt != nil {
		return c.openAt(data, target, home)
	}
	return c.decode(data, target)
}

func broken(manifest string, problems []string) *typeCodec {
	err := fmt.Errorf("%s", strings.Join(problems, "; "))
	return &typeCodec{
		manifest: manifest, contentType: JSONContent, problems: problems,
		encode: func(reflect.Value) ([]byte, error) { return nil, err },
		decode: func([]byte, reflect.Value) error { return err },
	}
}

func codecForType(t reflect.Type, manifest string, topLevel bool) *typeCodec {
	return codecAt(t, manifest, topLevel, false)
}

// codecAt is codecForType, at the edge when edge is set.
func codecAt(t reflect.Type, manifest string, topLevel, edge bool) *typeCodec {
	if manifest == "" {
		if c := textOrBinaryCodec(t); c != nil {
			return c
		}
	}
	if topLevel && manifest == "" && t.Kind() == reflect.Pointer {
		inner := codecAt(t.Elem(), "", true, edge)
		if len(inner.problems) > 0 {
			return inner
		}
		c := optionCodec(t, inner)
		c.personal = inner.personal
		return c
	}
	p, problems := planFor(t)
	if manifest == "" {
		manifest = t.Name()
	}
	if manifest == "" {
		problems = append(problems, fmt.Sprintf("%s has no name to use as its manifest; give it one with ankka.Manifest(...)", t))
	}
	if len(problems) > 0 {
		return broken(manifest, problems)
	}
	if edge {
		return &typeCodec{
			manifest: manifest, contentType: JSONContent, personal: p.personal,
			encode: func(v reflect.Value) ([]byte, error) { return encodeJSON(p, v) },
			decode: func(data []byte, target reflect.Value) error { return decodeJSON(p, data, target) },
		}
	}
	// A codec seals: a personal field is its envelope, bound to the manifest.
	return &typeCodec{
		manifest: manifest, contentType: JSONContent, personal: p.personal,
		encode: func(v reflect.Value) ([]byte, error) { return sealJSON(p, v, manifest, "") },
		decode: func(data []byte, target reflect.Value) error { return openJSON(p, data, target, manifest, "") },
		sealAt: func(v reflect.Value, home string) ([]byte, error) { return sealJSON(p, v, manifest, home) },
		openAt: func(data []byte, target reflect.Value, home string) error {
			return openJSON(p, data, target, manifest, home)
		},
	}
}

// textOrBinaryCodec is the primitive codec a top-level type selects, or nil for a JSON one.
func textOrBinaryCodec(t reflect.Type) *typeCodec {
	switch t {
	case durationType:
		return primitiveCodecs["duration-millis"].as(t)
	case doneType:
		return primitiveCodecs["done"].as(t)
	case unitType:
		return primitiveCodecs["unit"].as(t)
	}
	if isEnumeration(t) {
		return nil // an enumeration is JSON even at the top level, as the Scala codecs write it
	}
	switch t.Kind() {
	case reflect.String:
		return primitiveCodecs["string"].as(t)
	case reflect.Int, reflect.Int32:
		return primitiveCodecs["int"].as(t)
	case reflect.Int64:
		return primitiveCodecs["long"].as(t)
	case reflect.Int16:
		return primitiveCodecs["short"].as(t)
	case reflect.Int8:
		return primitiveCodecs["byte"].as(t)
	case reflect.Float64:
		return primitiveCodecs["double"].as(t)
	case reflect.Float32:
		return primitiveCodecs["float"].as(t)
	case reflect.Bool:
		return primitiveCodecs["boolean"].as(t)
	case reflect.Slice:
		if t.Elem().Kind() == reflect.Uint8 {
			return primitiveCodecs["bytes"].as(t)
		}
	}
	return nil
}

// A primitive codec works on reflect values of any type with the right kind, so a named string type
// is a "string" payload as a plain string is.
type primitive struct {
	manifest    string
	contentType string
	encode      func(reflect.Value) ([]byte, error)
	decode      func([]byte, reflect.Value) error
}

func (p primitive) as(reflect.Type) *typeCodec {
	return &typeCodec{manifest: p.manifest, contentType: p.contentType, encode: p.encode, decode: p.decode}
}

func textInteger(manifest string, bits int) primitive {
	return primitive{
		manifest: manifest, contentType: TextContent,
		encode: func(v reflect.Value) ([]byte, error) {
			n := v.Int()
			if bits < 64 && (n < -(1<<(bits-1)) || n > 1<<(bits-1)-1) {
				return nil, fail("", "%d does not fit %s", n, manifest)
			}
			return []byte(strconv.FormatInt(n, 10)), nil
		},
		decode: func(data []byte, target reflect.Value) error {
			s := strings.TrimSpace(string(data))
			n, err := strconv.ParseInt(s, 10, bits)
			if err != nil {
				return refuse("", "not a whole number for %s: %q", manifest, s)
			}
			target.SetInt(n)
			return nil
		},
	}
}

func textFloat(manifest string, bits int) primitive {
	return primitive{
		manifest: manifest, contentType: TextContent,
		encode: func(v reflect.Value) ([]byte, error) {
			f := v.Float()
			switch {
			case math.IsNaN(f):
				return []byte("NaN"), nil
			case math.IsInf(f, 1):
				return []byte("Infinity"), nil
			case math.IsInf(f, -1):
				return []byte("-Infinity"), nil
			}
			s, err := formatDouble(f, bits)
			return []byte(s), err
		},
		decode: func(data []byte, target reflect.Value) error {
			s := strings.TrimSpace(string(data))
			var f float64
			switch s {
			case "NaN":
				f = math.NaN()
			case "Infinity":
				f = math.Inf(1)
			case "-Infinity":
				f = math.Inf(-1)
			default:
				var err error
				if f, err = strconv.ParseFloat(s, bits); err != nil || strings.ContainsAny(s, "xXpP_") || strings.EqualFold(s, "inf") || strings.EqualFold(s, "+inf") || strings.EqualFold(s, "-inf") || strings.EqualFold(s, "nan") {
					return refuse("", "not a number for %s: %q", manifest, s)
				}
			}
			target.SetFloat(f)
			return nil
		},
	}
}

var primitiveCodecs = map[string]primitive{
	"string": {
		manifest: "string", contentType: TextContent,
		encode: func(v reflect.Value) ([]byte, error) { return []byte(v.String()), nil },
		decode: func(data []byte, target reflect.Value) error { target.SetString(string(data)); return nil },
	},
	"int":    textInteger("int", 32),
	"long":   textInteger("long", 64),
	"short":  textInteger("short", 16),
	"byte":   textInteger("byte", 8),
	"double": textFloat("double", 64),
	"float":  textFloat("float", 32),
	"boolean": {
		manifest: "boolean", contentType: TextContent,
		encode: func(v reflect.Value) ([]byte, error) { return []byte(strconv.FormatBool(v.Bool())), nil },
		decode: func(data []byte, target reflect.Value) error {
			switch strings.TrimSpace(string(data)) {
			case "true":
				target.SetBool(true)
			case "false":
				target.SetBool(false)
			default:
				return refuse("", "not a boolean: %q", string(data))
			}
			return nil
		},
	},
	"duration-millis": {
		manifest: "duration-millis", contentType: TextContent,
		encode: func(v reflect.Value) ([]byte, error) {
			return []byte(strconv.FormatInt(time.Duration(v.Int()).Milliseconds(), 10)), nil
		},
		decode: func(data []byte, target reflect.Value) error {
			s := strings.TrimSpace(string(data))
			n, err := strconv.ParseInt(s, 10, 64)
			if err != nil {
				return refuse("", "not a millisecond count: %q", s)
			}
			target.SetInt(int64(time.Duration(n) * time.Millisecond))
			return nil
		},
	},
	"done": {
		manifest: "done", contentType: BinaryContent,
		encode: func(reflect.Value) ([]byte, error) { return []byte{}, nil },
		decode: func([]byte, reflect.Value) error { return nil },
	},
	"unit": {
		manifest: "unit", contentType: BinaryContent,
		encode: func(reflect.Value) ([]byte, error) { return []byte{}, nil },
		decode: func([]byte, reflect.Value) error { return nil },
	},
	"bytes": {
		manifest: "bytes", contentType: BinaryContent,
		encode: func(v reflect.Value) ([]byte, error) {
			if v.IsNil() {
				return []byte{}, nil
			}
			return v.Bytes(), nil
		},
		decode: func(data []byte, target reflect.Value) error {
			target.SetBytes(append([]byte{}, data...))
			return nil
		},
	},
}

// optionCodec is a top-level pointer: zero bytes for nil, 0x01 and the inner bytes otherwise.
func optionCodec(t reflect.Type, inner *typeCodec) *typeCodec {
	manifest := "option[" + inner.manifest + "]"
	encodeAt := func(v reflect.Value, home string) ([]byte, error) {
		if v.IsNil() {
			return []byte{}, nil
		}
		b, err := inner.encodeAt(v.Elem(), home)
		if err != nil {
			return nil, err
		}
		return append([]byte{1}, b...), nil
	}
	decodeAt := func(data []byte, target reflect.Value, home string) error {
		if len(data) == 0 {
			target.SetZero()
			return nil
		}
		if data[0] != 1 {
			return refuse("", "%s: expected a 0x01 marker, got %d", manifest, data[0])
		}
		v := reflect.New(t.Elem())
		if err := inner.decodeAt(data[1:], v.Elem(), home); err != nil {
			return err
		}
		target.Set(v)
		return nil
	}
	return &typeCodec{
		manifest: manifest, contentType: BinaryContent,
		encode: func(v reflect.Value) ([]byte, error) { return encodeAt(v, "") },
		decode: func(data []byte, target reflect.Value) error { return decodeAt(data, target, "") },
		sealAt: encodeAt,
		openAt: decodeAt,
	}
}

var optionManifest = regexp.MustCompile(`^option\[(.+)\]$`)

// primitiveManifest reports whether manifest names a primitive codec ("int", "option[string]"), which
// is decided by the manifest alone, as the sidecar's own codecs decide it.
func primitiveManifest(manifest string) bool {
	if _, ok := primitiveCodecs[manifest]; ok {
		return true
	}
	if m := optionManifest.FindStringSubmatch(manifest); m != nil {
		return primitiveManifest(m[1])
	}
	return false
}
