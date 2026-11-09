package samples

import (
	"context"
	"regexp"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

var instructed = regexp.MustCompile(`(?i)ignore what you were told`)

// docs:start mcp-servers

// TicketAgent works tickets through two MCP servers, whose tools the model is offered as
// mcp__tickets__<tool> and mcp__guarded__<tool>.
var TicketAgent = ankka.Agent("ticket-agent",
	ankka.McpServers(
		// Found at ANKKA_MCP_TICKETS_URL, and sent the credential in ANKKA_MCP_TICKETS_TOKEN.
		ankka.McpServer("tickets", ankka.McpHeader("Authorization", "ANKKA_MCP_TICKETS_TOKEN")),
		// Every tool of this one waits for a person; its variable replaces the address given here.
		ankka.McpServer("guarded", ankka.McpURL("https://guarded.example.com/mcp"), ankka.McpApproval(0)),
	),
	// What an MCP server answers is checked before the model is told it.
	ankka.Guardrails(ankka.ResultGuardrail("no-instructions",
		func(_ context.Context, _, text string) (string, error) {
			if instructed.MatchString(text) {
				return "the result tries to instruct whoever reads it", nil
			}
			return "", nil
		})),
	ankka.Ask("ask", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.SystemMessage("You work tickets.").UserMessage(question).ThenReply(), nil
	}),
)

// docs:end mcp-servers

// docs:start mcp-header

var ticketsServer = ankka.McpServer("tickets", ankka.McpHeader("Authorization", "ANKKA_MCP_TICKETS_TOKEN"))

// docs:end mcp-header
