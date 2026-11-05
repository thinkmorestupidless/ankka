package ankka

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// The personal envelope, byte for byte, against protocol/fixtures/personal/ (feature 042, SC-008).

func readPersonalFixture(t *testing.T, name string, into any) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("protocol/fixtures/personal", name))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, into); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
}

type fxEnvelopeCase struct {
	Name      string          `json:"name"`
	Key       string          `json:"key"`
	Nonce     string          `json:"nonce"`
	Project   string          `json:"project"`
	Subject   string          `json:"subject"`
	Manifest  string          `json:"manifest"`
	Plaintext string          `json:"plaintext"`
	Envelope  json.RawMessage `json:"envelope"`
}

type fxEnvelopes struct {
	Cases  []fxEnvelopeCase `json:"cases"`
	Erased []fxEnvelopeCase `json:"erased"`
	// Corrupt cases name the key and manifest they are read under.
	Corrupt        []fxEnvelopeCase `json:"corrupt"`
	UnknownVersion json.RawMessage  `json:"unknown-version"`
}

// testKeys is a key source answering from what a test set, counting its fetches.
type testKeys struct {
	mu      sync.Mutex
	own     string
	keys    map[subjectRef][]byte
	erased  map[subjectRef]bool
	fetches int
}

func newTestKeys(own string) *testKeys {
	return &testKeys{own: own, keys: map[subjectRef][]byte{}, erased: map[subjectRef]bool{}}
}

// fetch answers ref as the sidecar does: an empty project is the own one, and every answer names
// its project.
func (k *testKeys) fetch(_ context.Context, ref subjectRef, create bool) (keyAnswer, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.fetches++
	if ref.project == "" {
		ref.project = k.own
	}
	if k.erased[ref] {
		return keyAnswer{erased: true, project: ref.project}, nil
	}
	if key, ok := k.keys[ref]; ok {
		return keyAnswer{key: key, project: ref.project}, nil
	}
	if !create {
		return keyAnswer{erased: true, project: ref.project}, nil
	}
	key := bytes.Repeat([]byte{byte(len(k.keys) + 7)}, 32)
	k.keys[ref] = key
	return keyAnswer{key: key, project: ref.project}, nil
}

func b64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// withKeys makes source the process's keyring for the test.
func withKeys(t *testing.T, source keySource) *keyring {
	t.Helper()
	k := newKeyring(source)
	t.Cleanup(useKeyring(k))
	return k
}

// withNonce fixes the nonce every envelope is sealed with for the test.
func withNonce(t *testing.T, nonce []byte) {
	t.Helper()
	before := personalNonce
	personalNonce = func() ([]byte, error) { return nonce, nil }
	t.Cleanup(func() { personalNonce = before })
}

type fxAddress struct {
	Line1 string `json:"line1"`
	City  string `json:"city"`
}

// roundTrip writes value under the case's key, nonce and manifest, compares the bytes with the
// fixture's envelope, and reads them back.
func roundTrip[T any](t *testing.T, c fxEnvelopeCase, value T) {
	t.Helper()
	keys := newTestKeys(c.Project)
	keys.keys[subjectRef{c.Project, c.Subject}] = b64(t, c.Key)
	withKeys(t, keys)
	withNonce(t, b64(t, c.Nonce))
	codec := CodecFor[Personal[T]](Manifest(c.Manifest))
	written, err := codec.Encode(PersonalOf(MustSubject(c.Subject), value))
	if err != nil {
		t.Fatalf("%s: %v", c.Name, err)
	}
	var want bytes.Buffer
	if err := json.Compact(&want, c.Envelope); err != nil {
		t.Fatal(err)
	}
	if string(written) != want.String() {
		t.Fatalf("%s:\n wrote %s\n want  %s", c.Name, written, want.String())
	}
	read, err := codec.Decode([]byte(c.Envelope))
	if err != nil {
		t.Fatalf("%s: reading the fixture: %v", c.Name, err)
	}
	got, ok := read.Value()
	if !ok || read.Subject().String() != c.Subject || fmt.Sprint(got) != fmt.Sprint(value) {
		t.Fatalf("%s: read back %v %v (present %v)", c.Name, read.Subject(), got, ok)
	}
	// The field's own JSON is what was sealed.
	plain, err := CodecFor[T](Manifest("x")).Encode(value)
	if _, isString := any(value).(string); isString {
		plain, err = json.Marshal(value)
	}
	if err != nil || string(plain) != c.Plaintext {
		t.Fatalf("%s: the plaintext is %s, the fixture's %s (%v)", c.Name, plain, c.Plaintext, err)
	}
}

