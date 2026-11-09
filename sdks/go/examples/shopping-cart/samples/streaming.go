package samples

import (
	"context"
	"iter"
	"strings"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start consume-stream

// askOnce reads a whole streamed answer: tokens are pushed from wherever the session is hosted to
// here as the model produces them, and a refusal ends the stream with its error.
func askOnce(ctx context.Context, session, question string) (string, error) {
	var answer strings.Builder
	for token, err := range ankka.InvokeStream(ctx, "weather-agent", session, "chat", question) {
		if err != nil {
			return "", err
		}
		answer.WriteString(token)
	}
	return answer.String(), nil
}

// docs:end consume-stream

// ChatEndpoint serves the weather agent's stream.
var ChatEndpoint = ankka.NewEndpoint("/chat", ankka.AllowAll(),
	ankka.Post("/{session}/once", func(ctx context.Context, req *ankka.Request, question string) (string, error) {
		return askOnce(ctx, req.Param("session"), question)
	}),
	// docs:start sse-stream
	// Each token is one server-sent event, as the agent produces it.
	ankka.SSE("/{session}", func(ctx context.Context, req *ankka.Request) iter.Seq2[string, error] {
		return ankka.InvokeStream(ctx, "weather-agent", req.Param("session"), "chat", "What is the weather?")
	}),
	// docs:end sse-stream
)
