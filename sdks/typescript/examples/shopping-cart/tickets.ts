// An agent whose tools are another program's: an MCP server, listed by name. The sidecar connects to
// it, lists its tools to the model, calls them, and holds the credential; this process never sees it.
import { Agent, command, mcpServer, s } from "ankka"

export class TicketAgent extends Agent {
  static readonly componentId = "ticket-agent"

  // docs:start mcp-servers
  static readonly mcpServers = {
    // Found at ANKKA_MCP_TICKETS_URL, and sent the credential in ANKKA_MCP_TICKETS_TOKEN.
    tickets: mcpServer("tickets", { headers: { Authorization: "ANKKA_MCP_TICKETS_TOKEN" } }),
  }
  // docs:end mcp-servers

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: TicketAgent, request) =>
      a.effects.systemMessage("You manage support tickets with the tools you are given.").userMessage(request).thenReply(),
    ),
  }
}
