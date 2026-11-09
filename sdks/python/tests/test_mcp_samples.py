"""The documentation's MCP server samples (docs/build/mcp-servers.md): an agent that names a server and
its credential's variable, and the unit kit scripting what the server answers."""

from __future__ import annotations

from ankka import command
from ankka.agent import Agent
from ankka.effects.agent import AgentEffect
from ankka.mcp import McpServer
from ankka.testkit import AgentTestKit, ScriptedModel


class TicketAgent(Agent):
    component_id = "ticket-agent"
    # docs:start mcp-credential
    mcp_servers = {"tickets": McpServer(headers={"Authorization": "ANKKA_MCP_TICKETS_TOKEN"})}
    # docs:end mcp-credential

    @command("ask")
    def ask(self, question: str) -> AgentEffect[str]:
        return self.effects.system_message("You manage tickets.").user_message(question).then_reply()


def test_the_declaration_names_the_header_and_the_variable_never_the_value() -> None:
    (server,) = TicketAgent.to_component().agent.mcp_servers
    assert server.name == "tickets" and [(h.name, h.variable) for h in server.headers] == [("Authorization", "ANKKA_MCP_TICKETS_TOKEN")]


def test_the_kit_scripts_what_the_server_answers() -> None:
    # docs:start scripted-mcp
    model = ScriptedModel().expect_tool_call("mcp__tickets__create", {"title": "printer on fire"}).expect_text("Ticket opened.")
    kit = AgentTestKit.of(
        TicketAgent,
        "s-1",
        model,
        mcp={"tickets": {"create": lambda arguments: f"opened '{arguments['title']}'", "search": lambda _: "2 tickets found"}},
    )
    answer = kit.call("ask", "open a ticket: printer on fire")
    assert answer.tool_results == ["opened 'printer on fire'"]
    assert answer.reply == "Ticket opened."
    # docs:end scripted-mcp