func TestEveryPersonalEnvelopeIsWrittenExactlyAndReadBack(t *testing.T) {
	var fx fxEnvelopes
	readPersonalFixture(t, "envelopes.json", &fx)
	if len(fx.Cases) != 4 {
		t.Fatalf("the fixture has %d cases", len(fx.Cases))
	}
	for _, c := range fx.Cases {
		t.Run(c.Name, func(t *testing.T) {
			switch c.Name {
			case "a string", "unicode and every allowed subject character":
				var s string
				_ = json.Unmarshal([]byte(c.Plaintext), &s)
				roundTrip(t, c, s)
			case "a date":
				var s string
				_ = json.Unmarshal([]byte(c.Plaintext), &s)
				d, err := ParseLocalDate(s)
				if err != nil {
					t.Fatal(err)
				}
				roundTrip(t, c, d)
			case "a record":
				roundTrip(t, c, fxAddress{Line1: "1 High St", City: "Leeds"})
			default:
				t.Fatalf("a case no test reads: %s", c.Name)
			}
		})
	}
}

func TestAnErasedEnvelopeIsWrittenAndReadAsErased(t *testing.T) {
	var fx fxEnvelopes
	readPersonalFixture(t, "envelopes.json", &fx)
	for _, c := range fx.Erased {
		keys := newTestKeys(c.Project)
		k := withKeys(t, keys)
		// An erased field fetches no key, so the process learns its project from an earlier answer.
		if _, err := k.prefetch([]subjectRef{{subject: "player/earlier"}}, true); err != nil {
			t.Fatal(err)
		}
		keys.fetches = 0
		codec := CodecFor[Personal[string]](Manifest("player-registered"))
		read, err := codec.Decode([]byte(c.Envelope))
		if err != nil || !read.Erased() || read.Subject().String() != c.Subject {
			t.Fatalf("%s: read %v erased %v (%v)", c.Name, read, read.Erased(), err)
		}
		if v, ok := read.Value(); ok || v != "" {
			t.Fatalf("an erased field has a value: %q", v)
		}
		if read.OrElse("gone") != "gone" {
			t.Fatal("OrElse of an erased field is not its default")
		}
		written, err := codec.Encode(read)
		var want bytes.Buffer
		_ = json.Compact(&want, c.Envelope)
		if err != nil || string(written) != want.String() {
			t.Fatalf("%s: an erased field wrote %s (%v), want %s", c.Name, written, err, want.String())
		}
		if keys.fetches != 0 {
			t.Fatalf("an envelope with no c asked for %d keys", keys.fetches)
		}
	}
}

func TestACorruptEnvelopeFailsAsCorruptNeverErased(t *testing.T) {
	var fx fxEnvelopes
	readPersonalFixture(t, "envelopes.json", &fx)
	if len(fx.Corrupt) != 3 {
		t.Fatalf("the fixture has %d corrupt cases", len(fx.Corrupt))
	}
	for _, c := range fx.Corrupt {
		var env struct {
			Project string `json:"project"`
			Subject string `json:"subject"`
		}
		_ = json.Unmarshal(c.Envelope, &env)
		keys := newTestKeys(env.Project)
		keys.keys[subjectRef{env.Project, env.Subject}] = b64(t, c.Key)
		withKeys(t, keys)
		_, err := CodecFor[Personal[string]](Manifest(c.Manifest)).Decode([]byte(c.Envelope))
		r, ok := AsRefusal(err)
		if !ok || r.Details[PersonalErrorKey] != "corrupt" || !strings.Contains(r.Message, "failed authentication") ||
			!strings.Contains(r.Message, "'"+env.Subject+"'") {
			t.Fatalf("%s: answered %v", c.Name, err)
		}
	}
	keys := newTestKeys("brand")
	withKeys(t, keys)
	_, err := CodecFor[Personal[string]](Manifest("player-registered")).Decode([]byte(fx.UnknownVersion))
	if r, ok := AsRefusal(err); !ok || r.Details[PersonalErrorKey] != "corrupt" || !strings.Contains(r.Message, "version is 2") {
		t.Fatalf("an unknown version answered %v", err)
	}
}

