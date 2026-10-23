//go:build conformance_broken

package conformance

import "context"

// refuse, broken on purpose: it persists what a refusal must not and replies as a success. Only
// `go run -tags conformance_broken ./cmd/conformance` builds it; see refuse.go.
func refuse(_ context.Context, _ Recordings, input string) (effect[string], error) {
	return persist[string](Recorded{Input: "refused " + input}).ThenReply(doneText), nil
}
