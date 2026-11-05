package ankka

import (
	"bytes"
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// FR-032 (feature 042): a replicated entity's keys are its home's. A copy at a follower tracks the
// home from Init.home, each event's installation and Command.home, asks for every key it decodes
// or seals with that home named, never asks for one to be made there, and keeps another home's keys
// apart from its own installation's. A home's keyring that cannot be reached fails the copy's read;
// it is never read as erased.

type fhMember struct {
	Email Personal[string] `json:"email"`
}

type fhEvent interface{ fhEvent() }

type fhJoined struct {
	Email Personal[string] `json:"email"`
}

func (fhJoined) fhEvent() {}

var fhEvents = Cases[fhEvent](Case[fhEvent, fhJoined]("Joined"))

var fhEntity = EventSourced[fhMember, fhEvent]("fh-member", fhEvents,
	func(string) fhMember { return fhMember{} },
	func(s fhMember, e fhEvent) fhMember {
		if j, ok := e.(fhJoined); ok {
			s.Email = j.Email
		}
		return s
	},
	Query("email", func(_ context.Context, s fhMember, _ Unit) (ReadOnlyEffect[string], error) {
		if v, ok := s.Email.Value(); ok {
			return Reply(v), nil
		}
		return Reply("erased"), nil
	}),
	Replicated(),
)

// asked is one fetch a key source was asked for.
type asked struct {
	ref    subjectRef
	create bool
}

// homes is a key source standing for the sidecar beside a follower at "b": its own keyring under
// the empty home, installation "a"'s through the relay; a home listed in down cannot be reached.
type homes struct {
	*testKeys
	mu    sync.Mutex
	down  map[string]bool
	asked []asked
}

func (h *homes) fetch(ctx context.Context, ref subjectRef, create bool) (keyAnswer, error) {
	h.mu.Lock()
	h.asked = append(h.asked, asked{ref, create})
	down := h.down[ref.home]
	h.mu.Unlock()
	if down {
		return keyAnswer{}, keyringUnavailable("the keyring of installation '" + ref.home + "' cannot be reached")
	}
	return h.testKeys.fetch(ctx, ref, create)
}

const fhSubject = "player/8c1f"

// fhFollower is the keys of a follower at "b" of an entity whose home is "a": "a" keeps the
// subject's key, and "b"'s own keyring has a different key of the same subject.
func fhFollower(t *testing.T) (*homes, *keyring, *testClock, *pb.Payload) {
	t.Helper()
	keys := newTestKeys("club")
	keys.keys[subjectRef{project: "club", subject: fhSubject, home: "a"}] = bytes.Repeat([]byte{1}, 32)
	keys.keys[subjectRef{project: "club", subject: fhSubject}] = bytes.Repeat([]byte{2}, 32)
	source := &homes{testKeys: keys, down: map[string]bool{}}
	k, clock := cacheUnderTest(t, source)
	t.Cleanup(useKeyring(k))
	// The event as the home wrote it, sealed under the home's key.
	joined, err := payloadOf(atHome(fhEntity.eventCodec, "a"), fhEvent(fhJoined{PersonalOf(MustSubject(fhSubject), "ada@example.com")}))
	if err != nil {
		t.Fatal(err)
	}
	// Nothing the writing held is kept: the follower asks for what it reads.
	k.dropAll()
	source.asked = nil
	return source, k, clock, joined
}

func fhQuery(home string) *pb.EventSourcedIn_Command {
	return &pb.EventSourcedIn_Command{Id: 1, Name: "email", Installation: "b", Home: home, SnapshotRequested: true}
}

func TestAFollowerReadsAndWritesWithItsHomesKeysAndMakesNone(t *testing.T) {
	source, _, _, joined := fhFollower(t)
	ctx := context.Background()
	inst := fhEntity.instance("m-1")
	if err := inst.Recover(ctx, &pb.EventSourcedIn_Init{ComponentId: "fh-member", EntityId: "m-1", Installation: "b"}); err != nil {
		t.Fatal(err)
	}
	if err := inst.Apply(ctx, &pb.EventSourcedIn_Event{Sequence: 1, Payload: joined, Installation: "a"}); err != nil {
		t.Fatalf("the follower could not read the home's event: %v", err)
	}
	reply, err := inst.Command(ctx, fhQuery("a"))
	if err != nil {
		t.Fatal(err)
	}
	var got string
	if got, err = valueOf(CodecFor[string](), reply.GetOutcome().GetReply().GetPayload()); err != nil || got != "ada@example.com" {
		t.Fatalf("the follower read %q (%v), not the home's field", got, err)
	}
	if len(source.asked) == 0 {
		t.Fatal("no key was asked for")
	}
	for _, a := range source.asked {
		if a.ref.home != "a" || a.create {
			t.Fatalf("a key was asked of home %q with create %v, not of the home 'a' read only", a.ref.home, a.create)
		}
	}
	if len(source.keys) != 3 { // player/0, and the subject's at "a" and at "b"
		t.Fatalf("a key was made: %v", source.keys)
	}

	// Recovered again from the snapshot the follower wrote: Init.home names whose keys it is.
	again := fhEntity.instance("m-1")
	source.asked = nil
	if err := again.Recover(ctx, &pb.EventSourcedIn_Init{ComponentId: "fh-member", EntityId: "m-1", Installation: "b",
		Snapshot: &pb.EventSourcedIn_Snapshot{Sequence: 1, Payload: reply.GetSnapshot()}, Home: "a"}); err != nil {
		t.Fatal(err)
	}
	if v, ok := again.state.Email.Value(); !ok || v != "ada@example.com" {
		t.Fatalf("the snapshot read %v", again.state.Email)
	}

	// A subject the home holds no key for, written at the follower: no key is made, there or here.
	_, err = payloadOf(again.stateCodec(), fhMember{PersonalOf(MustSubject("player/new"), "x")})
	for _, home := range []string{"a", ""} {
		if _, made := source.keys[subjectRef{project: "club", subject: "player/new", home: home}]; made {
			t.Fatalf("a key was made at home %q for a follower's write", home)
		}
	}
	if err == nil {
		t.Fatal("a follower sealed a field under a key its home never made")
	}
}

func TestTwoHomesKeysOfOneSubjectNeverMix(t *testing.T) {
	_, k, _, joined := fhFollower(t)
	// The follower's own key of the subject is held first.
	if _, err := k.prefetch([]subjectRef{{subject: fhSubject}}, false); err != nil {
		t.Fatal(err)
	}
	ev, err := valueOf(atHome(fhEntity.eventCodec, "a"), joined)
	if err != nil {
		t.Fatalf("the home's envelope was opened with another installation's key: %v", err)
	}
	if v, ok := ev.(fhJoined).Email.Value(); !ok || v != "ada@example.com" {
		t.Fatalf("read %v", ev)
	}
	if !k.cached("club", fhSubject) {
		t.Fatal("the own key went")
	}
}

func TestAHomeThatCannotBeReachedFailsTheReadAndIsNeverErased(t *testing.T) {
	source, _, clock, joined := fhFollower(t)
	ctx := context.Background()
	source.down["a"] = true
	inst := fhEntity.instance("m-1")
	if err := inst.Recover(ctx, &pb.EventSourcedIn_Init{ComponentId: "fh-member", EntityId: "m-1", Installation: "b"}); err != nil {
		t.Fatal(err)
	}
	err := inst.Apply(ctx, &pb.EventSourcedIn_Event{Sequence: 1, Payload: joined, Installation: "a"})
	var refusal *RefusalError
	if !errors.As(err, &refusal) || refusal.Code != Unavailable {
		t.Fatalf("recovery with the home unreachable answered %v, not unavailable", err)
	}

	// Recovered while the home answers, then the home goes: the held key lasts no longer than
	// another project's, and the command fails rather than reading the field erased.
	source.down["a"] = false
	inst = fhEntity.instance("m-1")
	_ = inst.Recover(ctx, &pb.EventSourcedIn_Init{ComponentId: "fh-member", EntityId: "m-1", Installation: "b"})
	if err := inst.Apply(ctx, &pb.EventSourcedIn_Event{Sequence: 1, Payload: joined, Installation: "a"}); err != nil {
		t.Fatal(err)
	}
	source.down["a"] = true
	clock.advance(31 * time.Second)
	reply, err := inst.Command(ctx, fhQuery("a"))
	if !errors.As(err, &refusal) || refusal.Code != Unavailable {
		t.Fatalf("a command with the home unreachable answered %v / %v, not unavailable", reply, err)
	}
}