func TestEverySubjectIsHeldToTheRule(t *testing.T) {
	var fx struct {
		Message string   `json:"message"`
		Valid   []string `json:"valid"`
		Invalid []string `json:"invalid"`
	}
	readPersonalFixture(t, "subjects.json", &fx)
	if fx.Message != SubjectRule {
		t.Fatalf("the rule is %q, the fixture's %q", SubjectRule, fx.Message)
	}
	for _, s := range fx.Valid {
		if got, err := Subject(s); err != nil || got.String() != s {
			t.Fatalf("%q was refused: %v", s, err)
		}
	}
	for _, s := range fx.Invalid {
		if _, err := Subject(s); err == nil || !strings.Contains(err.Error(), fx.Message) {
			t.Fatalf("%q was not refused with the rule: %v", s, err)
		}
	}
	// The refusal names the rule, never the value: a caller who passed an email as the subject
	// would otherwise put the email in an error message.
	if _, err := Subject("ada@example.com"); err == nil || strings.Contains(err.Error(), "ada@example.com") {
		t.Fatalf("the refusal repeats the value: %v", err)
	}
}

type fxEdgeRecord struct {
	Card Personal[fxAddress] `json:"card"`
}

func TestThePersonalFieldAtTheEdgeIsItsValueAndSubject(t *testing.T) {
	var fx struct {
		Cases []struct {
			Name    string          `json:"name"`
			Subject string          `json:"subject"`
			Value   json.RawMessage `json:"value"`
			Edge    json.RawMessage `json:"edge"`
		} `json:"cases"`
	}
	readPersonalFixture(t, "edge.json", &fx)
	withKeys(t, noKeyring{}) // the edge asks no keyring
	for _, c := range fx.Cases {
		var want bytes.Buffer
		_ = json.Compact(&want, c.Edge)
		switch c.Name {
		case "present", "erased":
			codec := routeCodec[Personal[string]]()
			read, err := codec.Decode(c.Edge)
			if err != nil || read.Subject().String() != c.Subject || read.Erased() != (c.Name == "erased") {
				t.Fatalf("%s: read %v erased %v (%v)", c.Name, read, read.Erased(), err)
			}
			if c.Name == "present" {
				if v, _ := read.Value(); `"`+v+`"` != string(c.Value) {
					t.Fatalf("%s: value %q", c.Name, v)
				}
			}
			written, err := codec.Encode(read)
			if err != nil || string(written) != want.String() {
				t.Fatalf("%s: wrote %s (%v), want %s", c.Name, written, err, want.String())
			}
		case "present record":
			codec := routeCodec[Personal[fxAddress]]()
			written, err := codec.Encode(PersonalOf(MustSubject(c.Subject), fxAddress{"1 High St", "Leeds"}))
			if err != nil || string(written) != want.String() {
				t.Fatalf("%s: wrote %s (%v), want %s", c.Name, written, err, want.String())
			}
			read, err := codec.Decode(c.Edge)
			if v, ok := read.Value(); err != nil || !ok || v.City != "Leeds" {
				t.Fatalf("%s: read %+v (%v)", c.Name, v, err)
			}
		default:
			t.Fatalf("a case no test reads: %s", c.Name)
		}
	}
	// Inside a body, at any depth.
	body := routeCodec[fxEdgeRecord]()
	written, err := body.Encode(fxEdgeRecord{Card: PersonalOf(MustSubject("cardholder/77a0"), fxAddress{"1 High St", "Leeds"})})
	if err != nil || string(written) != `{"card":{"subject":"cardholder/77a0","value":{"line1":"1 High St","city":"Leeds"}}}` {
		t.Fatalf("a body wrote %s (%v)", written, err)
	}
	if _, err := body.Decode([]byte(`{"card":{"subject":"not a subject","value":{"line1":"x","city":"y"}}}`)); err == nil ||
		!strings.Contains(err.Error(), SubjectRule) {
		t.Fatalf("a body naming no subject was read: %v", err)
	}
}

