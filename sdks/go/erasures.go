package ankka

import (
	"context"
	"errors"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// Asking for an erasure request from code (feature 042, protocol 1.23): ankka.Erasures(ctx).Ask(...)
// from an endpoint, a consumer or the erasure handler. The request is asked of the control plane as
// this service, through the sidecar's Client.AskErasure; the service needs the erasure grant of the
// project it asks in, its own included, and is named as who asked.

// ErasureRequests asks for erasure requests as the service the handler ctx belongs to.
type ErasureRequests struct{ ctx context.Context }

// Erasures asks for erasure requests as the service the handler ctx belongs to.
func Erasures(ctx context.Context) *ErasureRequests { return &ErasureRequests{ctx: ctx} }

// AskErasure is what to ask: whose personal data, where, and from when.
type AskErasure struct {
	Subject DataSubject
	// Project is the project to erase in; empty is this service's own.
	Project string
	// NotBefore holds the request until that date; zero is no hold.
	NotBefore LocalDate
	Reason    string
	// Correlation is the caller's own reference, carried on the request.
	Correlation string
}

// ErasureAsked is an erasure request as asked: its id in its project and its state (held, applying,
// applied).
type ErasureAsked struct {
	ID    string
	State string
}

// Ask asks for the erasure of req.Subject. A refusal is a *RefusalError with the control plane's
// code: Forbidden without the erasure grant, Conflict while erasure is off in the project,
// BadRequest for a subject outside the rule. A runtime before 1.23 is a *ProtocolTooOldError.
func (e *ErasureRequests) Ask(req AskErasure) (ErasureAsked, error) {
	_, rpc, err := clientOf(e.ctx)
	if err != nil {
		return ErasureAsked{}, err
	}
	out := &pb.AskErasureRequest{Project: req.Project, Subject: req.Subject.String(), Metadata: metadataToPb(MetadataFrom(e.ctx))}
	if !req.NotBefore.IsZero() {
		date := req.NotBefore.String()
		out.NotBefore = &date
	}
	if req.Reason != "" {
		out.Reason = &req.Reason
	}
	if req.Correlation != "" {
		out.Correlation = &req.Correlation
	}
	reply, err := rpc.AskErasure(e.ctx, out)
	if err != nil {
		return ErasureAsked{}, tooOld("asking for an erasure", "1.23", err)
	}
	switch r := reply.GetReply().(type) {
	case *pb.AskErasureReply_Ok:
		return ErasureAsked{ID: r.Ok.GetId(), State: r.Ok.GetState()}, nil
	case *pb.AskErasureReply_Error:
		return ErasureAsked{}, errorFromPb(r.Error)
	}
	return ErasureAsked{}, errors.New("the sidecar answered an erasure request with nothing")
}
