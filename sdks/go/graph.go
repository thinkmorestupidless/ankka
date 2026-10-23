package ankka

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"math/big"
	"reflect"
	"regexp"
	"sort"
	"strconv"
)

// GraphDeltaSchema is the graph delta contract's name: the manifest and the ce-type of every delta.
const GraphDeltaSchema = "ankka.graph-delta.v1"

// NodeKey is the record key of every delta for the node id.
func NodeKey(id string) string { return "node:" + id }

// EdgeKey is the record key of every delta for the edge id. Nodes and edges are separate id spaces.
func EdgeKey(id string) string { return "edge:" + id }

// GraphError is why an element or a result was refused: Why is the name the shared fixtures use
// (id, endpoints, identifier, reserved, property-value, integer-range, version, duplicate,
// no-sequence).
type GraphError struct {
	Why     string
	Message string
}

func (e *GraphError) Error() string { return e.Message }

// Properties are an element's properties: each a string, a bool, an integer, a float, or a
// non-empty slice of one of those kinds. A float with a whole value counts as an integer, as the
// sink reads it.
type Properties map[string]any

type elementKind int

const (
	elementNode elementKind = iota + 1
	elementEdge
	elementNodeTombstone
	elementEdgeTombstone
)

// Element is a graph element as a handler describes it: a node, an edge, or a tombstone marking one
// deleted. Without a version it takes its change's sequence number. An element the merge sink would
// refuse is refused when the result is published, naming why.
type Element struct {
	kind             elementKind
	id               string
	version          int64
	labels           []string
	typ, from, to    string
	properties       Properties
	propertiesWriter []byte
	fault            *GraphError
}

// ElementOption is an option of an element.
type ElementOption func(*Element)

// Labels are a node's labels, each an identifier.
func Labels(labels ...string) ElementOption {
	return func(e *Element) { e.labels = append([]string(nil), labels...) }
}

// WithProperties are an element's properties.
func WithProperties(p Properties) ElementOption { return func(e *Element) { e.properties = p } }

// AtVersion is the element's version, in place of its change's sequence number: 1 or more.
func AtVersion(v int64) ElementOption {
	return func(e *Element) {
		if v < 1 {
			e.refuse("version", "version must be a whole number of at least 1, not %d", v)
			return
		}
		e.version = v
	}
}

func (e *Element) describe() string {
	switch e.kind {
	case elementEdge:
		return fmt.Sprintf("edge %q", e.id)
	case elementNodeTombstone:
		return fmt.Sprintf("tombstone of node %q", e.id)
	case elementEdgeTombstone:
		return fmt.Sprintf("tombstone of edge %q", e.id)
	}
	return fmt.Sprintf("node %q", e.id)
}

func (e *Element) refuse(why, format string, args ...any) {
	if e.fault == nil {
		e.fault = &GraphError{Why: why, Message: e.describe() + ": " + fmt.Sprintf(format, args...)}
	}
}

// Key is the element's record key: NodeKey or EdgeKey of its id; a tombstone has the key of the
// element it marks.
func (e Element) Key() string {
	if e.kind == elementEdge || e.kind == elementEdgeTombstone {
		return EdgeKey(e.id)
	}
	return NodeKey(e.id)
}

var graphIdentifier = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

var reservedProperties = map[string]bool{"id": true, "_version": true, "_deleted": true}

