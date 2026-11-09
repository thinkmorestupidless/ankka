package samples

import (
	"net/http"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

// The streaming routes against a real sidecar: a stream read whole in a handler, and one served as
// server-sent events.
func TestTheChatStreamsThroughTheSidecar(t *testing.T) {
	script := `[{"text":"Streamed answer here"},{"text":"Mild and sunny"}]`
	kit := testkit.StartT(t, ankka.NewService(WeatherAgent, ChatEndpoint),
		testkit.Env(map[string]string{"ANKKA_MODEL_SCRIPT": script}))
	ctx := t.Context()

	r, err := kit.HTTP().Do(ctx, http.MethodPost, "/chat/s-1/once", "hello", http.Header{"Content-Type": {"text/plain"}})
	if err != nil || r.Status != 200 || r.Text() != "Streamed answer here" {
		t.Fatalf("the whole answer read as %v, %v\n%s", r, err, kit.SidecarLogs(ctx))
	}
	r, err = kit.HTTP().Get(ctx, "/chat/s-2")
	if err != nil || r.Status != 200 {
		t.Fatalf("the stream answered %v, %v", r, err)
	}
	var frames []string
	for _, line := range strings.Split(r.Text(), "\n") {
		if data, ok := strings.CutPrefix(line, "data:"); ok {
			frames = append(frames, strings.TrimSpace(data))
		}
	}
	// The scripted text arrives as several tokens, each its own frame.
	if len(frames) < 2 || !strings.Contains(strings.Join(frames, ""), "sunny") {
		t.Fatalf("the frames were %q\n%s", frames, kit.SidecarLogs(ctx))
	}
}
