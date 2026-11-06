package ankka

import (
	"bytes"
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

// The process's key cache (feature 042, FR-020, FR-022): the runtime's rules, on an injected clock.
// A key of the own project is fresh for ttl and served in an outage until outageBound; a key of
// another project for foreignTTL; at most maxKeys are held, the least recently used going first;
// and an answer whose fetch was overtaken by a notice is never believed.

type testClock struct{ at time.Time }

func (c *testClock) now() time.Time          { return c.at }
func (c *testClock) advance(d time.Duration) { c.at = c.at.Add(d) }

// flaky is a key source that fails as unavailable while down.
type flaky struct {
	*testKeys
	down bool
}

func (f *flaky) fetch(ctx context.Context, ref subjectRef, create bool) (keyAnswer, error) {
	if f.down {
		return keyAnswer{}, keyringUnavailable("the keyring did not answer")
	}
	return f.testKeys.fetch(ctx, ref, create)
}

func cacheUnderTest(t *testing.T, source keySource) (*keyring, *testClock) {
	t.Helper()
	k := newKeyring(source)
	clock := &testClock{at: time.Unix(1_000_000, 0)}
	k.now = clock.now
	// The own project is learned from the first own answer.
	if _, err := k.prefetch([]subjectRef{{subject: "player/0"}}, true); err != nil {
		t.Fatal(err)
	}
	return k, clock
}

func readKey(t *testing.T, k *keyring, project, subject string) keyAnswer {
	t.Helper()
	ref := subjectRef{project: project, subject: subject}
	got, err := k.prefetch([]subjectRef{ref}, false)
	if err != nil {
		t.Fatalf("read %v: %v", ref, err)
	}
	return got[ref]
}

func TestAnOwnProjectsKeyIsAskedForAgainAfterTheTTL(t *testing.T) {
	keys := newTestKeys("brand")
	keys.keys[subjectRef{project: "brand", subject: "player/1"}] = bytes.Repeat([]byte{1}, 32)
	k, clock := cacheUnderTest(t, keys)
	before := keys.fetches
	readKey(t, k, "brand", "player/1")
	clock.advance(4*time.Minute + 59*time.Second)
	readKey(t, k, "brand", "player/1")
	if keys.fetches-before != 1 {
		t.Fatalf("asked %d times within the ttl, want 1", keys.fetches-before)
	}
	clock.advance(2 * time.Second)
	readKey(t, k, "brand", "player/1")
	if keys.fetches-before != 2 {
		t.Fatalf("asked %d times past the ttl, want 2", keys.fetches-before)
	}
}

func TestAnotherProjectsKeyIsHeldForTheForeignTTLOnly(t *testing.T) {
	keys := newTestKeys("brand")
	ref := subjectRef{project: "casino", subject: "player/1"}
	keys.keys[ref] = bytes.Repeat([]byte{2}, 32)
	k, clock := cacheUnderTest(t, keys)
	before := keys.fetches
	if a := readKey(t, k, "casino", "player/1"); a.erased {
		t.Fatal("read as erased before the erasure")
	}
	clock.advance(29 * time.Second)
	readKey(t, k, "casino", "player/1")
	if keys.fetches-before != 1 {
		t.Fatalf("asked %d times within the foreign ttl, want 1", keys.fetches-before)
	}
	// Erased in the producing project: no notice of it reaches this process.
	delete(keys.keys, ref)
	keys.erased[ref] = true
	clock.advance(2 * time.Second)
	if a := readKey(t, k, "casino", "player/1"); !a.erased {
		t.Fatal("another project's key outlived the foreign ttl")
	}
}

func TestTheCacheHoldsAtMostMaxKeysAndEvictsTheLeastRecentlyUsedOne(t *testing.T) {
	keys := newTestKeys("brand")
	for _, s := range []string{"player/1", "player/2", "player/3"} {
		keys.keys[subjectRef{project: "brand", subject: s}] = bytes.Repeat([]byte{3}, 32)
	}
	k, _ := cacheUnderTest(t, keys)
	k.settings.maxKeys = 2
	readKey(t, k, "brand", "player/1")
	readKey(t, k, "brand", "player/2")
	readKey(t, k, "brand", "player/1")
	readKey(t, k, "brand", "player/3")
	if len(k.cache) != 2 || !k.cached("brand", "player/1") || !k.cached("brand", "player/3") || k.cached("brand", "player/2") {
		t.Fatalf("held %d keys; player/1 %v, player/2 %v, player/3 %v", len(k.cache),
			k.cached("brand", "player/1"), k.cached("brand", "player/2"), k.cached("brand", "player/3"))
	}
}

func TestInAnOutageAnOwnKeyServesUntilTheOutageBound(t *testing.T) {
	source := &flaky{testKeys: newTestKeys("brand")}
	source.keys[subjectRef{project: "brand", subject: "player/1"}] = bytes.Repeat([]byte{4}, 32)
	k, clock := cacheUnderTest(t, source)
	k.settings.ttl, k.settings.outageBound = 20*time.Second, 50*time.Second
	readKey(t, k, "brand", "player/1")
	source.down = true
	clock.advance(30 * time.Second)
	if a := readKey(t, k, "brand", "player/1"); a.erased || a.key == nil {
		t.Fatal("an own key did not serve within the outage bound")
	}
	clock.advance(25 * time.Second)
	if _, err := k.prefetch([]subjectRef{{project: "brand", subject: "player/1"}}, false); err == nil || !strings.Contains(err.Error(), "unavailable") {
		t.Fatalf("past the outage bound: %v, want unavailable", err)
	}
}

func TestInAnOutageAnotherProjectsKeyDoesNotOutliveTheForeignTTL(t *testing.T) {
	source := &flaky{testKeys: newTestKeys("brand")}
	source.keys[subjectRef{project: "casino", subject: "player/1"}] = bytes.Repeat([]byte{5}, 32)
	k, clock := cacheUnderTest(t, source)
	readKey(t, k, "casino", "player/1")
	source.down = true
	clock.advance(31 * time.Second)
	if _, err := k.prefetch([]subjectRef{{project: "casino", subject: "player/1"}}, false); err == nil {
		t.Fatal("another project's key served in an outage past the foreign ttl")
	}
}

func TestTheOutageBoundMayNotExceedTheNoticeBound(t *testing.T) {
	s := defaultKeyringSettings
	if err := s.check(); err != nil {
		t.Fatalf("the defaults: %v", err)
	}
	s.outageBound = 61 * time.Second
	if err := s.check(); err == nil || err.Error() != "ankka.keyring.cache.outage-bound (1m1s) must not exceed ankka.keyring.notice-bound (1m0s)" {
		t.Fatalf("got %v", err)
	}
	s = defaultKeyringSettings
	s.foreignTTL = 0
	if err := s.check(); err == nil || !strings.Contains(err.Error(), "cache.foreign-ttl must be positive") {
		t.Fatalf("got %v", err)
	}
	s = defaultKeyringSettings
	s.maxKeys = 0
	if err := s.check(); err == nil || !strings.Contains(err.Error(), "max-keys must be at least 1") {
		t.Fatalf("got %v", err)
	}
}

// held is a key source whose next fetch waits until released, answering the key it held when the
// fetch began.
type held struct {
	*testKeys
	once    sync.Once
	started chan struct{}
	release chan struct{}
}

func (h *held) fetch(ctx context.Context, ref subjectRef, create bool) (keyAnswer, error) {
	first := false
	h.once.Do(func() { first = true })
	if !first {
		return h.testKeys.fetch(ctx, ref, create)
	}
	a, err := h.testKeys.fetch(ctx, ref, create)
	close(h.started)
	<-h.release
	return a, err
}

func TestAFetchInFlightWhenItsSubjectIsDroppedIsNotBelieved(t *testing.T) {
	for _, how := range []string{"subject", "all"} {
		t.Run(how, func(t *testing.T) {
			keys := newTestKeys("brand")
			ref := subjectRef{project: "brand", subject: "player/1"}
			keys.keys[ref] = bytes.Repeat([]byte{6}, 32)
			k, _ := cacheUnderTest(t, keys)
			source := &held{testKeys: keys, started: make(chan struct{}), release: make(chan struct{})}
			k.source = source
			done := make(chan keyAnswer)
			go func() {
				got, err := k.prefetch([]subjectRef{ref}, false)
				if err != nil {
					t.Error(err)
				}
				done <- got[ref]
			}()
			<-source.started
			keys.mu.Lock()
			delete(keys.keys, ref)
			keys.erased[ref] = true
			keys.mu.Unlock()
			if how == "subject" {
				k.drop("brand", "player/1")
			} else {
				k.dropAll()
			}
			close(source.release)
			if a := <-done; !a.erased {
				t.Fatal("the answer overtaken by the notice was believed")
			}
			if k.cached("brand", "player/1") {
				t.Fatal("the destroyed key is held")
			}
		})
	}
}

func TestADropAfterTheAnswerAndBeforeItIsHandedOnLeavesNoKeyHeld(t *testing.T) {
	keys := newTestKeys("brand")
	ref := subjectRef{project: "brand", subject: "player/1"}
	keys.keys[ref] = bytes.Repeat([]byte{7}, 32)
	k, _ := cacheUnderTest(t, keys)
	// The notice arrives once the fetch has found itself not overtaken: the window in which a put
	// made after the check once put the destroyed key back.
	k.afterFetch = func() { k.drop("brand", "player/1") }
	if _, err := k.prefetch([]subjectRef{ref}, false); err != nil {
		t.Fatal(err)
	}
	if k.cached("brand", "player/1") {
		t.Fatal("the key a notice dropped was put back")
	}
}
