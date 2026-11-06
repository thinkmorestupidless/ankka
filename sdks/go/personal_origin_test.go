package ankka

import (
	"bytes"
	"encoding/json"
	"testing"
	"time"
)

// FR-027 (feature 042): what a service keeps of a personal field it read from another project's
// envelope stays sealed under the producing project's key, so an erasure there reaches the copy
// here with no notice of its own. A field a handler makes, even with the same value, is the
// service's own.

type fxRegistered struct {
	PlayerID string           `json:"playerId"`
	Email    Personal[string] `json:"email"`
}

type fxCopy struct {
	Email Personal[string] `json:"email"`
}

type originSetup struct {
	keys      *testKeys
	k         *keyring
	clock     *testClock
	published []byte
}

func newOriginSetup(t *testing.T) originSetup {
	t.Helper()
	casino := subjectRef{project: "casino", subject: "player/8c1f"}
	producer := newTestKeys("casino")
	producer.keys[casino] = bytes.Repeat([]byte{1}, 32)
	withKeys(t, producer)
	published, err := CodecFor[fxRegistered](Manifest("registered")).Encode(fxRegistered{"8c1f", PersonalOf(MustSubject("player/8c1f"), "ada@example.com")})
	if err != nil {
		t.Fatal(err)
	}
	// A consumer in 'brand' holding a grant on 'casino''s subjects.
	consumer := newTestKeys("brand")
	consumer.keys[casino] = producer.keys[casino]
	k, clock := cacheUnderTest(t, consumer)
	t.Cleanup(useKeyring(k))
	return originSetup{keys: consumer, k: k, clock: clock, published: published}
}

func envelopeOf(t *testing.T, data []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	return m["email"].(map[string]any)
}

func TestAFieldReadFromAnotherProjectIsKeptUnderThatProjectsKey(t *testing.T) {
	s := newOriginSetup(t)
	read, err := CodecFor[fxRegistered](Manifest("registered")).Decode(s.published)
	if err != nil {
		t.Fatal(err)
	}
	kept, err := CodecFor[fxCopy](Manifest("copy")).Encode(fxCopy{read.Email})
	if err != nil {
		t.Fatal(err)
	}
	if e := envelopeOf(t, kept); e["project"] != "casino" || e["c"] == nil || bytes.Contains(kept, []byte("ada@example.com")) {
		t.Fatalf("kept %s", kept)
	}
	if _, made := s.keys.keys[subjectRef{project: "brand", subject: "player/8c1f"}]; made {
		t.Fatal("this project made a key of its own for the subject")
	}
	again, err := CodecFor[fxCopy](Manifest("copy")).Decode(kept)
	if v, ok := again.Email.Value(); err != nil || !ok || v != "ada@example.com" {
		t.Fatalf("read back %v %v %v", v, ok, err)
	}
}

func TestAnErasureInTheProducingProjectReachesTheKeptCopy(t *testing.T) {
	s := newOriginSetup(t)
	read, _ := CodecFor[fxRegistered](Manifest("registered")).Decode(s.published)
	kept, err := CodecFor[fxCopy](Manifest("copy")).Encode(fxCopy{read.Email})
	if err != nil {
		t.Fatal(err)
	}
	// 'casino' erases the subject; no notice reaches 'brand'.
	casino := subjectRef{project: "casino", subject: "player/8c1f"}
	delete(s.keys.keys, casino)
	s.keys.erased[casino] = true
	s.clock.advance(31 * time.Second)
	again, err := CodecFor[fxCopy](Manifest("copy")).Decode(kept)
	if err != nil || !again.Email.Erased() {
		t.Fatalf("the kept copy read %v (%v) after the producing project's erasure", again.Email, err)
	}
	// Written again now, it is written erased.
	rewritten, err := CodecFor[fxCopy](Manifest("copy")).Encode(fxCopy{read.Email})
	if e := envelopeOf(t, rewritten); err != nil || e["project"] != "casino" || e["c"] != nil {
		t.Fatalf("rewritten %s (%v)", rewritten, err)
	}
}

func TestAFieldAHandlerMakesWithTheSameValueIsTheServicesOwn(t *testing.T) {
	s := newOriginSetup(t)
	read, _ := CodecFor[fxRegistered](Manifest("registered")).Decode(s.published)
	v, _ := read.Email.Value()
	made := PersonalOf(read.Email.Subject(), v)
	kept, err := CodecFor[fxCopy](Manifest("copy")).Encode(fxCopy{made})
	if e := envelopeOf(t, kept); err != nil || e["project"] != "brand" {
		t.Fatalf("kept %s (%v)", kept, err)
	}
}
