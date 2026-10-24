package ankka

import (
	"errors"
	"fmt"
	"testing"
	"time"
)

type tCart struct{ Items []string }

func TestBuildingAnEffectPerformsNothingAndKeepsWhatItWasGiven(t *testing.T) {
	events := []string{"a", "b"}
	called := false
	e := Persist[tCart, string, int](events...).ExpireAfter(time.Minute).ThenReply(func(s tCart) int {
		called = true
		return len(s.Items)
	})
	events[0] = "changed"
	if called {
		t.Fatal("building the effect computed its reply")
	}
	if got := e.Events(); len(got) != 2 || got[0] != "a" {
		t.Fatalf("the effect's events are %v; the caller's slice was copied", got)
	}
	if e.retention.expireAfter != time.Minute || e.outcome.kind != outcomeReply {
		t.Fatalf("got %+v", e)
	}
	if n := e.outcome.compute(tCart{Items: []string{"x", "y", "z"}}); n != 3 {
		t.Fatalf("the reply computed from the state after the events is %d", n)
	}
}

func TestReplicationChangesAreOneHandoverOrOneSetChange(t *testing.T) {
	e := Persist[tCart, string, Done]().ReplicateTo("b").ReplicateTo("a").StopReplicatingTo("c").ThenNoReply()
	if e.problem != "" || fmt.Sprint(e.replication.Add) != "[a b]" || fmt.Sprint(e.replication.Remove) != "[c]" {
		t.Fatalf("got %+v, problem %q", e.replication, e.problem)
	}
	if e := Persist[tCart, string, Done]().HandOverTo("b").ReplicateTo("c").ThenNoReply(); e.problem == "" {
		t.Fatal("a handover and a set change in one command were accepted")
	}
	if e := Persist[tCart, string, Done]().ReplicateTo("a").StopReplicatingTo("a").ThenNoReply(); e.problem == "" {
		t.Fatal("adding and removing one installation was accepted")
	}
}

func TestThenReplyStateNeedsTheReplyTypeToBeTheState(t *testing.T) {
	ok := Persist[tCart, string, tCart]().ThenReplyState()
	if got := ok.outcome.compute(tCart{Items: []string{"x"}}); len(got.Items) != 1 {
		t.Fatal("ThenReplyState did not reply with the state")
	}
	wrong := Persist[tCart, string, int]().ThenReplyState()
	defer func() {
		if recover() == nil {
			t.Fatal("ThenReplyState with another reply type did not fail")
		}
	}()
	wrong.outcome.compute(tCart{})
}

func TestARefusalIsAnErrorCarryingItsCode(t *testing.T) {
	err := fmt.Errorf("wrapped: %w", Refuse(BadRequest, "quantity must be positive"))
	r, ok := AsRefusal(err)
	if !ok || r.Code != BadRequest || r.Message != "quantity must be positive" || r.Code.HTTPStatus() != 400 {
		t.Fatalf("got %+v, %v", r, ok)
	}
	if _, ok := AsRefusal(errors.New("plain")); ok {
		t.Fatal("a plain error read as a refusal")
	}
	for code, status := range map[ErrorCode]int{Internal: 500, NotFound: 404, Conflict: 409, Timeout: 504, Unavailable: 503, WorkflowFailed: 424, NotHome: 421} {
		if code.HTTPStatus() != status {
			t.Errorf("%s answers %d, want %d", code, code.HTTPStatus(), status)
		}
	}
}

func TestStepAndWorkflowEffects(t *testing.T) {
	s := StepUpdate(tCart{Items: []string{"a"}}).ThenPause(PauseOptions{After: time.Second, OnTimeout: "expire"})
	if !s.update || s.next != stepPause || s.pause.OnTimeout != "expire" {
		t.Fatalf("got %+v", s)
	}
	if e := End[tCart](); e.update || e.next != stepEnd {
		t.Fatalf("got %+v", e)
	}
	if e := FailWorkflow[tCart](Conflict, "no stock"); e.next != stepFail || e.failCode != Conflict {
		t.Fatalf("got %+v", e)
	}
	w := WorkflowUpdate[tCart, Done](tCart{}).ThenTransitionTo("reserve", 3).ThenReplyValue(Done{})
	if !w.update || w.transition == nil || w.transition.Step != "reserve" || w.transition.Input != 3 {
		t.Fatalf("got %+v", w)
	}
}

func TestKeyedRowsNeedAKey(t *testing.T) {
	e := AllRows(UpdateRowAt("k1", 1), DeleteRowAt[int]("k2"))
	if e.problem != "" || len(e.Changes()) != 2 || !e.Changes()[1].Deleted {
		t.Fatalf("got %+v", e)
	}
	if e := UpdateRowAt("", 1); e.problem == "" {
		t.Fatal("an empty key was accepted")
	}
}

func TestConsumerAndAgentEffects(t *testing.T) {
	if e := ProduceAll(Message{Payload: 1, Key: "a"}, Message{}); e.problem == "" {
		t.Fatal("a message with no payload was accepted")
	}
	a := SystemMessage("help").UserMessage("hi").Tools("lookup").WithContext("doc")
	b := a.Tools("other")
	if len(a.ToolNames) != 1 || len(b.ToolNames) != 2 || !a.SessionMemory {
		t.Fatalf("an agent effect was changed by a later builder: %+v, %+v", a, b)
	}
	if j := a.ThenReplyJSON(""); !j.JSONReply || j.SchemaHint != "JSON" {
		t.Fatalf("got %+v", j)
	}
}
