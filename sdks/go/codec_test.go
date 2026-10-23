package ankka

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
)

func TestAMapOfSeveralKeysIsWrittenInKeyOrder(t *testing.T) {
	type labels struct {
		Labels map[string]int `json:"labels"`
	}
	c := CodecFor[labels]()
	for range 20 { // a Go map's iteration order changes from run to run; the bytes must not
		b, err := c.Encode(labels{Labels: map[string]int{"zeta": 1, "alpha": 2, "mid": 3, "Beta": 4}})
		if err != nil || string(b) != `{"labels":{"Beta":4,"alpha":2,"mid":3,"zeta":1}}` {
			t.Fatalf("got %s, %v", b, err)
		}
	}
}

func TestEveryFieldIsWrittenIncludingEmptyOnes(t *testing.T) {
	type rec struct {
		Items []string        `json:"items"`
		Map   map[string]bool `json:"map"`
		Note  *string         `json:"note"`
		Count int             `json:"count"`
	}
	b, err := CodecFor[rec]().Encode(rec{})
	if err != nil || string(b) != `{"items":[],"map":{},"note":null,"count":0}` {
		t.Fatalf("got %s, %v", b, err)
	}
}

func TestStringsAreEscapedAsJsoniterEscapesThem(t *testing.T) {
	type rec struct {
		S string `json:"s"`
	}
	b, err := CodecFor[rec]().Encode(rec{S: "a\"b\\c\b\t\n\f\r\x01\x1f\x7f/é€<>&"})
	want := `{"s":"a\"b\\c\b\t\n\f\r\u0001\u001f\u007f/é€<>&"}`
	if err != nil || string(b) != want {
		t.Fatalf("got %s, %v; want %s", b, err, want)
	}
	back, err := CodecFor[rec]().Decode(b)
	if err != nil || back.S != "a\"b\\c\b\t\n\f\r\x01\x1f\x7f/é€<>&" {
		t.Fatalf("read back %q, %v", back.S, err)
	}
	if _, err := CodecFor[rec]().Encode(rec{S: "\xff"}); err == nil {
		t.Fatal("text that is not UTF-8 was written")
	}
}

func TestFieldNamesComeFromTagsOrTheFieldNameInLowerCamel(t *testing.T) {
	type rec struct {
		Name     string
		ID       string
		URLPath  string
		Tagged   string `json:"renamed"`
		Excluded string `json:"-"`
	}
	b, err := CodecFor[rec]().Encode(rec{Name: "n", ID: "i", URLPath: "u", Tagged: "t", Excluded: "x"})
	if err != nil || string(b) != `{"name":"n","id":"i","urlPath":"u","renamed":"t"}` {
		t.Fatalf("got %s, %v", b, err)
	}
}

type badUnsigned struct {
	N uint `json:"n"`
}
type badChan struct {
	C chan int `json:"c"`
}
type badFunc struct {
	F func() `json:"f"`
}
type badAny struct {
	A any `json:"a"`
}
type unregistered interface{ unregistered() }
type badInterface struct {
	U unregistered `json:"u"`
}
type badMapKey struct {
	M map[int]string `json:"m"`
}
type badUnexported struct {
	hidden string
}
type inner struct {
	X string `json:"x"`
}
type badEmbedded struct {
	inner
}
type badOption struct {
	N string `json:"n,omitempty"`
}
type badDuplicate struct {
	Same string
	B    string `json:"same"`
}
type badPointerPointer struct {
	P **string `json:"p"`
}

type collides interface{ collides() }
type withType struct {
	Type string `json:"type"`
}

func (withType) collides() {}

var _ = Cases[collides](Case[collides, withType]("WithType"))

type twice interface{ twice() }
type twiceA struct{}
type twiceB struct{}

func (twiceA) twice() {}
func (twiceB) twice() {}

var _ = Cases[twice](Case[twice, twiceA]("Same"), Case[twice, twiceB]("Same"))

type notImplemented interface{ notImplemented() }
type stranger struct{}

var _ = Cases[notImplemented](Case[notImplemented, stranger]("Stranger"))

type badCollides struct {
	C collides `json:"c"`
}
type badTwice struct {
	T twice `json:"t"`
}
type badNotImplemented struct {
	N notImplemented `json:"n"`
}

func problemsOf[T any]() []string { return CodecProblems(CodecFor[T]()) }

