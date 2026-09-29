# Autonomous agents and tasks

> How an autonomous agent works — handed a typed task, iterating on its own until it completes, fails or spends its budget, with every iteration recorded so a crash resumes where it stopped.

Source: https://docs.ankka.cloud/concepts/autonomous-agents/
An autonomous agent is a component that is handed a **task** and works it on its own. It calls the
model, runs the tools the model asks for, records what happened, and goes round again — for as many
iterations as it decides it needs — until the model completes the task with a typed result, reports that
it cannot, or the task's iteration budget runs out. Nobody waits on the other end of a socket: whoever
wanted the work done reads the task's result later, or watches the agent's progress as it happens.

ankka has two kinds of agent. A [request agent](agents.md) answers one request while its caller waits,
and is the right shape for a chat message or a single decision. An autonomous agent is the right shape for
a job: "answer this, using these tools, and give me a structured result when you are done".
[Autonomous agents](../build/autonomous-agents.md) shows how to write one.

## A task is a record of its own

A task is a durable record with its own id. It is created by whoever wants the work done and outlives the
agent that does it. It carries:

- a **task type** — a name, a description the model reads, the shape of its result, and rules the result
  must satisfy;
- **instructions**, and optionally **attachments**: content that travels with the task, inline or as a
  reference an agent's tools can fetch;
- **dependencies**: other tasks that must complete first. The result of each is shown to the model when
  the task starts.

A task moves through `pending`, `assigned` and `in-progress` to one of `completed`, `failed` or
`cancelled`, which are final. A result that a rule refuses puts the task in `result-rejected`, with the
reason, until the agent's next attempt. Anyone holding the task's id can read its record and decode its
result as the task type's result, or wait until it ends. A task whose dependency fails or is cancelled is
cancelled, and so, in turn, are the tasks that depend on it. A failed task is not retried by the
platform: trying again is a new task.

A task type's name is its wire name. It is written into every task of that type, so it is declared
separately from the code holding it, for the reason every handler's wire name is (see
[Handlers and wire names](wire-names.md)).

## An instance works one task at a time

An autonomous agent runs as **instances**, each named by an id the caller chooses. An instance is created
by its first assignment. It works one task at a time, in the order they were assigned; a task waiting on a
dependency lets a later one that is ready run first. A caller can suspend an instance, which stops it
before its next model call while keeping its task and its queue, and resume it. A caller can terminate an
instance, which is permanent: its tasks go back to `pending`, unassigned, for another instance to take,
and its id can never be used again. A caller can also cancel one task, which stops it at the end of the
iteration in progress.

To run a single task without naming an instance, a caller asks the platform to create one for it; that
instance ends when its task does.

An instance with nothing to do and nobody watching leaves memory after a while and costs nothing but its
records until it is next addressed. An instance with work does not wait to be addressed: after a restart,
a crash or a move between nodes, it is started again by the platform and carries on.

## The loop, and what is recorded

Each iteration, the model is shown the agent's description and instructions, the task type's description
and result shape, the task's instructions, attachments and dependency results, where it is in its budget,
and everything it has done on this task so far. It is given the agent's tools and two more that every
autonomous agent has: `complete_task`, whose input is the task type's result, and `fail_task`, whose
input is a reason. The model ends a task only through those two; a reply that calls neither is recorded and
the next iteration follows.

Every iteration is recorded before the next begins, in this order: that the iteration started; the
model's response; that the iteration completed; then the results of the tools the response asked for. An
instance that stops anywhere in that sequence resumes from the last thing recorded:

- a model call whose response was recorded is **never made again**;
- a tool whose result was not recorded **is run again**. Tools are run at least once, not exactly once.
  A tool with a side effect should tolerate being repeated — by checking before it acts, or by making the
  action idempotent with a key the task supplies.

What an instance has done on a task is kept as a session of [session memory](agents.md), one session per
task, so it is durable and compacted like any conversation. The task's own instructions, attachments and
dependency results are not part of that history and are shown to the model whole on every iteration.

## The budget, the rules and the result

A task type is accepted by an agent with an **iteration budget**: the most model calls the agent may spend
on one task. As a task nears it, the model is told how many iterations remain, and on its last iteration
that this is the last. A task that reaches its budget fails, saying so, and the instance moves on to its
next task. A model call that fails — the provider is down, or does not answer in time — is tried again
for the same iteration after a pause, without spending the budget; too many in a row fail the task with
the last error.

A result the model sends with `complete_task` must first decode as the task type's result. One that does
not is not the task's end: the model is told why, and tries again. A result that decodes is checked by
the task type's rules in the order they were declared, then by the agent's output guardrails. A result
one of them refuses puts the task in `result-rejected`, and the refusal goes back to the model, which
tries again within the same budget. A rule that throws has decided nothing, and the check is simply made
again. The agent's input guardrails see the task's instructions before any model call, and a task they
refuse fails at once.

## Watching an instance

An instance's **notifications** say what it does as it happens: that it activated or left memory, that
an iteration started, completed or failed, that a task was assigned, started, completed, failed,
cancelled or had its result rejected, that a task is waiting on a dependency — and, once per condition,
that a task is approaching its budget, that model calls keep failing, or that a dependency has been
pending too long. An endpoint can forward them to a browser as server-sent events.

Notifications are live. A subscriber sees what happens from the moment it subscribes and nothing before;
the durable account of a task is its record. A subscriber that reads too slowly does not slow the instance
down: it misses the oldest notifications it had not read, and is told how many. While anyone is watching
an instance, it stays in memory.

## Choosing between them

- **A request agent** when a caller is waiting for the answer: a chat turn, a classification, a single
  decision inside a workflow step.
- **An autonomous agent** when the work has no caller waiting, needs as many model calls as it takes,
  has a result someone will read later, should survive a crash, and should stop after a budget.
- **A [workflow](../build/workflows.md)** when the steps are fixed and known in advance, including when
  some of those steps ask agents for decisions. See
  [Multi-agent orchestration](../build/multi-agent-orchestration.md).

## In other languages

A Python or TypeScript service declares an autonomous agent's whole definition — its description, tools,
guardrails, task types and budgets — and the sidecar runs everything else: the loop, the model, the task
records and the instance's own record. The process is asked for three things only, each naming the task
it is for: to run a tool, to check a guardrail, and to check a result against its task type and rules. A
task and its instance therefore survive the process restarting; see
[Services in other languages](polyglot.md).

## What this does not do yet

Autonomous agents do not yet coordinate with each other: there is no delegating a subtask to another
agent, handing a task on, leading a team over a shared backlog, or moderating a conversation. The
[limitations page](../reference/limitations.md) lists these and the rest.