func newElement(kind elementKind, id, typ, from, to string, opts []ElementOption) Element {
	e := Element{kind: kind, id: id, typ: typ, from: from, to: to}
	for _, o := range opts {
		o(&e)
	}
	if id == "" {
		e.refuse("id", "id must be a non-empty string")
	}
	if kind == elementEdge || kind == elementEdgeTombstone {
		switch {
		case typ == "" || from == "" || to == "":
			e.refuse("endpoints", "an edge needs a type, a from and a to")
		case !graphIdentifier.MatchString(typ):
			e.refuse("identifier", "type %q is not an identifier ([A-Za-z_][A-Za-z0-9_]*)", typ)
		}
	}
	for _, l := range e.labels {
		if !graphIdentifier.MatchString(l) {
			e.refuse("identifier", "label %q is not an identifier ([A-Za-z_][A-Za-z0-9_]*)", l)
		}
	}
	if kind == elementNode || kind == elementEdge {
		w, err := writeProperties(e.properties)
		if err != nil {
			if ge, ok := err.(*GraphError); ok {
				e.refuse(ge.Why, "%s", ge.Message)
			}
		}
		e.propertiesWriter = w
	} else if len(e.properties) > 0 || len(e.labels) > 0 {
		e.refuse("property-value", "a tombstone has no labels or properties")
	}
	return e
}

// Node is a node, with Labels and WithProperties.
func Node(id string, opts ...ElementOption) Element {
	return newElement(elementNode, id, "", "", "", opts)
}

// Edge is an edge of type typ from one node to another, with WithProperties.
func Edge(id, typ, from, to string, opts ...ElementOption) Element {
	return newElement(elementEdge, id, typ, from, to, opts)
}

// NodeTombstone marks a node deleted. It stays in the graph, marked, so a straggling older delta
// cannot bring it back.
func NodeTombstone(id string, opts ...ElementOption) Element {
	return newElement(elementNodeTombstone, id, "", "", "", opts)
}

// EdgeTombstone marks an edge deleted, naming its type and endpoints so it is found without a scan.
func EdgeTombstone(id, typ, from, to string, opts ...ElementOption) Element {
	return newElement(elementEdgeTombstone, id, typ, from, to, opts)
}

// ---- Properties, checked and written ----

type scalarKind int

const (
	scalarString scalarKind = iota + 1
	scalarBool
	scalarInteger
	scalarFloat
)

var kindNamesOfScalars = map[scalarKind]string{scalarString: "string", scalarBool: "boolean", scalarInteger: "integer", scalarFloat: "float"}

func propertyFault(name, format string, args ...any) *GraphError {
	return &GraphError{Why: "property-value", Message: fmt.Sprintf("property %q ", name) + fmt.Sprintf(format, args...)}
}

func rangeFault(name string, v any) *GraphError {
	return &GraphError{Why: "integer-range", Message: fmt.Sprintf("property %q is %v, which does not fit 64 bits", name, v)}
}

var (
	minInt64 = big.NewInt(math.MinInt64)
	maxInt64 = big.NewInt(math.MaxInt64)
)

// writeScalar writes one property scalar as the contract does, answering its kind.
func writeScalar(name string, v reflect.Value) (string, scalarKind, *GraphError) {
	if v.Kind() == reflect.Interface || v.Kind() == reflect.Pointer {
		if v.IsNil() {
			return "", 0, propertyFault(name, "is null; leave the property out instead")
		}
		if b, ok := v.Interface().(*big.Int); ok {
			if b.Cmp(minInt64) < 0 || b.Cmp(maxInt64) > 0 {
				return "", 0, rangeFault(name, b)
			}
			return b.String(), scalarInteger, nil
		}
		if v.Kind() == reflect.Interface {
			return writeScalar(name, v.Elem())
		}
		return "", 0, propertyFault(name, "is a %s; a property is a string, a number, a boolean, or a non-empty list of one of those", v.Type())
	}
	switch v.Kind() {
	case reflect.String:
		b, _ := json.Marshal(v.String())
		return string(b), scalarString, nil
	case reflect.Bool:
		return strconv.FormatBool(v.Bool()), scalarBool, nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return strconv.FormatInt(v.Int(), 10), scalarInteger, nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		if v.Uint() > math.MaxInt64 {
			return "", 0, rangeFault(name, v.Uint())
		}
		return strconv.FormatUint(v.Uint(), 10), scalarInteger, nil
	case reflect.Float32, reflect.Float64:
		f := v.Float()
		if math.IsInf(f, 0) || math.IsNaN(f) {
			return "", 0, propertyFault(name, "is %v, which is not a finite number", f)
		}
		if f == math.Trunc(f) {
			if f < -9223372036854775808.0 || f >= 9223372036854775808.0 {
				return "", 0, rangeFault(name, f)
			}
			return strconv.FormatInt(int64(f), 10), scalarInteger, nil
		}
		bits := 64
		if v.Kind() == reflect.Float32 {
			bits = 32
		}
		s, err := formatDouble(f, bits)
		if err != nil {
			return "", 0, propertyFault(name, "is %v: %v", f, err)
		}
		return s, scalarFloat, nil
	}
	return "", 0, propertyFault(name, "is a %s; a property is a string, a number, a boolean, or a non-empty list of one of those", v.Type())
}

