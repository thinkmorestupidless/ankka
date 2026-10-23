package ankka

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

type mCart struct {
	Items []string `json:"items"`
}

type mEvent interface{ mEvent() }
type mAdded struct {
	Item string `json:"item"`
}

func (mAdded) mEvent() {}

var mEvents = Cases[mEvent](Case[mEvent, mAdded]("ItemAdded"))

func mApply(s mCart, e mEvent) mCart {
	switch e := e.(type) {
	case mAdded:
		s.Items = append(append([]string(nil), s.Items...), e.Item)
	}
	return s
}

func mCodecs[R any]() esCodecs[mCart, mEvent, R] {
	_ = mEvents
	return esCodecs[mCart, mEvent, R]{state: CodecFor[mCart](), event: CodecFor[mEvent](), reply: CodecFor[R]()}
}

func TestAPersistedReplyIsComputedFromTheStateAfterTheEvents(t *testing.T) {
	eff := Persist[mCart, mEvent, int](mAdded{"a"}, mAdded{"b"}).ThenReply(func(s mCart) int { return len(s.Items) })
	after, reply, err := materialiseEventSourced(eff, mCart{Items: []string{"x"}}, mApply, mCodecs[int](), true)
	if err != nil {
		t.Fatal(err)
	}
	if len(after.Items) != 3 || len(reply.Events) != 2 {
		t.Fatalf("after %+v, %d events", after, len(reply.Events))
	}
	if got := string(reply.Events[0].Data); got != `{"item":"a","type":"ItemAdded"}` && got != `{"type":"ItemAdded","item":"a"}` {
		t.Fatalf("event written as %s", got)
	}
	if string(reply.GetOutcome().GetReply().GetPayload().GetData()) != "3" {
		t.Fatalf("reply %v", reply.GetOutcome())
	}
	if reply.Snapshot == nil || !strings.Contains(string(reply.Snapshot.Data), `"x","a","b"`) {
		t.Fatalf("snapshot %v", reply.Snapshot)
	}
}

func TestNoSnapshotUnlessAsked(t *testing.T) {
	_, reply, err := materialiseEventSourced(Persist[mCart, mEvent, Done]().ThenNoReply(), mCart{}, mApply, mCodecs[Done](), false)
	if err != nil || reply.Snapshot != nil || reply.GetOutcome().GetNoReply() == nil {
		t.Fatalf("got %v, %v", reply, err)
	}
}

func TestRetentionAndReplicationTravelWithTheReply(t *testing.T) {
	eff := Persist[mCart, mEvent, Done]().ExpireAfter(90 * time.Second).ReplicateTo("eu").ThenReplyValue(Done{})
	_, reply, err := materialiseEventSourced(eff, mCart{}, mApply, mCodecs[Done](), false)
	if err != nil {
		t.Fatal(err)
	}
	if reply.GetRetention().GetExpireAfter().GetMillis() != 90000 || reply.GetReplication().GetSet().GetAdd()[0] != "eu" {
		t.Fatalf("got %v", reply)
	}
	_, reply, _ = materialiseEventSourced(Persist[mCart, mEvent, Done]().DeleteEntity().ThenNoReply(), mCart{}, mApply, mCodecs[Done](), false)
	if reply.GetRetention().GetDeleteNow() == nil {
		t.Fatal("delete now was not carried")
	}
}

func TestAFailureLeavesTheStateAsItWas(t *testing.T) {
	before := mCart{Items: []string{"x"}}
	panicking := Persist[mCart, mEvent, int](mAdded{"a"}).ThenReply(func(mCart) int { panic("boom") })
	after, reply, err := materialiseEventSourced(panicking, before, mApply, mCodecs[int](), false)
	if err == nil || reply != nil || len(after.Items) != 1 {
		t.Fatalf("a panicking reply: %v, %v, %+v", err, reply, after)
	}
	badApply := func(mCart, mEvent) mCart { panic("apply") }
	if _, _, err := materialiseEventSourced(Persist[mCart, mEvent, Done](mAdded{"a"}).ThenNoReply(), before, badApply, mCodecs[Done](), false); err == nil {
		t.Fatal("a panicking apply was not a failure")
	}
	if _, _, err := materialiseEventSourced(Effect[mCart, mEvent, Done]{}, before, mApply, mCodecs[Done](), false); !errors.Is(err, errNoEffect) {
		t.Fatalf("the zero effect: %v", err)
	}
	conflicting := Persist[mCart, mEvent, Done]().HandOverTo("a").ReplicateTo("b").ThenNoReply()
	if _, _, err := materialiseEventSourced(conflicting, before, mApply, mCodecs[Done](), false); err == nil {
		t.Fatal("a handover and a set change were materialised")
	}
}

