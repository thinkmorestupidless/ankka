package ankka

import (
	"encoding/json"
	"reflect"
)

// The JSON Schema a model is given for a tool's input or a task's result, derived from the same
// plan the codec encodes by, so the shape is declared once (FR-014). A field's `desc` tag is its
// description. A type met again inside itself is described by reference to its definition.

// schemaOf is the JSON Schema of t's encoding, or the problems that keep t from having one.
func schemaOf(t reflect.Type) (map[string]any, []string) {
	p, problems := planFor(t)
	if len(problems) > 0 {
		return nil, problems
	}
	w := &schemaWriter{defs: map[string]any{}, visiting: map[reflect.Type]bool{}, names: map[reflect.Type]string{}}
	s := w.schema(p)
	if len(w.defs) > 0 {
		s["$defs"] = w.defs
	}
	return s, nil
}

// schemaJSON is schemaOf as text, keys in a fixed order.
func schemaJSON(t reflect.Type) (string, []string) {
	s, problems := schemaOf(t)
	if len(problems) > 0 {
		return "", problems
	}
	b, err := json.Marshal(s)
	if err != nil {
		return "", []string{err.Error()}
	}
	return string(b), nil
}

type schemaWriter struct {
	defs     map[string]any
	visiting map[reflect.Type]bool
	names    map[reflect.Type]string
}

func (w *schemaWriter) schema(p *plan) map[string]any {
	switch p.kind {
	case kindString:
		return map[string]any{"type": "string"}
	case kindInt, kindLong, kindShort, kindByte:
		return map[string]any{"type": "integer"}
	case kindDouble, kindFloat:
		return map[string]any{"type": "number"}
	case kindBool:
		return map[string]any{"type": "boolean"}
	case kindInstant, kindLocalDateTime:
		return map[string]any{"type": "string", "format": "date-time"}
	case kindDuration:
		return map[string]any{"type": "string", "format": "duration"}
	case kindLocalDate:
		return map[string]any{"type": "string", "format": "date"}
	case kindBytes:
		return map[string]any{"type": "string", "contentEncoding": "base64"}
	case kindDone, kindUnit:
		return map[string]any{"type": "null"}
	case kindRaw:
		return map[string]any{}
	case kindOptional:
		return map[string]any{"anyOf": []any{w.schema(p.elem), map[string]any{"type": "null"}}}
	case kindSequence:
		s := map[string]any{"type": "array", "items": w.schema(p.elem)}
		if p.length >= 0 {
			s["minItems"], s["maxItems"] = p.length, p.length
		}
		return s
	case kindMap:
		return map[string]any{"type": "object", "additionalProperties": w.schema(p.elem)}
	case kindEnum:
		return map[string]any{
			"type":                 "object",
			"properties":           map[string]any{"type": map[string]any{"enum": append([]string(nil), p.values...)}},
			"required":             []string{"type"},
			"additionalProperties": false,
		}
	case kindRecord, kindSum:
		return w.named(p)
	}
	return map[string]any{}
}

// named describes a record or a sum type in place, or by reference when it is met inside itself.
func (w *schemaWriter) named(p *plan) map[string]any {
	if w.visiting[p.typ] {
		name, ok := w.names[p.typ]
		if !ok {
			name = p.typ.Name()
			if name == "" {
				name = "self"
			}
			w.names[p.typ] = name
		}
		return map[string]any{"$ref": "#/$defs/" + name}
	}
	w.visiting[p.typ] = true
	var s map[string]any
	if p.kind == kindRecord {
		s = w.object(p, nil)
	} else {
		var cases []any
		for _, name := range sumOrder(p) {
			c := p.sum.byName[name]
			cases = append(cases, w.object(c.record, &name))
		}
		s = map[string]any{"oneOf": cases}
	}
	delete(w.visiting, p.typ)
	if name, ok := w.names[p.typ]; ok {
		w.defs[name] = s
		return map[string]any{"$ref": "#/$defs/" + name}
	}
	return s
}

// object is a record's schema; a sum type's case also carries its discriminator.
func (w *schemaWriter) object(p *plan, discriminator *string) map[string]any {
	properties := map[string]any{}
	required := []string{}
	if discriminator != nil {
		properties["type"] = map[string]any{"const": *discriminator}
		required = append(required, "type")
	}
	for _, f := range p.fields {
		s := w.schema(f.plan)
		if d := p.typ.Field(f.index).Tag.Get("desc"); d != "" {
			s["description"] = d
		}
		properties[f.name] = s
		if f.plan.kind != kindOptional {
			required = append(required, f.name)
		}
	}
	return map[string]any{"type": "object", "properties": properties, "required": required, "additionalProperties": false}
}

// sumOrder is a sum type's cases in the order they were registered.
func sumOrder(p *plan) []string {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if reg := registry.sums[p.typ]; reg != nil {
		return append([]string(nil), reg.order...)
	}
	return sortedKeys(p.sum.byName)
}