// writeProperties writes the properties as the contract does, names in order, or the first fault.
func writeProperties(p Properties) ([]byte, error) {
	var buf bytes.Buffer
	buf.WriteByte('{')
	names := make([]string, 0, len(p))
	for n := range p {
		names = append(names, n)
	}
	sort.Strings(names)
	for i, name := range names {
		if reservedProperties[name] {
			return nil, &GraphError{Why: "reserved", Message: fmt.Sprintf("property %q is reserved", name)}
		}
		if i > 0 {
			buf.WriteByte(',')
		}
		key, _ := json.Marshal(name)
		buf.Write(key)
		buf.WriteByte(':')
		v := reflect.ValueOf(p[name])
		if !v.IsValid() {
			return nil, propertyFault(name, "is null; leave the property out instead")
		}
		if v.Kind() == reflect.Slice || v.Kind() == reflect.Array {
			if v.Len() == 0 {
				return nil, propertyFault(name, "is an empty list; leave the property out instead")
			}
			var kind scalarKind
			buf.WriteByte('[')
			for j := 0; j < v.Len(); j++ {
				item := v.Index(j)
				for item.Kind() == reflect.Interface && !item.IsNil() {
					item = item.Elem()
				}
				if (item.Kind() == reflect.Slice && item.Type() != reflect.TypeFor[[]byte]()) || item.Kind() == reflect.Map {
					return nil, propertyFault(name, "is a list holding a list or an object; a list holds scalars")
				}
				s, k, fault := writeScalar(name, item)
				if fault != nil {
					return nil, fault
				}
				if kind != 0 && k != kind {
					return nil, propertyFault(name, "is a list of more than one kind (%s, %s); a whole-number float counts as an integer", kindNamesOfScalars[kind], kindNamesOfScalars[k])
				}
				kind = k
				if j > 0 {
					buf.WriteByte(',')
				}
				buf.WriteString(s)
			}
			buf.WriteByte(']')
			continue
		}
		if v.Kind() == reflect.Map || v.Kind() == reflect.Struct {
			return nil, propertyFault(name, "is an object; a property is a string, a number, a boolean, or a non-empty list of one of those")
		}
		s, _, fault := writeScalar(name, v)
		if fault != nil {
			return nil, fault
		}
		buf.WriteString(s)
	}
	buf.WriteByte('}')
	return buf.Bytes(), nil
}

// ---- A result, resolved ----

// deltaRecord is one record a graph consumer's result comes to.
type deltaRecord struct {
	key   string
	value []byte
}

func quote(s string) string { b, _ := json.Marshal(s); return string(b) }