func TestAPersonalFieldNeverPrintsItsValue(t *testing.T) {
	p := PersonalOf(MustSubject("player/8c1f"), "ada@example.com")
	for _, format := range []string{"%v", "%+v", "%#v", "%s", "%q"} {
		if got := fmt.Sprintf(format, p); strings.Contains(got, "ada") {
			t.Fatalf("%s printed %s", format, got)
		}
	}
	if p.String() != "Personal(player/8c1f)" {
		t.Fatalf("String is %q", p.String())
	}
	type holder struct{ Email Personal[string] }
	if got := fmt.Sprintf("%+v", holder{p}); strings.Contains(got, "ada") {
		t.Fatalf("a struct printed %s", got)
	}
}

type pPlayer struct {
	ID      string                       `json:"id"`
	Email   Personal[string]             `json:"email"`
	Others  []Personal[string]           `json:"others"`
	ByName  map[string]Personal[string]  `json:"byName"`
	Maybe   *Personal[LocalDate]         `json:"maybe"`
	Address Personal[fxAddress]          `json:"address"`
	Gone    map[string]Personal[float64] `json:"gone"`
}

func TestKeysAreFetchedOnceBeforeTheCodecAndDroppedOnANotice(t *testing.T) {
	keys := newTestKeys("brand")
	k := withKeys(t, keys)
	codec := CodecFor[pPlayer](Manifest("player"))
	ada, bob := MustSubject("player/ada"), MustSubject("player/bob")
	v := pPlayer{
		ID: "1", Email: PersonalOf(ada, "ada@example.com"),
		Others:  []Personal[string]{PersonalOf(bob, "bob@example.com"), PersonalOf(ada, "a2")},
		ByName:  map[string]Personal[string]{"x": PersonalOf(bob, "b2")},
		Address: PersonalOf(ada, fxAddress{"1 High St", "Leeds"}),
	}
	data, err := codec.Encode(v)
	if err != nil {
		t.Fatal(err)
	}
	if keys.fetches != 2 {
		t.Fatalf("writing two subjects fetched %d keys", keys.fetches)
	}
	if bytes.Contains(data, []byte("ada@")) || bytes.Contains(data, []byte("Leeds")) || !bytes.Contains(data, []byte(`"$personal":1`)) {
		t.Fatalf("the value is in the clear: %s", data)
	}
	read, err := codec.Decode(data)
	if err != nil {
		t.Fatal(err)
	}
	if keys.fetches != 2 {
		t.Fatalf("reading what was just written fetched again: %d", keys.fetches)
	}
	if e, _ := read.Email.Value(); e != "ada@example.com" || read.Others[0].OrElse("") != "bob@example.com" ||
		read.ByName["x"].OrElse("") != "b2" || read.Maybe != nil || read.Address.OrElse(fxAddress{}).City != "Leeds" {
		t.Fatalf("read back %+v", read)
	}

	// A notice drops the key: the next read asks again, and the destroyed key reads as erased.
	k.drop("brand", "player/bob")
	keys.mu.Lock()
	keys.erased[subjectRef{"brand", "player/bob"}] = true
	keys.mu.Unlock()
	read, err = codec.Decode(data)
	if err != nil {
		t.Fatalf("an erased subject failed decoding: %v", err)
	}
	if keys.fetches != 3 || !read.Others[0].Erased() || !read.ByName["x"].Erased() || read.Email.Erased() {
		t.Fatalf("after the notice: %d fetches, %+v", keys.fetches, read)
	}

	// A write for the erased subject is refused, and nothing is written.
	out, err := codec.Encode(v)
	r, ok := AsRefusal(err)
	if !ok || out != nil || r.Code != Conflict || r.Details[PersonalErrorKey] != "erased" ||
		r.Message != "the data subject 'player/bob' is erased; nothing was written" {
		t.Fatalf("a write for an erased subject answered %s %v", out, err)
	}
	// Writing the erased fields back is allowed: the envelope without c.
	read.Email = PersonalOf(ada, "new@example.com")
	if again, err := codec.Encode(read); err != nil || !bytes.Contains(again, []byte(`{"$personal":1,"project":"brand","subject":"player/bob"}`)) {
		t.Fatalf("an erased field written back: %s (%v)", again, err)
	}
}

