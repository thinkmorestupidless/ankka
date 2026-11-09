package samples

import (
	"context"
	"slices"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheChatStreamsItsAnswer(t *testing.T) {
	ctx := context.Background()
	if p := ankka.NewService(WeatherAgent, ChatEndpoint).Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	model := testkit.NewScriptedModel().
		ExpectToolCall("get_weather", map[string]any{"location": "Lisbon"}).
		ExpectText("Lisbon is 18C and sunny.")
	kit := testkit.Agent(WeatherAgent, "s-1", model)
	defer kit.Close()
	tokens, err := kit.Stream(ctx, "chat", "Will it rain in Lisbon?")
	if err != nil || !slices.Equal(tokens, []string{"Lisbon ", "is ", "18C ", "and ", "sunny."}) {
		t.Fatalf("the stream was %q, %v", tokens, err)
	}
}
