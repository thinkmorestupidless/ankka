package ankka

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// DocsSidecar is a stand-in for the sidecar the documentation's samples in package ankka_test run
// against: it answers every call with an empty reply and an erasure as held, and keeps what it was
// sent, so a sample's test can see what its calls carried.
type DocsSidecar struct{ f *fakeSidecar }

// StartDocsSidecar starts a DocsSidecar hosting an agent "helper" and an autonomous agent
// "resolver", and answers a handler's context whose client is it.
func StartDocsSidecar(t *testing.T) (context.Context, *DocsSidecar) {
	t.Helper()
	f := &fakeSidecar{
		reply:   &pb.InvokeReply{Result: &pb.InvokeReply_Reply{Reply: &pb.Outcome_Reply{Payload: &pb.Payload{ContentType: JSONContent, Data: []byte(`{}`)}}}},
		erasure: heldErasure("er-1", "held"),
	}
	d := newDeclarations()
	d.components = []*pb.Component{
		{Kind: pb.Kind_AGENT, Id: "helper"},
		{Kind: pb.Kind_AUTONOMOUS_AGENT, Id: "resolver"},
	}
	return startFake(t, f, d), &DocsSidecar{f: f}
}

// Answer has every later call answered with the JSON text reply.
func (s *DocsSidecar) Answer(reply string) {
	s.f.mu.Lock()
	defer s.f.mu.Unlock()
	s.f.reply = &pb.InvokeReply{Result: &pb.InvokeReply_Reply{Reply: &pb.Outcome_Reply{Payload: &pb.Payload{ContentType: JSONContent, Data: []byte(reply)}}}}
}

// Subjects is the data subject each call was sent with, in order: the metadata's for a call to an
// agent's session, the body's for an assignment to an instance; "" for none.
func (s *DocsSidecar) Subjects() []string {
	s.f.mu.Lock()
	defer s.f.mu.Unlock()
	out := make([]string, 0, len(s.f.invokes))
	for _, r := range s.f.invokes {
		if v, ok := metadataFromPb(r.GetMetadata())[DataSubjectKey]; ok {
			out = append(out, v)
			continue
		}
		var body map[string]any
		_ = json.Unmarshal(r.GetPayload().GetData(), &body)
		subject, _ := body["subject"].(string)
		out = append(out, subject)
	}
	return out
}

// ErasuresAsked is each erasure asked, as subject, not-before date, reason and correlation.
func (s *DocsSidecar) ErasuresAsked() [][4]string {
	s.f.mu.Lock()
	defer s.f.mu.Unlock()
	out := make([][4]string, 0, len(s.f.erasuresAsked))
	for _, r := range s.f.erasuresAsked {
		out = append(out, [4]string{r.GetSubject(), r.GetNotBefore(), r.GetReason(), r.GetCorrelation()})
	}
	return out
}

// RunErasureHandler runs svc's erasure handler for subject as the sidecar would, with ctx's storage,
// and answers what the sidecar is told: the objects erased and the finality as the wire writes it.
func RunErasureHandler(ctx context.Context, svc *Service, subject string) (int64, string, error) {
	reply, err := svc.decls.runErasure(ctx, &pb.ErasureCall{Subject: subject, ErasureSeq: 1})
	if err != nil {
		return 0, "", err
	}
	return reply.GetObjectsErased(), reply.GetFinality(), nil
}
