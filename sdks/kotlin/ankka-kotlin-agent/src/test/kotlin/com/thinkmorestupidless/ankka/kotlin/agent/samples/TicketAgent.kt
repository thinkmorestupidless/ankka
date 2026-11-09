package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.AgentSettings
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail
import com.thinkmorestupidless.ankka.agent.javasdk.McpServer
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.kotlin.agent.effects

// docs:start mcp-servers
/** An agent whose tools are two MCP servers'. */
@ComponentId("ticket-agent")
class TicketAgent : Agent() {
    override fun settings(): AgentSettings =
        AgentSettings.defaults()
            // docs:start credential
            // Found at ANKKA_MCP_TICKETS_URL, and sent the credential in ANKKA_MCP_TICKETS_TOKEN.
            .mcpServer(McpServer.named("tickets").header("Authorization", "ANKKA_MCP_TICKETS_TOKEN"))
            // docs:end credential
            // Every tool of this one waits for a person; its variable replaces the address given here.
            .mcpServer(McpServer.at("guarded", "https://guarded.example.com/mcp").requiresApproval())
            // What an MCP server answers is checked before the model is told it.
            .resultGuardrail(Guardrail.forbidding("no-instructions", "(?i)ignore what you were told"))

    @Command("ask")
    fun ask(question: String): AgentEffect<String> = effects {
        systemMessage("You manage support tickets.").userMessage(question).thenReply()
    }
}
// docs:end mcp-servers
