package samples

import (
	"net/http"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// The client-side samples against a real sidecar, whose model is scripted: a turn that waits for a
// person, decided through the client, and a stream that ends with the approval event.
func TestAnApprovalIsDecidedThroughTheSidecar(t *testing.T) {
	script := `[{"tool":"issue_refund","arguments":{"order":"o-7","amount":12}},` +
		`{"text":"Refunded 12 on o-7."},` +
		`{"tool":"issue_refund","arguments":{"order":"o-7","amount":12}}]`
	kit := testkit.StartT(t, ankka.NewService(ApprovalAgent, SupportEndpoint),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": script}))
	ctx := t.Context()

	answer, err := askAndDecide(kit.Context(ctx))
	if err != nil || answer != "Refunded 12 on o-7." {
		t.Fatalf("ask and decide answered %q, %v\n%s", answer, err, kit.SidecarLogs(ctx))
	}

	r, err := kit.HTTP().Do(ctx, http.MethodGet, "/support/s-43", nil, nil)
	if err != nil || r.Status != 200 {
		t.Fatalf("the stream answered %v, %v", r, err)
	}
	body := r.Text()
	if !strings.Contains(body, "event:approval") || !strings.Contains(body, "issue_refund") {
		t.Fatalf("the stream ended with %q\n%s", body, kit.SidecarLogs(ctx))
	}
}