func TestWithNoKeyringAPersonalFieldIsUnavailable(t *testing.T) {
	withKeys(t, noKeyring{})
	codec := CodecFor[pPlayer](Manifest("player"))
	_, err := codec.Encode(pPlayer{Email: PersonalOf(MustSubject("player/ada"), "a")})
	if r, ok := AsRefusal(err); !ok || r.Code != Unavailable || !strings.HasPrefix(r.Message, "the keyring is unavailable") {
		t.Fatalf("answered %v", err)
	}
	// A value with no personal field in it needs no keyring.
	if _, err := codec.Encode(pPlayer{Email: PersonalOf(MustSubject("player/ada"), "a")}); err == nil {
		t.Fatal("not refused")
	}
	type plain struct {
		Maybe *Personal[string] `json:"maybe"`
	}
	if _, err := CodecFor[plain]().Encode(plain{}); err != nil {
		t.Fatalf("a nil personal field asked the keyring: %v", err)
	}
	if _, err := CodecFor[Personal[string]](Manifest("m")).Encode(Personal[string]{}); err == nil {
		t.Fatal("a field with no subject was written")
	}
}

func TestTheSidecarsKeysAreAskedOverTheProtocol(t *testing.T) {
	ctx := startFake(t, &fakeSidecar{}, nil)
	k := newKeyring(&sidecarKeys{client: ClientFrom(ctx)})
	if _, err := k.ownProject(); err == nil {
		t.Fatal("a process no answer told its project wrote under one")
	}
	_, err := k.source.fetch(context.Background(), subjectRef{"brand", "player/8c1f"}, false)
	if r, ok := AsRefusal(err); !ok || r.Code != Unavailable || !strings.Contains(r.Message, "1.23") {
		t.Fatalf("a sidecar with no keys answered %v", err)
	}
}

// GetSubjectKey answers one key for every subject, naming keyProject as the project it is for; a
// fake with no keyProject is a sidecar before 1.23.
func (f *fakeSidecar) GetSubjectKey(_ context.Context, r *pb.SubjectKeyRequest) (*pb.SubjectKeyReply, error) {
	if f.keyProject == "" {
		return nil, status.Error(codes.Unimplemented, "unknown method GetSubjectKey")
	}
	f.mu.Lock()
	f.keyAsked = append(f.keyAsked, r.GetProject())
	f.mu.Unlock()
	project := r.GetProject()
	if project == "" {
		project = f.keyProject
	}
	return &pb.SubjectKeyReply{Reply: &pb.SubjectKeyReply_Key{Key: bytes.Repeat([]byte{9}, 32)}, Project: project}, nil
}

func TestAProcessSealsUnderTheProjectItsKeyAnswerNames(t *testing.T) {
	f := &fakeSidecar{keyProject: "brand"}
	ctx := startFake(t, f, nil)
	k := withKeys(t, &sidecarKeys{client: ClientFrom(ctx)})
	codec := CodecFor[pPlayer](Manifest("player"))
	ada := MustSubject("player/ada")
	out, err := codec.Encode(pPlayer{Email: PersonalOf(ada, "ada@example.com"), Address: PersonalOf(ada, fxAddress{"1 High St", "Leeds"})})
	if err != nil {
		t.Fatalf("a write through the sidecar: %v", err)
	}
	var doc struct {
		Email struct {
			Project string `json:"project"`
			Subject string `json:"subject"`
		} `json:"email"`
	}
	if err := json.Unmarshal(out, &doc); err != nil || doc.Email.Project != "brand" || doc.Email.Subject != "player/ada" {
		t.Fatalf("the envelope does not name the answered project: %s (%v)", out, err)
	}
	if own, err := k.ownProject(); err != nil || own != "brand" || !k.cached("brand", "player/ada") {
		t.Fatalf("the process did not learn its project: %q %v", own, err)
	}
	// The project is in the associated data too: the envelope opens under it.
	back, err := codec.Decode(out)
	if err != nil {
		t.Fatalf("read back: %v", err)
	}
	if v, ok := back.Email.Value(); !ok || v != "ada@example.com" {
		t.Fatalf("read back %+v", back)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.keyAsked) != 1 || f.keyAsked[0] != "" {
		t.Fatalf("a write for the own project asked for %q", f.keyAsked)
	}
}

