package ankka

import (
	"context"
	"sync"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

var traceSeen struct {
	sync.Mutex
	traceparent string
}

var tTracedConsumer = Consumer("t-traced", Changes(tCartEntity), func(ctx context.Context, _ tEvent) (ConsumerEffect, error) {
	traceSeen.Lock()
	traceSeen.traceparent = MetadataFrom(ctx)["traceparent"]
	traceSeen.Unlock()
	return ConsumerDone(), nil
})

// features/polyglot/components.feature, "a call made by a handler is nested under that handler in
// the trace", the Go row's half in the SDK: a consumer's handler is given the trace its change
// carried, and every call made with the handler's context carries the handler's metadata on
// (TestInvokeSendsTheWireAndDecodesTheReply), which is what the runtime nests the call's span by.
// The runtime's half, and the call counted from the consumer's handler, is the conformance case
// topology.call-attributed.
func TestAConsumersHandlerIsGivenTheTraceItsChangeCarried(t *testing.T) {
	client := pb.NewConsumerClient(loopback(t, NewService(tCartEntity, tTracedConsumer)))
	req := consumerRequest(t, tAdded{tItem{"ink", 1}},
		Metadata{"ce-subject": "c1", "ankka.sequence": "1", "ankka.protocol": "1.22", "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"})
	req.ComponentId = "t-traced"
	if out, err := client.Handle(context.Background(), req); err != nil || out.GetDone() == nil {
		t.Fatalf("the consumer answered %v, %v", out, err)
	}
	traceSeen.Lock()
	defer traceSeen.Unlock()
	if traceSeen.traceparent != "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" {
		t.Fatalf("the handler was given the trace %q", traceSeen.traceparent)
	}
}
