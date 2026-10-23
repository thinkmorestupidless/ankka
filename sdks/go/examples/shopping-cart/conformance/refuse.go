//go:build !conformance_broken

package conformance

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// refuse is the conformance entity's refusal: nothing is persisted, the caller is told a conflict.
// Built with -tags conformance_broken it persists and replies instead (refuse_broken.go), which is
// the red proof of features/polyglot/conformance.feature, "a behaviour broken in an SDK is named
// and no other": the run names es.refusal-persists-nothing, client.error-code-crosses and
// topology.call-attributed, and nothing else.
func refuse(_ context.Context, _ Recordings, _ string) (effect[string], error) {
	return effect[string]{}, ankka.Refuse(ankka.Conflict, "refused on purpose")
}
