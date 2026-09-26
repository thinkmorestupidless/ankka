// Fixtures for the other kinds: small components whose handlers cover each effect and need no sidecar.
import { KeyValueEntity } from "../../src/keyValueEntity.ts"
import { Workflow } from "../../src/workflow.ts"
import { Consumer } from "../../src/consumer.ts"
import { TimedAction } from "../../src/timedAction.ts"
import { Agent } from "../../src/agent.ts"
import { action, command, guardrail, query, step, stream, tool } from "../../src/handlers.ts"
import { jsonCodec } from "../../src/codec.ts"
import { Done, done, s, type Infer } from "../../src/schema.ts"
import { ErrorCode } from "../../src/effects/common.ts"
import { workflowSettings } from "../../src/effects/workflow.ts"
import { Duration } from "../../src/time.ts"

// ── key value ──

export const ProfileState = s.record("ProfileState", { name: s.string, visits: s.int })

export class Profile extends KeyValueEntity<Infer<typeof ProfileState>> {
  static readonly componentId = "profile"
  static readonly state = jsonCodec(ProfileState, "profile")
  static readonly handlers = {
    set: command("set", s.string, s.string, (p: Profile, name) => (name ? p.effects.updateState({ ...p.state, name, visits: p.state.visits + 1 }).thenReply((st) => `${st.name}#${st.visits}`) : p.effects.error("a name is needed"))),
    get: query("get", s.string, (p: Profile) => p.effects.reply(p.state.name || "none")),
    getState: query("get-state", ProfileState, (p: Profile) => p.effects.reply(p.state)),
    remove: command("delete", s.string, (p: Profile) => p.effects.deleteEntity().thenReply(() => "done")),
    expire: command("expire", Done, (p: Profile) => p.effects.updateState(p.state).expireAfter(Duration.ofSeconds(2)).thenReply(() => done)),
    boom: command("boom", Done, (): never => {
      throw new Error("kaboom")
    }),
  }
  emptyState() {
    return { name: "", visits: 0 }
  }
}

// ── workflow ──

export const Approval = s.record("Approval", { id: s.string, status: s.string, mode: s.string, note: s.option(s.string) })
export type Approval = Infer<typeof Approval>

export class ApprovalWorkflow extends Workflow<Approval> {
  static readonly componentId = "approval"
  static readonly state = jsonCodec(Approval, "approval")
  static readonly settings = workflowSettings({
    defaultStepTimeout: Duration.ofSeconds(5),
    steps: { check: { recovery: { maxRetries: 2, failoverTo: "finish" } } },
  })
  static readonly handlers = {
    start: command("start", s.string, Done, (w: ApprovalWorkflow, mode) =>
      w.state.status !== "new" ? w.effects.error(`already ${w.state.status}`, ErrorCode.Conflict) : w.effects.updateState({ ...w.state, status: "checking", mode }).thenTransitionTo("check").thenReply(() => done),
    ),
    status: query("status", s.string, (w: ApprovalWorkflow) => w.effects.reply(w.state.status)),
    approve: command("approve", Done, (w: ApprovalWorkflow) => w.effects.updateState({ ...w.state, status: "approving" }).thenTransitionTo("finish", "approved by hand").thenReply(() => done)),
  }
  static readonly steps = {
    check: step("check", (w: ApprovalWorkflow) => w.check()),
    slow: step("slow", async (w: ApprovalWorkflow) => {
      await new Promise((r) => setTimeout(r, 150))
      return w.stepEffects.updateState({ ...w.state, status: "slowed" }).thenEnd()
    }),
    finish: step("finish", s.string, (w: ApprovalWorkflow, note) => w.stepEffects.updateState({ ...w.state, status: "done", note }).thenEnd()),
  }
  emptyState(): Approval {
    return { id: this.entityId, status: "new", mode: "ok", note: null }
  }
  check() {
    switch (this.state.mode) {
      case "pause":
        return this.stepEffects.updateState({ ...this.state, status: "waiting" }).thenPause({ after: Duration.ofMillis(500), onTimeout: "finish", onTimeoutInput: "timed out" })
      case "fail":
        return this.stepEffects.fail("declined", ErrorCode.Forbidden)
      case "throw":
        throw new Error("check exploded")
      default:
        return this.stepEffects.updateState({ ...this.state, status: "checked" }).thenTransitionTo("finish", "all good")
    }
  }
}

// ── consumer, producing onward ──

export const Ping = s.record("Ping", { n: s.int })
export const Pong = s.record("Pong", { n: s.int, from: s.string })

export class Ponger extends Consumer<Infer<typeof Ping>, Infer<typeof Pong>> {
  static readonly componentId = "ponger"
  static readonly topic = "pings"
  static readonly message = jsonCodec(Ping, "ping")
  static readonly out = jsonCodec(Pong, "pong")
  static readonly producesTo = "pongs"
  onMessage(ping: Infer<typeof Ping>) {
    if (ping.n < 0) return this.effects.ignore()
    if (ping.n === 0) return this.effects.done()
    return this.effects.produce({ n: ping.n, from: this.subject }, { "x-ping": String(ping.n) })
  }
}

// ── timed action ──

export class Reminder extends TimedAction {
  static readonly componentId = "reminder"
  static readonly actions = {
    remind: action("remind", s.string, (r: Reminder, id) => (id === "bad" ? r.effects.fail("no such id", ErrorCode.NotFound) : r.effects.done())),
    ping: action("ping", (r: Reminder) => r.effects.done()),
  }
}

// ── agent ──

export const AddInput = s.record("AddInput", { a: s.int, b: s.int })
export const Sum = s.record("Sum", { sum: s.int })

export class Calculator extends Agent {
  static readonly componentId = "calculator"
  static readonly role = "adds numbers"
  static readonly maxToolCallSteps = 3
  static readonly tools = {
    add: tool("add", "Adds two whole numbers.", AddInput, (_c: Calculator, input) => {
      if (input.a > 1000) throw new Error("too big")
      return input.a + input.b
    }),
  }
  static readonly guardrails = {
    polite: guardrail("polite", (stage, text) => (text.includes("stupid") ? `${stage} was rude` : null)),
  }
  static readonly handlers = {
    ask: command("ask", s.string, s.string, (c: Calculator, q) => c.effects.systemMessage("You add numbers with the add tool.").userMessage(q).tools("add").guardrails("polite").thenReply()),
    askJson: command("ask-json", s.string, Sum, (c: Calculator, q) => c.effects.userMessage(q).tools("add").thenReplyJson<Infer<typeof Sum>>("{sum: number}")),
    forget: command("forget", s.string, s.string, (c: Calculator, q) => c.effects.userMessage(q).memory(false).thenReply()),
    refuse: command("refuse", s.string, s.string, (c: Calculator) => c.effects.error("not today", ErrorCode.Forbidden)),
    rogue: command("rogue", s.string, s.string, (c: Calculator, q) => c.effects.userMessage(q).tools("subtract").thenReply()),
    chat: stream("chat", s.string, (c: Calculator, q) => c.effects.userMessage(q).tools("add").thenReply()),
  }
}