// writeDelta is the element at version as the contract writes it.
func writeDelta(e Element, version int64) []byte {
	v := strconv.FormatInt(version, 10)
	switch e.kind {
	case elementNode:
		labels := make([]string, len(e.labels))
		for i, l := range e.labels {
			labels[i] = quote(l)
		}
		return fmt.Appendf(nil, `{"kind":"node","id":%s,"version":%s,"labels":[%s],"properties":%s}`, quote(e.id), v, joinComma(labels), e.propertiesWriter)
	case elementEdge:
		return fmt.Appendf(nil, `{"kind":"edge","id":%s,"version":%s,"type":%s,"from":%s,"to":%s,"properties":%s}`, quote(e.id), v, quote(e.typ), quote(e.from), quote(e.to), e.propertiesWriter)
	case elementEdgeTombstone:
		return fmt.Appendf(nil, `{"kind":"tombstone","element":"edge","id":%s,"version":%s,"type":%s,"from":%s,"to":%s}`, quote(e.id), v, quote(e.typ), quote(e.from), quote(e.to))
	}
	return fmt.Appendf(nil, `{"kind":"tombstone","element":"node","id":%s,"version":%s}`, quote(e.id), v)
}

func joinComma(s []string) string {
	var b bytes.Buffer
	for i, x := range s {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(x)
	}
	return b.String()
}

// deltaRecords are what a result publishes for a change of sequence number sequence (0: none):
// every element at its stated version, else the sequence number, refusing an element the sink
// would refuse, an element named twice, or a version left to a change with no sequence number.
// The server and the test kit both read a result through this.
func deltaRecords(elements []Element, sequence int64) ([]deltaRecord, error) {
	seen := map[string]bool{}
	out := make([]deltaRecord, 0, len(elements))
	for _, e := range elements {
		if e.kind == 0 {
			return nil, &GraphError{Why: "id", Message: "an element built without Node, Edge, NodeTombstone or EdgeTombstone"}
		}
		if e.fault != nil {
			return nil, e.fault
		}
		key := e.Key()
		if seen[key] {
			return nil, &GraphError{Why: "duplicate", Message: fmt.Sprintf("%s is in this result twice (%s); an element is published once for a change", e.describe(), key)}
		}
		seen[key] = true
		version := e.version
		if version == 0 {
			if sequence < 1 {
				return nil, &GraphError{Why: "no-sequence", Message: e.describe() + ": this source has no sequence number; state a version with AtVersion"}
			}
			version = sequence
		}
		out = append(out, deltaRecord{key: key, value: writeDelta(e, version)})
	}
	return out, nil
}

// ---- Reading ----

// Delta is a graph delta as read back: one element's whole state at a version, or a tombstone.
type Delta struct {
	// Kind is node, edge or tombstone; Element, of a tombstone, is node or edge.
	Kind, Element string
	ID            string
	Version       int64
	Labels        []string
	Type          string
	From, To      string
	// Properties hold string, bool, int64 and float64 values, or slices of one of them.
	Properties map[string]any
}

// Key is the delta's record key.
func (d Delta) Key() string {
	if d.Kind == "edge" || (d.Kind == "tombstone" && d.Element == "edge") {
		return EdgeKey(d.ID)
	}
	return NodeKey(d.ID)
}