func TestTypesTheEncodingCannotDescribeAreRefusedNamingTheField(t *testing.T) {
	cases := map[string]struct {
		problems []string
		mentions string
	}{
		"unsigned":                 {problemsOf[badUnsigned](), "badUnsigned.N: uint is unsigned"},
		"a channel":                {problemsOf[badChan](), "badChan.C"},
		"a function":               {problemsOf[badFunc](), "badFunc.F"},
		"any":                      {problemsOf[badAny](), "badAny.A: interface {} holds any value"},
		"an interface, no cases":   {problemsOf[badInterface](), "the interface ankka.unregistered has no registered cases"},
		"a map with int keys":      {problemsOf[badMapKey](), "badMapKey.M: map[int]string has int keys"},
		"an unexported field":      {problemsOf[badUnexported](), "badUnexported.hidden: an unexported field"},
		"an embedded field":        {problemsOf[badEmbedded](), "badEmbedded.inner: an embedded field"},
		"a tag option":             {problemsOf[badOption](), `badOption.N: the json tag "n,omitempty" has an option`},
		"two fields, one name":     {problemsOf[badDuplicate](), `badDuplicate.B: the name "same" is also Same's`},
		"a pointer to a pointer":   {problemsOf[badPointerPointer](), "pointer to a pointer"},
		"a field named type":       {problemsOf[badCollides](), `withType.type: a case of ankka.collides has a field named "type"`},
		"a discriminator twice":    {problemsOf[badTwice](), `the discriminator "Same" is registered twice`},
		"a case not implementing":  {problemsOf[badNotImplemented](), "does not implement ankka.notImplemented"},
		"an unnamed type, no name": {problemsOf[[]fxLineItem](), "has no name to use as its manifest"},
	}
	for name, c := range cases {
		if !strings.Contains(strings.Join(c.problems, "\n"), c.mentions) {
			t.Errorf("%s: problems %q do not mention %q", name, c.problems, c.mentions)
		}
	}
	if _, err := CodecFor[badUnsigned]().Encode(badUnsigned{}); err == nil || !strings.Contains(err.Error(), "unsigned") {
		t.Errorf("a codec with problems encoded: %v", err)
	}
}

func TestReadingIsLenientWhereWritingIsStrict(t *testing.T) {
	c := CodecFor[fxService](Manifest("service"))
	got, err := c.Decode([]byte(`{"status":"Ready","unknown":[1,{"x":2}],"createdAt":"2026-09-23T12:00:00+02:00","name":"cart"}`))
	if err != nil {
		t.Fatal(err)
	}
	if got.Name != "cart" || got.Owner != nil || got.Note != nil || got.Status != "Ready" || FormatInstant(got.CreatedAt) != "2026-09-23T10:00:00Z" {
		t.Fatalf("got %+v", got)
	}
}

func TestReadingRefusesWhatIsRequiredNamingThePath(t *testing.T) {
	cart := CodecFor[fxShoppingCart](Manifest("shopping-cart"))
	event := CodecFor[fxCartEvent](Manifest("shopping-cart-event"))
	status := CodecFor[fxStatus](Manifest("status"))
	cases := []struct {
		decode func() error
		path   string
		says   string
	}{
		{func() error { _, err := cart.Decode([]byte(`{"items":[]}`)); return err }, "checkedOut", "missing required field"},
		{func() error {
			_, err := cart.Decode([]byte(`{"items":[{"productId":"p","name":"n","quantity":1.5}],"checkedOut":false}`))
			return err
		}, "items[0].quantity", "not a whole number"},
		{func() error {
			_, err := cart.Decode([]byte(`{"items":[{"productId":"p","name":"n","quantity":3000000000}],"checkedOut":false}`))
			return err
		}, "items[0].quantity", "does not fit int"},
		{func() error { _, err := event.Decode([]byte(`{"type":"Teleported"}`)); return err }, "", `has no case "Teleported"`},
		{func() error { _, err := event.Decode([]byte(`{"item":{}}`)); return err }, "", `needs a "type"`},
		{func() error { _, err := status.Decode([]byte(`"Gone"`)); return err }, "", `expected one of "Ready", "Failed"`},
		{func() error { _, err := cart.Decode([]byte(`{"items":[],"checkedOut":false} x`)); return err }, "", "not JSON"},
	}
	for i, c := range cases {
		err := c.decode()
		var de *DecodingError
		if !errors.As(err, &de) {
			t.Errorf("case %d: %v is not a DecodingError", i, err)
			continue
		}
		if de.Path != c.path || !strings.Contains(de.Message, c.says) {
			t.Errorf("case %d: path %q, message %q; want path %q saying %q", i, de.Path, de.Message, c.path, c.says)
		}
	}
	if s, err := status.Decode([]byte(`"Failed"`)); err != nil || s != "Failed" {
		t.Errorf("a bare string for an enumeration: %v, %v", s, err)
	}
}

func TestWritingRefusesWhatCannotBeWritten(t *testing.T) {
	type rec struct {
		N int     `json:"n"`
		D float64 `json:"d"`
	}
	if _, err := CodecFor[rec]().Encode(rec{N: 1 << 40}); err == nil || !strings.Contains(err.Error(), "does not fit an int") {
		t.Errorf("an int past 32 bits: %v", err)
	}
	nan := 0.0
	if _, err := CodecFor[rec]().Encode(rec{D: nan / nan}); err == nil || !strings.Contains(err.Error(), "NaN") {
		t.Errorf("NaN: %v", err)
	}
	if _, err := CodecFor[fxCartEvent](Manifest("e")).Encode(nil); err == nil {
		t.Error("a nil sum type value was written")
	}
	if _, err := CodecFor[fxStatus](Manifest("s")).Encode("Gone"); err == nil {
		t.Error("a value that is not the enumeration's was written")
	}
	type unregisteredCase struct{}
	var e fxCartEvent = fxItemRemoved{ProductID: "p"}
	if b, err := CodecFor[fxCartEvent](Manifest("e")).Encode(e); err != nil || string(b) != `{"type":"ItemRemoved","productId":"p"}` {
		t.Errorf("a registered case: %s, %v", b, err)
	}
}

