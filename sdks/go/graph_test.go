package ankka

import (
	"bytes"
	"encoding/json"
	"errors"
	"math"
	"math/big"
	"os"
	"reflect"
	"sort"
	"strings"
	"testing"
)

const graphFixtures = "protocol/fixtures/graph-deltas/"

type graphRow struct {
	Name     string            `json:"name"`
	Delta    json.RawMessage   `json:"delta"`
	Element  json.RawMessage   `json:"element"`
	Elements []json.RawMessage `json:"elements"`
	Key      string            `json:"key"`
	Why      string            `json:"why"`
	Sequence *int64            `json:"sequence"`
}

func graphRows(t *testing.T, file string) []graphRow {
	t.Helper()
	data, err := os.ReadFile(graphFixtures + file)
	if err != nil {
		t.Fatal(err)
	}
	var rows []graphRow
	if err := json.Unmarshal(data, &rows); err != nil {
		t.Fatal(err)
	}
	return rows
}

// fixtureValue is a fixture's JSON value as an author's Go value: an integer literal an int64, or a
// *big.Int past 64 bits; a fraction or an exponent a float64.
func fixtureValue(v any) any {
	switch x := v.(type) {
	case json.Number:
		if i, err := x.Int64(); err == nil {
			return i
		}
		if strings.ContainsAny(x.String(), ".eE") {
			f, _ := x.Float64()
			return f
		}
		b, _ := new(big.Int).SetString(x.String(), 10)
		return b
	case []any:
		out := make([]any, len(x))
		for i, item := range x {
			out[i] = fixtureValue(item)
		}
		return out
	case map[string]any:
		out := map[string]any{}
		for k, item := range x {
			out[k] = fixtureValue(item)
		}
		return out
	}
	return v
}

// elementOf builds the element a fixture row describes, the way an author builds it. A version an
// int64 cannot hold, or a fraction, counts as refused by type (SOURCE.md).
func elementOf(t *testing.T, raw json.RawMessage) (Element, bool) {
	t.Helper()
	var m map[string]any
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(&m); err != nil {
		t.Fatal(err)
	}
	str := func(k string) string { s, _ := m[k].(string); return s }
	var opts []ElementOption
	if v, ok := m["version"]; ok {
		n, err := v.(json.Number).Int64()
		if err != nil {
			return Element{}, false
		}
		opts = append(opts, AtVersion(n))
	}
	if ls, ok := m["labels"].([]any); ok {
		var labels []string
		for _, l := range ls {
			labels = append(labels, l.(string))
		}
		opts = append(opts, Labels(labels...))
	}
	if ps, ok := m["properties"].(map[string]any); ok {
		opts = append(opts, WithProperties(Properties(fixtureValue(ps).(map[string]any))))
	}
	switch {
	case str("kind") == "node":
		return Node(str("id"), opts...), true
	case str("kind") == "edge":
		return Edge(str("id"), str("type"), str("from"), str("to"), opts...), true
	case str("element") == "edge":
		return EdgeTombstone(str("id"), str("type"), str("from"), str("to"), opts...), true
	}
	return NodeTombstone(str("id"), opts...), true
}