// ReadDelta reads a delta's value, for tests and for a consumer of a delta topic. Given a key, it
// also holds the contract's rule that the key is the delta's element key. It accepts version 0,
// which the contract allows and the builder does not write.
func ReadDelta(value []byte, key string) (Delta, error) {
	var raw map[string]any
	dec := json.NewDecoder(bytes.NewReader(value))
	dec.UseNumber()
	if err := dec.Decode(&raw); err != nil {
		return Delta{}, fmt.Errorf("not a delta: %w", err)
	}
	str := func(name string) string { s, _ := raw[name].(string); return s }
	d := Delta{Kind: str("kind"), Element: str("element"), ID: str("id"), Type: str("type"), From: str("from"), To: str("to"), Properties: map[string]any{}}
	switch d.Kind {
	case "node", "edge":
	case "tombstone":
		if d.Element != "node" && d.Element != "edge" {
			return Delta{}, fmt.Errorf("a tombstone marks a node or an edge, not %q", d.Element)
		}
	default:
		return Delta{}, fmt.Errorf("kind is %q", d.Kind)
	}
	if d.ID == "" {
		return Delta{}, fmt.Errorf("id is missing or empty")
	}
	n, ok := raw["version"].(json.Number)
	if !ok {
		return Delta{}, fmt.Errorf("version is not a non-negative integer")
	}
	version, err := n.Int64()
	if err != nil || version < 0 {
		return Delta{}, fmt.Errorf("version is not a non-negative integer")
	}
	d.Version = version
	edgeLike := d.Kind == "edge" || (d.Kind == "tombstone" && d.Element == "edge")
	if edgeLike && (d.From == "" || d.To == "" || !graphIdentifier.MatchString(d.Type)) {
		return Delta{}, fmt.Errorf("an edge needs a type, a from and a to")
	}
	if ls, ok := raw["labels"].([]any); ok {
		for _, l := range ls {
			s, _ := l.(string)
			if !graphIdentifier.MatchString(s) {
				return Delta{}, fmt.Errorf("label %v is not an identifier", l)
			}
			d.Labels = append(d.Labels, s)
		}
	}
	if ps, ok := raw["properties"].(map[string]any); ok {
		for name, v := range ps {
			if reservedProperties[name] {
				return Delta{}, fmt.Errorf("property %q is reserved", name)
			}
			read, err := readProperty(name, v)
			if err != nil {
				return Delta{}, err
			}
			d.Properties[name] = read
		}
	}
	if key != "" && key != d.Key() {
		return Delta{}, fmt.Errorf("the record key is %q and the delta's element key %q", key, d.Key())
	}
	return d, nil
}

func readScalar(name string, v any) (any, scalarKind, error) {
	switch x := v.(type) {
	case string:
		return x, scalarString, nil
	case bool:
		return x, scalarBool, nil
	case json.Number:
		if i, err := x.Int64(); err == nil {
			return i, scalarInteger, nil
		}
		f, err := x.Float64()
		if err != nil {
			return nil, 0, fmt.Errorf("property %q is not a scalar or array of scalars", name)
		}
		if f == math.Trunc(f) {
			if f < -9223372036854775808.0 || f >= 9223372036854775808.0 {
				return nil, 0, fmt.Errorf("property %q is not a scalar or array of scalars", name)
			}
			return int64(f), scalarInteger, nil
		}
		return f, scalarFloat, nil
	}
	return nil, 0, fmt.Errorf("property %q is not a scalar or array of scalars", name)
}

func readProperty(name string, v any) (any, error) {
	list, ok := v.([]any)
	if !ok {
		s, _, err := readScalar(name, v)
		return s, err
	}
	if len(list) == 0 {
		return nil, fmt.Errorf("property %q is not a scalar or array of scalars", name)
	}
	var kind scalarKind
	out := make([]any, 0, len(list))
	for _, item := range list {
		s, k, err := readScalar(name, item)
		if err != nil {
			return nil, err
		}
		if kind != 0 && k != kind {
			return nil, fmt.Errorf("property %q is not a scalar or array of scalars", name)
		}
		kind = k
		out = append(out, s)
	}
	return out, nil
}

// ---- The effect ----

type graphKind int

const (
	graphNone graphKind = iota
	graphPublish
	graphDone
	graphIgnore
)

// GraphEffect is what a graph consumer's handler answers: elements to publish, done, or ignored.
type GraphEffect struct {
	kind     graphKind
	elements []Element
}

// Publish publishes these elements, each as a delta under its own key. None publishes nothing.
func Publish(elements ...Element) GraphEffect {
	return GraphEffect{kind: graphPublish, elements: append([]Element(nil), elements...)}
}

// GraphDone acknowledges the change.
func GraphDone() GraphEffect { return GraphEffect{kind: graphDone} }

// GraphIgnore acknowledges the change, saying it was of no interest.
func GraphIgnore() GraphEffect { return GraphEffect{kind: graphIgnore} }

// Elements are what the effect publishes.
func (e GraphEffect) Elements() []Element { return e.elements }