func TestARefusalAndAFailureAreTellable(t *testing.T) {
	r := refusedEventSourced(Refuse(NotFound, "no such cart"))
	if e := r.GetOutcome().GetError(); e.GetCode() != pb.ErrorCode_NOT_FOUND || e.GetMessage() != "no such cart" || len(r.Events) != 0 {
		t.Fatalf("got %v", r)
	}
	if !isRefusal(Refuse(Conflict, "x")) || isRefusal(errors.New("x")) {
		t.Fatal("isRefusal")
	}
	if e := errorToPb(errors.New("disk")); e.GetCode() != pb.ErrorCode_INTERNAL {
		t.Fatalf("a failure is %v", e)
	}
	back := errorFromPb(&pb.Error{Code: pb.ErrorCode_WORKFLOW_FAILED, Message: "m", Details: map[string]string{"step": "charge"}})
	if back.Code != WorkflowFailed || back.Details["step"] != "charge" {
		t.Fatalf("got %+v", back)
	}
}

func TestAQueryRepliesWithoutChangingAnything(t *testing.T) {
	r, err := materialiseEventSourcedQuery(Reply(mCart{Items: []string{"x"}}), mCart{}, CodecFor[mCart](), CodecFor[mCart](), false)
	if err != nil || len(r.Events) != 0 || !strings.Contains(string(r.GetOutcome().GetReply().GetPayload().GetData()), `"x"`) {
		t.Fatalf("got %v, %v", r, err)
	}
	r, _ = materialiseEventSourcedQuery(NoReply[int](), mCart{}, CodecFor[mCart](), CodecFor[int](), false)
	if r.GetOutcome().GetNoReply() == nil {
		t.Fatal("no reply")
	}
}

func TestKeyValueKeepsOrReplacesTheState(t *testing.T) {
	sc, rc := CodecFor[mCart](), CodecFor[int]()
	after, reply, err := materialiseKeyValue(UpdateState[mCart, int](mCart{Items: []string{"n"}}).ThenReply(func(s mCart) int { return len(s.Items) }), mCart{}, sc, rc)
	if err != nil || len(after.Items) != 1 || reply.NewState == nil || string(reply.Outcome.GetReply().Payload.Data) != "1" {
		t.Fatalf("got %+v, %v, %v", after, reply, err)
	}
	after, reply, _ = materialiseKeyValue(KeepState[mCart, int]().ThenReplyValue(7), mCart{Items: []string{"o"}}, sc, rc)
	if reply.NewState != nil || after.Items[0] != "o" {
		t.Fatalf("a kept state was sent: %v", reply)
	}
	_, reply, _ = materialiseKeyValue(DeleteState[mCart, int]().ThenNoReply(), mCart{}, sc, rc)
	if reply.GetRetention().GetDeleteNow() == nil {
		t.Fatal("delete")
	}
}

func TestWorkflowCommandAndStepOutcomes(t *testing.T) {
	sc, rc := CodecFor[mCart](), CodecFor[Done]()
	enc := func(step string, input any) (*pb.Payload, error) {
		return payloadOf(CodecFor[int](), input.(int))
	}
	_, reply, err := materialiseWorkflowCommand(WorkflowUpdate[mCart, Done](mCart{}).ThenTransitionTo("reserve", 3).ThenReplyValue(Done{}), mCart{}, sc, rc, enc)
	if err != nil || reply.Transition.GetStep() != "reserve" || string(reply.Transition.GetInput().GetData()) != "3" || reply.NewState == nil {
		t.Fatalf("got %v, %v", reply, err)
	}
	cases := map[string]StepEffect[mCart]{
		"transition": TransitionTo[mCart]("charge", nil),
		"pause":      Pause[mCart](PauseOptions{After: time.Second, OnTimeout: "expire"}),
		"end":        StepUpdate(mCart{Items: []string{"done"}}).ThenEnd(),
		"fail":       FailWorkflow[mCart](Conflict, "no stock"),
	}
	for name, eff := range cases {
		after, r, err := materialiseStep(eff, mCart{}, sc, enc)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		switch name {
		case "transition":
			if r.Next.GetTransitionTo().GetStep() != "charge" || r.Next.GetTransitionTo().Input != nil || r.NewState != nil {
				t.Fatalf("%s: %v", name, r)
			}
		case "pause":
			if r.Next.GetPause().GetAfterMillis() != 1000 || r.Next.GetPause().GetOnTimeout().GetStep() != "expire" {
				t.Fatalf("%s: %v", name, r)
			}
		case "end":
			if r.Next.GetEnd() == nil || r.NewState == nil || after.Items[0] != "done" {
				t.Fatalf("%s: %v", name, r)
			}
		case "fail":
			if r.Next.GetFail().GetCode() != pb.ErrorCode_CONFLICT {
				t.Fatalf("%s: %v", name, r)
			}
		}
	}
	if _, _, err := materialiseStep(StepEffect[mCart]{}, mCart{}, sc, enc); err == nil {
		t.Fatal("the zero step effect was materialised")
	}
}

func TestMetadataTravelsBothWays(t *testing.T) {
	md := metadataFromPb(metadataToPb(Metadata{"b": "2", "a": "1"}))
	if md["a"] != "1" || md["b"] != "2" {
		t.Fatalf("got %v", md)
	}
	if metadataToPb(Metadata{"b": "2", "a": "1"}).Entries[0].Key != "a" {
		t.Fatal("metadata entries are not in a stable order")
	}
}
