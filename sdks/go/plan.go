package ankka

import (
	"encoding/json"
	"fmt"
	"reflect"
	"sort"
	"strings"
	"time"
	"unicode"
)

// A plan is what the codec knows about a Go type: its shape in the encoding of protocol/ENCODING.md,
// read from the type by reflection once and kept. The developer never sees one, only the rules it
// enforces (data-model.md, "Type plans").

type planKind int

const (
	kindString planKind = iota
	kindInt
	kindLong
	kindShort
	kindByte
	kindDouble
	kindFloat
	kindBool
	kindInstant
	kindDuration
	kindLocalDate
	kindLocalDateTime
	kindBytes
	kindOptional
	kindSequence
	kindMap
	kindRecord
	kindSum
	kindEnum
	kindDone
	kindUnit
	kindRaw
)

var kindNames = map[planKind]string{
	kindString: "string", kindInt: "int", kindLong: "long", kindShort: "short", kindByte: "byte",
	kindDouble: "double", kindFloat: "float", kindBool: "boolean", kindInstant: "instant",
	kindDuration: "duration", kindLocalDate: "local date", kindLocalDateTime: "local date-time",
	kindBytes: "bytes", kindOptional: "optional", kindSequence: "sequence", kindMap: "map",
	kindRecord: "record", kindSum: "sum type", kindEnum: "enumeration", kindDone: "done", kindUnit: "unit", kindRaw: "raw JSON",
}

type plan struct {
	kind   planKind
	typ    reflect.Type
	elem   *plan       // optional, sequence, map
	length int         // a fixed-length array's length; -1 for a slice
	fields []fieldPlan // record
	sum    *sumPlan    // sum type
	values []string    // enumeration
	allows map[string]bool
}

type fieldPlan struct {
	name  string
	index int
	plan  *plan
}

type sumPlan struct {
	byName map[string]*casePlan
	byType map[reflect.Type]*casePlan
}

type casePlan struct {
	discriminator string
	pointer       bool
	record        *plan
}

func (p *plan) describe() string {
	if p.typ != nil && p.typ.Name() != "" {
		return p.typ.String()
	}
	return kindNames[p.kind]
}

var (
	timeType          = reflect.TypeFor[time.Time]()
	durationType      = reflect.TypeFor[time.Duration]()
	localDateType     = reflect.TypeFor[LocalDate]()
	localDateTimeType = reflect.TypeFor[LocalDateTime]()
	doneType          = reflect.TypeFor[Done]()
	unitType          = reflect.TypeFor[Unit]()
	bytesType         = reflect.TypeFor[[]byte]()
	rawJSONType       = reflect.TypeFor[json.RawMessage]()
)

// planFor builds, or returns the kept, plan for t; problems name the type and the field. A type with
// problems is never kept, so a registration made later is seen.
func planFor(t reflect.Type) (*plan, []string) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if p, ok := registry.plans[t]; ok {
		return p, nil
	}
	b := &planBuilder{building: map[reflect.Type]*plan{}}
	p := b.build(t, t.String())
	if len(b.problems) > 0 {
		return nil, dedupe(b.problems)
	}
	for typ, built := range b.building {
		registry.plans[typ] = built
	}
	return p, nil
}

type planBuilder struct {
	building map[reflect.Type]*plan
	problems []string
}

func (b *planBuilder) refuse(where, format string, args ...any) *plan {
	b.problems = append(b.problems, where+": "+fmt.Sprintf(format, args...))
	return &plan{kind: kindString}
}