type pEvent interface{ pEvent() }
type pRegistered struct {
	Email Personal[string] `json:"email"`
}

func (pRegistered) pEvent() {}

var pEvents = Cases[pEvent](Case[pEvent, pRegistered]("Registered"))

func TestAServiceWithAPersonalFieldOrAHandlerNeeds123(t *testing.T) {
	entity := EventSourced[string, pEvent]("players", pEvents,
		func(string) string { return "" },
		func(s string, _ pEvent) string { return s },
		Query("get", func(_ context.Context, s string, _ Unit) (ReadOnlyEffect[string], error) { return Reply(s), nil }),
	)
	svc := NewService(entity)
	if len(svc.Problems()) != 0 {
		t.Fatal(svc.Problems())
	}
	why := svc.decls.refusal("1.22")
	if !strings.Contains(why, "personal fields need protocol 1.23") || !strings.Contains(why, "players") {
		t.Fatalf("a 1.22 runtime was answered %q", why)
	}
	if why := svc.decls.refusal("1.23"); why != "" {
		t.Fatalf("a 1.23 runtime was refused: %s", why)
	}
	if NewService(plainEntity()).decls.refusal("1.22") != "" {
		t.Fatal("a service with no personal field needs 1.23")
	}

	h := NewService(plainEntity()).ErasureHandler(func(ctx context.Context, s DataSubject) (ErasureReport, error) {
		return ErasureReport{}, nil
	})
	if !h.Spec().GetErasureHandler() || !strings.Contains(h.decls.refusal("1.22"), "an erasure handler needs protocol 1.23") {
		t.Fatalf("the handler: spec %v, refusal %q", h.Spec().GetErasureHandler(), h.decls.refusal("1.22"))
	}
	h.ErasureHandler(func(context.Context, DataSubject) (ErasureReport, error) { return ErasureReport{}, nil })
	if len(h.Problems()) != 1 || !strings.Contains(h.Problems()[0].Message, "one erasure handler") {
		t.Fatalf("a second handler: %v", h.Problems())
	}
}

func plainEntity() *KeyValueEntity[string] {
	return KeyValue[string]("plain", func(string) string { return "" },
		Query("get", func(_ context.Context, s string, _ Unit) (ReadOnlyEffect[string], error) { return Reply(s), nil }),
	)
}

func TestTheErasureHandlerIsToldTheSubjectAndReportsBack(t *testing.T) {
	var seen []string
	svc := NewService(plainEntity()).ErasureHandler(func(ctx context.Context, s DataSubject) (ErasureReport, error) {
		e, _ := ErasureFrom(ctx)
		seen = append(seen, fmt.Sprintf("%s %d %v", s, e.Seq, e.Rerun))
		if s.String() == "player/bad" {
			return ErasureReport{}, fmt.Errorf("the bucket refused")
		}
		return ErasureReport{ObjectsErased: 3, Finality: After(7 * 24 * time.Hour)}, nil
	})
	reply, err := svc.decls.runErasure(context.Background(), &pb.ErasureCall{Subject: "player/8c1f", ErasureSeq: 12, Rerun: true})
	if err != nil || reply.GetObjectsErased() != 3 || reply.GetFinality() != "after:PT168H" {
		t.Fatalf("answered %v (%v)", reply, err)
	}
	if seen[0] != "player/8c1f 12 true" {
		t.Fatalf("the handler saw %v", seen)
	}
	if _, err := svc.decls.runErasure(context.Background(), &pb.ErasureCall{Subject: "player/bad"}); err == nil {
		t.Fatal("a failing handler answered no error")
	}
	if _, err := svc.decls.runErasure(context.Background(), &pb.ErasureCall{Subject: "no subject"}); err == nil {
		t.Fatal("an invalid subject reached the handler")
	}
	if Immediate.wire() != "immediate" {
		t.Fatal(Immediate.wire())
	}
}