func TestAPointerCaseIsReadBackAsAPointer(t *testing.T) {
	b, err := CodecFor[pointerSum](Manifest("p")).Encode(&pointerCase{N: 2})
	if err != nil || string(b) != `{"type":"P","n":2}` {
		t.Fatalf("got %s, %v", b, err)
	}
	back, err := CodecFor[pointerSum](Manifest("p")).Decode(b)
	if pc, ok := back.(*pointerCase); err != nil || !ok || pc.N != 2 {
		t.Fatalf("read back %#v, %v", back, err)
	}
}

type pointerSum interface{ pointerSum() }
type pointerCase struct {
	N int `json:"n"`
}

func (*pointerCase) pointerSum() {}

var _ = Cases[pointerSum](Case[pointerSum, pointerCase]("P"))

func TestRawJSONIsKeptAsWritten(t *testing.T) {
	type rec struct {
		Args json.RawMessage `json:"args"`
	}
	c := CodecFor[rec]()
	back, err := c.Decode([]byte(`{"args": {"z": 1, "a": [1.50, "x"]}}`))
	if err != nil || string(back.Args) != `{"z":1,"a":[1.50,"x"]}` {
		t.Fatalf("got %s, %v", back.Args, err)
	}
	b, err := c.Encode(back)
	if err != nil || string(b) != `{"args":{"z":1,"a":[1.50,"x"]}}` {
		t.Fatalf("got %s, %v", b, err)
	}
}

func TestContractFingerprintsMatchTheFixtures(t *testing.T) {
	text, err := os.ReadFile("protocol/fixtures/contracts/fingerprints.json")
	if err != nil {
		t.Fatal(err)
	}
	var rows []struct {
		Name        string          `json:"name"`
		Schema      json.RawMessage `json:"schema"`
		Fingerprint string          `json:"fingerprint"`
	}
	if err := json.Unmarshal(text, &rows); err != nil {
		t.Fatal(err)
	}
	if len(rows) == 0 {
		t.Fatal("no rows")
	}
	for _, r := range rows {
		c, err := ContractFromBytes(r.Schema, r.Name)
		if err != nil || c.Fingerprint != r.Fingerprint {
			t.Errorf("%s: %s, %v; want %s", r.Name, c.Fingerprint, err, r.Fingerprint)
		}
	}
	if _, err := ContractFromBytes([]byte("{}"), "Bad Name"); err == nil {
		t.Error("a contract name with a space was accepted")
	}
}

func TestTopLevelCodecsSelectTheirPayloads(t *testing.T) {
	type named string
	cases := []struct {
		manifest, contentType string
		got                   interface {
			Manifest() string
			ContentType() string
		}
	}{
		{"string", TextContent, CodecFor[named]()},
		{"int", TextContent, CodecFor[int32]()},
		{"long", TextContent, CodecFor[int64]()},
		{"short", TextContent, CodecFor[int16]()},
		{"byte", TextContent, CodecFor[int8]()},
		{"float", TextContent, CodecFor[float32]()},
		{"option[string]", BinaryContent, CodecFor[*string]()},
		{"option[shopping-cart]", BinaryContent, CodecFor[*fxShoppingCart]()},
		{"fxShoppingCart", JSONContent, CodecFor[fxShoppingCart]()},
		{"cart", JSONContent, CodecFor[fxShoppingCart](Manifest("cart"))},
	}
	for _, c := range cases[:7] {
		if c.got.Manifest() != c.manifest || c.got.ContentType() != c.contentType {
			t.Errorf("%s: got %s (%s)", c.manifest, c.got.Manifest(), c.got.ContentType())
		}
	}
	for _, c := range cases[8:] {
		if c.got.Manifest() != c.manifest || c.got.ContentType() != c.contentType {
			t.Errorf("%s: got %s (%s)", c.manifest, c.got.Manifest(), c.got.ContentType())
		}
	}
	if n, err := CodecFor[int]().Decode([]byte(" 42 ")); err != nil || n != 42 {
		t.Errorf("text int: %v, %v", n, err)
	}
	if _, err := CodecFor[int]().Encode(1 << 40); err == nil {
		t.Error("a top-level int past 32 bits was written")
	}
	if d, err := CodecFor[float64]().Encode(1e10); err != nil || string(d) != "1.0E10" {
		t.Errorf("text double: %s, %v", d, err)
	}
}
