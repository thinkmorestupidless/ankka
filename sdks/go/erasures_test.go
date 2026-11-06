package ankka

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// AskErasure answers what the test set, keeping what it was asked; with nothing set it is a sidecar
// before 1.23.
func (f *fakeSidecar) AskErasure(_ context.Context, r *pb.AskErasureRequest) (*pb.AskErasureReply, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.erasure == nil {
		return nil, status.Error(codes.Unimplemented, "unknown method AskErasure")
	}
	f.erasuresAsked = append(f.erasuresAsked, r)
	return f.erasure, nil
}

func heldErasure(id, state string) *pb.AskErasureReply {
	return &pb.AskErasureReply{Reply: &pb.AskErasureReply_Ok{Ok: &pb.ErasureAsked{Id: id, State: state}}}
}

func TestAnErasureForItsOwnProjectIsAskedWithItEmpty(t *testing.T) {
	f := &fakeSidecar{erasure: heldErasure("er-1", "held")}
	ctx := startFake(t, f, nil)
	if _, err := Erasures(ctx).Ask(AskErasure{Subject: MustSubject("customer-42")}); err != nil {
		t.Fatal(err)
	}
	r := f.erasuresAsked[0]
	if r.GetProject() != "" || r.GetSubject() != "customer-42" || r.NotBefore != nil || r.Reason != nil || r.Correlation != nil {
		t.Fatalf("sent %v", r)
	}
	if metadataFromPb(r.GetMetadata())["traceparent"] != "00-abc-def-01" {
		t.Fatal("the handler's metadata did not travel")
	}
}

func TestAnErasureSendsItsDateAsISO8601(t *testing.T) {
	f := &fakeSidecar{erasure: heldErasure("er-1", "held")}
	ctx := startFake(t, f, nil)
	_, err := Erasures(ctx).Ask(AskErasure{
		Subject: MustSubject("customer-42"), Project: "ledger", NotBefore: NewLocalDate(2027, time.January, 31), Reason: "aml-retention", Correlation: "c-7",
	})
	if err != nil {
		t.Fatal(err)
	}
	r := f.erasuresAsked[0]
	if r.GetProject() != "ledger" || r.GetNotBefore() != "2027-01-31" || r.GetReason() != "aml-retention" || r.GetCorrelation() != "c-7" {
		t.Fatalf("sent %v", r)
	}
}

func TestAnErasureAnswersItsIDAndState(t *testing.T) {
	ctx := startFake(t, &fakeSidecar{erasure: heldErasure("er-9", "applying")}, nil)
	asked, err := Erasures(ctx).Ask(AskErasure{Subject: MustSubject("customer-42")})
	if err != nil || asked != (ErasureAsked{ID: "er-9", State: "applying"}) {
		t.Fatalf("got %v, %v", asked, err)
	}
}

func TestARefusedErasureCarriesItsCode(t *testing.T) {
	f := &fakeSidecar{erasure: &pb.AskErasureReply{Reply: &pb.AskErasureReply_Error{Error: &pb.Error{Code: pb.ErrorCode_FORBIDDEN, Message: "no erasure grant"}}}}
	ctx := startFake(t, f, nil)
	_, err := Erasures(ctx).Ask(AskErasure{Subject: MustSubject("customer-42")})
	r, ok := AsRefusal(err)
	if !ok || r.Code != Forbidden || r.Message != "no erasure grant" {
		t.Fatalf("got %v", err)
	}
}

func TestAnErasureOnAnOlderSidecarNames123(t *testing.T) {
	ctx := startFake(t, &fakeSidecar{}, nil)
	_, err := Erasures(ctx).Ask(AskErasure{Subject: MustSubject("customer-42")})
	var old *ProtocolTooOldError
	if !errors.As(err, &old) || old.Needs != "1.23" {
		t.Fatalf("got %v", err)
	}
}

func TestTheErasureHandlerAsksThroughItsContext(t *testing.T) {
	f := &fakeSidecar{erasure: heldErasure("er-1", "held")}
	ctx := startFake(t, f, nil)
	svc := NewService(plainEntity()).ErasureHandler(func(ctx context.Context, s DataSubject) (ErasureReport, error) {
		_, err := Erasures(ctx).Ask(AskErasure{Subject: s})
		return ErasureReport{}, err
	})
	if _, err := svc.decls.runErasure(ctx, &pb.ErasureCall{Subject: "player/8c1f"}); err != nil {
		t.Fatal(err)
	}
	if len(f.erasuresAsked) != 1 || f.erasuresAsked[0].GetSubject() != "player/8c1f" {
		t.Fatalf("asked %v", f.erasuresAsked)
	}
}