func (b *planBuilder) build(t reflect.Type, where string) *plan {
	if p, ok := b.building[t]; ok {
		return p // a recursive type, handled by reference
	}
	if p, ok := registry.plans[t]; ok {
		return p
	}
	switch t {
	case timeType:
		return b.keep(t, &plan{kind: kindInstant, typ: t})
	case durationType:
		return b.keep(t, &plan{kind: kindDuration, typ: t})
	case localDateType:
		return b.keep(t, &plan{kind: kindLocalDate, typ: t})
	case localDateTimeType:
		return b.keep(t, &plan{kind: kindLocalDateTime, typ: t})
	case doneType:
		return b.keep(t, &plan{kind: kindDone, typ: t})
	case unitType:
		return b.keep(t, &plan{kind: kindUnit, typ: t})
	case rawJSONType:
		return b.keep(t, &plan{kind: kindRaw, typ: t})
	}
	switch t.Kind() {
	case reflect.String:
		if reg := registry.enums[t]; reg != nil {
			for _, p := range reg.problems {
				b.problems = append(b.problems, where+": "+p)
			}
			allows := map[string]bool{}
			for _, v := range reg.values {
				allows[v] = true
			}
			return b.keep(t, &plan{kind: kindEnum, typ: t, values: reg.values, allows: allows})
		}
		return b.keep(t, &plan{kind: kindString, typ: t})
	case reflect.Int, reflect.Int32:
		return b.keep(t, &plan{kind: kindInt, typ: t})
	case reflect.Int64:
		return b.keep(t, &plan{kind: kindLong, typ: t})
	case reflect.Int16:
		return b.keep(t, &plan{kind: kindShort, typ: t})
	case reflect.Int8:
		return b.keep(t, &plan{kind: kindByte, typ: t})
	case reflect.Float64:
		return b.keep(t, &plan{kind: kindDouble, typ: t})
	case reflect.Float32:
		return b.keep(t, &plan{kind: kindFloat, typ: t})
	case reflect.Bool:
		return b.keep(t, &plan{kind: kindBool, typ: t})
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return b.refuse(where, "%s is unsigned, which the encoding has no type for; use int, int32 or int64", t)
	case reflect.Complex64, reflect.Complex128:
		return b.refuse(where, "%s is a complex number, which the encoding has no type for", t)
	case reflect.Chan, reflect.Func, reflect.UnsafePointer:
		return b.refuse(where, "%s cannot be encoded", t)
	case reflect.Pointer:
		p := &plan{kind: kindOptional, typ: t}
		b.building[t] = p
		p.elem = b.build(t.Elem(), where)
		if p.elem.kind == kindOptional {
			return b.refuse(where, "%s is a pointer to a pointer; one pointer says optional", t)
		}
		return p
	case reflect.Slice:
		if t.Elem().Kind() == reflect.Uint8 {
			return b.keep(t, &plan{kind: kindBytes, typ: t})
		}
		p := &plan{kind: kindSequence, typ: t, length: -1}
		b.building[t] = p
		p.elem = b.build(t.Elem(), where+"[]")
		return p
	case reflect.Array:
		p := &plan{kind: kindSequence, typ: t, length: t.Len()}
		b.building[t] = p
		p.elem = b.build(t.Elem(), where+"[]")
		return p
	case reflect.Map:
		if t.Key().Kind() != reflect.String {
			return b.refuse(where, "%s has %s keys; a map's keys must be strings", t, t.Key())
		}
		p := &plan{kind: kindMap, typ: t}
		b.building[t] = p
		p.elem = b.build(t.Elem(), where+"{}")
		return p
	case reflect.Struct:
		return b.record(t, where)
	case reflect.Interface:
		return b.sum(t, where)
	}
	return b.refuse(where, "%s cannot be encoded", t)
}

func (b *planBuilder) keep(t reflect.Type, p *plan) *plan {
	b.building[t] = p
	return p
}

func (b *planBuilder) record(t reflect.Type, where string) *plan {
	p := &plan{kind: kindRecord, typ: t}
	b.building[t] = p
	names := map[string]string{}
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		tag, tagged := f.Tag.Lookup("json")
		if tag == "-" {
			continue
		}
		fieldWhere := fmt.Sprintf("%s.%s", t, f.Name)
		if f.Anonymous {
			b.refuse(fieldWhere, "an embedded field cannot be encoded (the order of promoted fields is ambiguous); name the field")
			continue
		}
		if !f.IsExported() {
			b.refuse(fieldWhere, "an unexported field cannot be encoded; export it or tag it `json:\"-\"`")
			continue
		}
		name := lowerCamel(f.Name)
		if tagged {
			if strings.Contains(tag, ",") {
				b.refuse(fieldWhere, "the json tag %q has an option; the encoding writes every field and an optional field is a pointer, so no option applies", tag)
				continue
			}
			if tag != "" {
				name = tag
			}
		}
		if prev, dup := names[name]; dup {
			b.refuse(fieldWhere, "the name %q is also %s's", name, prev)
			continue
		}
		names[name] = f.Name
		p.fields = append(p.fields, fieldPlan{name: name, index: i, plan: b.build(f.Type, fieldWhere)})
	}
	return p
}

func (b *planBuilder) sum(t reflect.Type, where string) *plan {
	reg := registry.sums[t]
	if reg == nil {
		if t.NumMethod() == 0 {
			return b.refuse(where, "%s holds any value, which the encoding cannot describe; use an interface with registered cases (ankka.Cases)", t)
		}
		return b.refuse(where, "the interface %s has no registered cases; register them with ankka.Cases[%s](...)", t, t.Name())
	}
	for _, problem := range reg.problems {
		b.problems = append(b.problems, where+": "+problem)
	}
	p := &plan{kind: kindSum, typ: t, sum: &sumPlan{byName: map[string]*casePlan{}, byType: map[reflect.Type]*casePlan{}}}
	b.building[t] = p
	for _, name := range reg.order {
		c := reg.byName[name]
		record := b.build(c.typ, fmt.Sprintf("%s (case %q)", where, name))
		for _, f := range record.fields {
			if f.name == "type" {
				b.refuse(fmt.Sprintf("%s.%s", c.typ, "type"), "a case of %s has a field named \"type\", which is the discriminator's", t)
			}
		}
		cp := &casePlan{discriminator: name, pointer: c.pointer, record: record}
		p.sum.byName[name] = cp
		p.sum.byType[c.typ] = cp
	}
	return p
}

// lowerCamel is a Go field name as a JSON name: the leading capital, or the leading run of
// capitals of an initialism, lowered ("Name" → "name", "ID" → "id", "URLPath" → "urlPath").
func lowerCamel(name string) string {
	runes := []rune(name)
	i := 0
	for i < len(runes) && unicode.IsUpper(runes[i]) {
		i++
	}
	switch {
	case i == 0:
		return name
	case i == 1 || i == len(runes):
		return strings.ToLower(string(runes[:i])) + string(runes[i:])
	default:
		// "URLPath": the last capital starts the next word.
		return strings.ToLower(string(runes[:i-1])) + string(runes[i-1:])
	}
}

func dedupe(problems []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, p := range problems {
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	sort.Strings(out)
	return out
}