func TestTheBuilderWritesWhatTheSinkReads(t *testing.T) {
	for _, file := range []string{"keys.json", "deltas.json"} {
		rows := graphRows(t, file)
		if len(rows) < 8 {
			t.Fatalf("%s has %d rows", file, len(rows))
		}
		for _, row := range rows {
			e, ok := elementOf(t, row.Delta)
			if !ok {
				t.Fatalf("%s: a row's version cannot be built", file)
			}
			records, err := deltaRecords([]Element{e}, 1)
			if err != nil {
				t.Errorf("%s: %s is refused: %v", file, row.Delta, err)
				continue
			}
			if records[0].key != row.Key {
				t.Errorf("%s: %s has the key %q, not %q", file, row.Delta, records[0].key, row.Key)
			}
			got, err := ReadDelta(records[0].value, row.Key)
			if err != nil {
				t.Errorf("%s: what the builder wrote for %s does not read: %v\n%s", file, row.Delta, err, records[0].value)
				continue
			}
			want, err := ReadDelta(row.Delta, row.Key)
			if err != nil {
				t.Fatalf("%s: the fixture %s does not read: %v", file, row.Delta, err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Errorf("%s: the builder wrote\n%s\nwhich reads %+v, not %+v", file, records[0].value, got, want)
			}
		}
	}
}

func TestTheBuilderRefusesWhatTheSinkWouldRefuse(t *testing.T) {
	rows := graphRows(t, "refused.json")
	whys := map[string]bool{}
	for _, row := range rows {
		whys[row.Why] = true
	}
	if len(rows) < 42 || strings.Join(sortedKeys(whys), ",") != "duplicate,endpoints,id,identifier,integer-range,no-sequence,property-value,reserved,version" {
		t.Fatalf("%d refused rows, for %v", len(rows), sortedKeys(whys))
	}
	for _, row := range rows {
		sequence := int64(1)
		if row.Sequence != nil {
			sequence = *row.Sequence
		}
		raws := row.Elements
		if raws == nil {
			raws = []json.RawMessage{row.Element}
		}
		var elements []Element
		byType := false
		for _, raw := range raws {
			e, ok := elementOf(t, raw)
			if !ok {
				byType = true
			}
			elements = append(elements, e)
		}
		if byType {
			if row.Why != "version" {
				t.Errorf("%s: refused by type, and the row says %s", row.Name, row.Why)
			}
			continue
		}
		_, err := deltaRecords(elements, sequence)
		var ge *GraphError
		if !errors.As(err, &ge) {
			t.Errorf("%s: not refused (%v)", row.Name, err)
			continue
		}
		if ge.Why != row.Why {
			t.Errorf("%s: refused for %s (%s), not %s", row.Name, ge.Why, ge.Message, row.Why)
		}
	}
}

func TestAPropertyThatIsNotFiniteIsRefused(t *testing.T) {
	for _, f := range []float64{math.NaN(), math.Inf(1), math.Inf(-1)} {
		_, err := deltaRecords([]Element{Node("n", WithProperties(Properties{"p": f}))}, 1)
		var ge *GraphError
		if !errors.As(err, &ge) || ge.Why != "property-value" {
			t.Errorf("%v: refused as %v", f, err)
		}
	}
	// Go's own types: unsigned past 63 bits, and a float32.
	if _, err := deltaRecords([]Element{Node("n", WithProperties(Properties{"p": uint64(math.MaxUint64)}))}, 1); err == nil || err.(*GraphError).Why != "integer-range" {
		t.Errorf("a uint64 past 63 bits is refused as %v", err)
	}
	records, err := deltaRecords([]Element{Node("n", WithProperties(Properties{"p": float32(0.1), "q": []int{1, 2}}))}, 1)
	if err != nil || !strings.Contains(string(records[0].value), `"p":0.1`) {
		t.Errorf("a float32 writes %s, %v", records[0].value, err)
	}
}

func TestPropertiesAreWrittenInNameOrder(t *testing.T) {
	records, err := deltaRecords([]Element{Node("n", WithProperties(Properties{"b": 1, "a": 2, "c": 3}))}, 1)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(records[0].value), `"properties":{"a":2,"b":1,"c":3}`) {
		t.Fatalf("wrote %s", records[0].value)
	}
}

func TestTheReaderHoldsTheKeyRule(t *testing.T) {
	if _, err := ReadDelta([]byte(`{"kind":"node","id":"n","version":0}`), "node:n"); err != nil {
		t.Errorf("version 0 does not read: %v", err)
	}
	if _, err := ReadDelta([]byte(`{"kind":"node","id":"n","version":1}`), "edge:n"); err == nil {
		t.Error("a delta under another element's key reads")
	}
	keys := []string{NodeKey("x"), EdgeKey("x")}
	sort.Strings(keys)
	if keys[0] == keys[1] {
		t.Error("a node and an edge share a key")
	}
}
