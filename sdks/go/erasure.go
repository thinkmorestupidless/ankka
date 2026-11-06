package ankka

import (
	"context"
	"fmt"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// The service's own part of an erasure (feature 042): what the platform cannot see, such as the
// objects it keeps in a bucket. Run once for each application of an erasure, on one instance of the
// service, and again on each re-run, so it must do nothing the second time that it did not do the
// first. A run that fails is recorded as failed and run again on the next pass; the platform's own
// duties do not wait for it.

// erasureSince is the protocol minor whose runtimes keep subject keys and run erasure handlers.
const erasureSince = 23

// Finality is when what a handler erased is gone for good: Immediate, or After a store that keeps
// deleted objects has let them go.
type Finality struct{ after time.Duration }

// Immediate is gone now.
var Immediate = Finality{}

// After is gone once within has passed: a bucket with soft delete.
func After(within time.Duration) Finality { return Finality{after: within} }

// Within is how long after the run the objects are gone; zero for Immediate.
func (f Finality) Within() time.Duration { return f.after }

// wire is the finality as the sidecar is told it: "immediate", or "after:" and an ISO-8601 duration.
func (f Finality) wire() string {
	if f.after <= 0 {
		return "immediate"
	}
	return "after:" + FormatDuration(f.after.Truncate(time.Millisecond))
}

// ErasureReport is what a run of the handler erased.
type ErasureReport struct {
	ObjectsErased int64
	Finality      Finality
}

// Erasure is the application of an erasure a handler is running for.
type Erasure struct {
	// Seq is the erasure's sequence in the installation's erasure log.
	Seq int64
	// Rerun is whether this run is a re-run of an erasure already applied.
	Rerun bool
}

type erasureKeyType struct{}

// ErasureFrom is the erasure a handler's context is running for; false outside an erasure handler.
func ErasureFrom(ctx context.Context) (Erasure, bool) {
	e, ok := ctx.Value(erasureKeyType{}).(Erasure)
	return e, ok
}

// ErasureHandlerFunc is a service's erasure handler: given the erased data subject, it erases what
// the service keeps of it outside the platform's stores and says how much.
type ErasureHandlerFunc func(ctx context.Context, subject DataSubject) (ErasureReport, error)

// ErasureHandler gives the service its erasure handler; one per service, before it starts. The
// service then needs a runtime of protocol 1.23.
func (s *Service) ErasureHandler(h ErasureHandlerFunc) *Service {
	d := s.decls
	switch {
	case h == nil:
		d.problem("erasure handler", "the erasure handler is nil")
	case d.erasure != nil:
		d.problem("erasure handler", "a service has one erasure handler; it was given a second")
	default:
		d.erasure = h
		d.require(erasureSince, "an erasure handler needs protocol 1.%d", erasureSince)
	}
	return s
}

// runErasure is the Erasure conversation's call of the handler.
func (d *declarations) runErasure(ctx context.Context, call *pb.ErasureCall) (*pb.ErasureReply, error) {
	subject, err := Subject(call.GetSubject())
	if err != nil {
		return nil, &RefusalError{Code: BadRequest, Message: err.Error()}
	}
	ctx = context.WithValue(withMetadata(ctx, metadataFromPb(call.GetMetadata())), erasureKeyType{}, Erasure{Seq: call.GetErasureSeq(), Rerun: call.GetRerun()})
	report, err := d.erasure(ctx, subject)
	if err != nil {
		return nil, err
	}
	if report.ObjectsErased < 0 {
		return nil, fmt.Errorf("the erasure handler reported %d objects erased", report.ObjectsErased)
	}
	return &pb.ErasureReply{ObjectsErased: report.ObjectsErased, Finality: report.Finality.wire()}, nil
}

// personalCodec is a codec that can say whether its type holds a personal field.
type personalCodec interface{ holdsPersonal() bool }

// notePersonal requires protocol 1.23 of the runtime once a declared codec holds a personal field:
// an older runtime keeps no subject keys, and its sidecar could not answer for one.
func (d *declarations) notePersonal(where string, codecs ...any) {
	if d.personal {
		return
	}
	for _, c := range codecs {
		if pc, ok := c.(personalCodec); ok && pc.holdsPersonal() {
			d.personal = true
			d.require(erasureSince, "personal fields need protocol 1.%d (%s holds one)", erasureSince, where)
			return
		}
	}
}
