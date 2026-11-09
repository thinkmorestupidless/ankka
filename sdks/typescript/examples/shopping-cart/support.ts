// Agents whose tools wait for a person, a tool that calls another service, and an agent that names
// the provider it wants. The decision rules — who decided, decided once, one tool run at most — are
// the sidecar's; this process declares which tools wait.
import { Acl, Agent, Endpoint, command, post, s, sse, sseEvent, stream, tool } from "ankka"

/** What the tools did, for a test to read. */
export const runs: string[] = []

const RefundOrder = s.record("RefundOrder", { order: s.string, amount: s.int })
const CloseAccount = s.record("CloseAccount", { customer: s.string })

export class ApprovalAgent extends Agent {
  static readonly componentId = "approval-agent"

  static readonly tools = {
    // docs:start requires-approval
    issueRefund: tool(
      "issue_refund",
      "Refunds an order. A supervisor approves every refund before it is made.",
      RefundOrder,
      (_a: ApprovalAgent, { order, amount }) => {
        runs.push(`issue_refund(${order},${amount})`)
        return `refunded ${amount} on ${order}`
      },
      { approval: true },
    ),
    // docs:end requires-approval
    // docs:start requires-approval-within
    closeAccount: tool(
      "close_account",
      "Closes a customer's account.",
      CloseAccount,
      (_a: ApprovalAgent, { customer }) => {
        runs.push(`close_account(${customer})`)
        return `closed ${customer}`
      },
      { approval: { withinMs: 2_000 } },
    ),
    // docs:end requires-approval-within
  }

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: ApprovalAgent, question) => a.support(question)),
    chat: stream("chat", s.string, (a: ApprovalAgent, question) => a.support(question)),
  }

  support(question: string) {
    return this.effects
      .systemMessage("You are a support agent. Use the tools to act on what the customer asks.")
      .userMessage(question)
      .tools("issue_refund", "close_account")
      .thenReply()
  }
}

/** Drives the approval agent: a turn that may wait, and a stream that says when it does. */
export class SupportEndpoint extends Endpoint {
  static readonly prefix = "/support"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    // docs:start ask-and-decide
    refund: post("/{session}/refunds", s.string, s.string, async (ep: SupportEndpoint, req, order) => {
      const session = ep.client.of(ApprovalAgent, req.params.session).call(ApprovalAgent.handlers.ask)
      const outcome = await session.ask(`refund order ${order}`)
      if (outcome.kind === "answered") return outcome.value
      // In a real service the requests are shown to a person, and the decision arrives later,
      // from whatever route they make it through; that route's ACL is who may decide.
      const decided = await session.decide(outcome.requests[0]!.id, { approved: true, by: "dana" })
      return decided.kind === "answered" ? decided.value : `still waiting on ${decided.requests.length}`
    }),
    // docs:end ask-and-decide
    // docs:start sse-approval
    events: sse("/{session}", async function* (ep: SupportEndpoint, req) {
      const parts = ep.client.of(ApprovalAgent, req.params.session).call(ApprovalAgent.handlers.chat)
      for await (const part of parts.streamParts("refund order o-7")) {
        yield typeof part === "string" ? part : sseEvent("approval", part.requests)
      }
    }),
    // docs:end sse-approval
  }
}

// docs:start service-call-tool
const ReadBalance = s.record("ReadBalance", { customer: s.string })

/** A support agent whose tool reads a balance from the wallet service, as its own service. */
export class WalletAgent extends Agent {
  static readonly componentId = "wallet-agent"

  static readonly tools = {
    readBalance: tool("read_balance", "Reads a customer's wallet balance.", ReadBalance, (a: WalletAgent, { customer }) =>
      a.services.service("wallet").getText(`/wallet/balances/${customer}`),
    ),
  }

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: WalletAgent, question) =>
      a.effects.systemMessage("You answer questions about wallets.").userMessage(question).tools("read_balance").thenReply(),
    ),
  }
}
// docs:end service-call-tool

// docs:start naming-a-provider
/** Answered by the provider the sidecar has under the name `openai`, not its default. */
export class Triage extends Agent {
  static readonly componentId = "triage"

  static readonly handlers = {
    route: command("route", s.string, s.string, (a: Triage, question) =>
      a.effects.model("openai").systemMessage("Say which team should answer.").userMessage(question).thenReply(),
    ),
  }
}
// docs:end naming-a-provider
