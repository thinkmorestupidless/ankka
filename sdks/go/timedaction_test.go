package ankka

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

type tReminder struct {
	Cart string `json:"cart"`
}

var tSeenTimer TimerScope

var tReminders = TimedAction("t-reminders",
	Action("remind", func(ctx context.Context, r tReminder) (ActionEffect, error) {
		tSeenTimer, _ = TimerFrom(ctx)
		switch r.Cart {
		case "refuse":
			return ActionEffect{}, Refuse(Conflict, "already reminded")
		case "error":
			return ActionEffect{}, errors.New("the mail server is down")
		case "fail":
			return ActionFail(Unavailable, "try later"), nil
		case "panic":
			panic("the reminder broke")
		case "nothing":
			return ActionEffect{}, nil
		}
		return ActionDone(), nil
	}),
)

func TestATimedActionRunsItsAction(t *testing.T) {
	svc := NewService(tReminders)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	c := svc.Spec().GetComponents()[0]
	if c.GetKind() != pb.Kind_TIMED_ACTION || len(c.GetHandlers()) != 1 || c.GetHandlers()[0].GetName() != "remind" || c.GetTimedAction() == nil {
		t.Fatalf("declared %v", c)
	}
	client := pb.NewTimedActionClient(loopback(t, svc))
	call := func(cart, name string, md Metadata) *pb.TimedActionEffect {
		t.Helper()
		p, err := encodeAny(tReminder{cart})
		if err != nil {
			t.Fatal(err)
		}
		out, err := client.Invoke(context.Background(), &pb.TimedActionRequest{ComponentId: "t-reminders", Name: name, Payload: p, Metadata: metadataToPb(md)})
		if err != nil {
			t.Fatalf("a timed action failed its call: %v", err)
		}
		return out
	}
	due := time.UnixMilli(1790627790360)
	if out := call("c1", "remind", Metadata{"ankka.timer": "remind-c1", "ankka.due": "1790627790360", "ankka.attempts": "2"}); out.GetDone() == nil {
		t.Fatalf("a reminder answered %v", out)
	}
	if tSeenTimer.Name != "remind-c1" || !tSeenTimer.Due.Equal(due) || tSeenTimer.Attempts != 2 {
		t.Fatalf("the action saw the timer %+v", tSeenTimer)
	}
	for cart, want := range map[string]struct {
		code pb.ErrorCode
		text string
	}{
		"refuse":  {pb.ErrorCode_CONFLICT, "already reminded"},
		"error":   {pb.ErrorCode_INTERNAL, "mail server"},
		"fail":    {pb.ErrorCode_UNAVAILABLE, "try later"},
		"panic":   {pb.ErrorCode_INTERNAL, "the reminder broke"},
		"nothing": {pb.ErrorCode_INTERNAL, "no effect"},
	} {
		f := call(cart, "remind", nil).GetFail()
		if f.GetCode() != want.code || !strings.Contains(f.GetMessage(), want.text) {
			t.Errorf("%s answered %v", cart, f)
		}
	}
	if f := call("c1", "nap", nil).GetFail(); f.GetCode() != pb.ErrorCode_NOT_FOUND {
		t.Errorf("an unknown action answered %v", f)
	}
	bad, err := client.Invoke(context.Background(), &pb.TimedActionRequest{ComponentId: "t-reminders", Name: "remind", Payload: &pb.Payload{ContentType: JSONContent, Data: []byte("{")}})
	if err != nil || bad.GetFail().GetCode() != pb.ErrorCode_BAD_REQUEST {
		t.Errorf("an unreadable input answered %v, %v", bad, err)
	}
	if out, _ := client.Invoke(context.Background(), &pb.TimedActionRequest{ComponentId: "nobody"}); out.GetFail().GetCode() != pb.ErrorCode_NOT_FOUND {
		t.Errorf("an unknown timed action answered %v", out)
	}
	// Before 1.12 a request carries no due time.
	call("c1", "remind", Metadata{"ankka.timer": "t"})
	if !tSeenTimer.Due.IsZero() {
		t.Errorf("a request with no due time is due %v", tSeenTimer.Due)
	}
}

func TestATimedActionsProblemsAreNamed(t *testing.T) {
	text := strings.Join(serviceProblems(NewService(TimedAction("", SnapshotEvery(1)))), "\n")
	for _, want := range []string{"has an id", "at least one Action", "not an option a timed action takes"} {
		if !strings.Contains(text, want) {
			t.Errorf("problems do not say %q:\n%s", want, text)
		}
	}
}
