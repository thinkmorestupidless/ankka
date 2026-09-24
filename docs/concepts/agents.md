---
title: Agents and sessions
description: How an ankka agent works — sharded by session, one request at a time, with session memory kept as an event sourced entity and the model loop, tools and guardrails run by the platform.
kind: concept
languages: [scala, python]
components: [agent]
related: [build/agents.md, build/streaming.md, build/multi-agent-orchestration.md, concepts/effects.md]
---

# Agents and sessions

An agent is a component that carries out a task by talking to a model. Like every other ankka
component, its handler returns a description rather than doing the work: which instructions to send,
which message, which tools the model may call, which guardrails apply, and what shape the answer takes.
The platform runs the conversation that description asks for. It calls the model, dispatches tool calls,
records the conversation and counts tokens.

This page explains how that works and why it is built this way. [Agents](../build/agents.md) shows how
to write one.

## An agent is addressed by session

An agent instance is identified by a **session id**, not by an entity id. A session is one
conversation. Every request to an agent names the session it belongs to, and the platform shards
agents by that id across the service's instances, exactly as it shards entities by theirs.

Requests to one session are handled strictly one at a time. A second request that arrives while the
first is still talking to the model waits in a queue until the first has finished. Without that rule,
two overlapping requests would read the same history, both append to it, and neither turn would
acknowledge the other. With it, a conversation is a sequence, however many callers it has.

Different sessions run concurrently, on any instance. Fanning out to several agents at once is safe for
that reason: the concurrency is across sessions, and each session's own history is only ever touched by
one request.

## Session memory is an event sourced entity

A session's conversation is stored by an [event sourced entity](../build/event-sourced-entities.md) that
the platform registers for you, with the component id `ankka-session-memory`. Each user message,
model reply and tool result is an event in its journal, and the conversation is the fold of those
events. Several properties follow from that, rather than being features built separately:

- **Durability.** A conversation survives a restart, a rolling deployment and a crash, because it is a
  journal like any other.
- **Collaboration.** Several agents share a conversation by addressing the same session id. Every
  stored message records which agent wrote it, so an agent can read the whole conversation or only
  what particular agents said.
- **Observation.** Memory events can be consumed like any entity's events. Compaction is built that way.

What an agent reads from memory and whether it writes to it is part of its effect. It can read the
whole history, only the last few messages, only messages from named agents, or nothing at all. A
routing agent that decides which specialists to consult typically reads and writes nothing, so its
routing chatter never reaches the specialists.

## The agent loop

The loop is the platform's, not the agent's. For each request it:

1. runs the input guardrails against the user's message, before any model is called;
2. assembles the request: instructions, the conversation as memory allows, any extra context the
   handler attached, and the declared tools;
3. calls the model;
4. if the model asked for tools, runs them, sends their results back, and calls the model again;
5. runs the output guardrails against the final answer, before anything is written to memory;
6. writes the turn to session memory, with the tokens it used, and replies.

A request may take at most a fixed number of tool round trips, 100 by default, and fails with an
internal error if the model is still asking for tools after that. The limit is set per agent.

### Tools are declared, not discovered

A tool is a name, a description the model reads, a set of typed parameters, and a function. In Scala a
tool is built with `FunctionTool`, and each parameter's type supplies both the JSON Schema the model is
shown and the decoder that reads the model's arguments back. They come from one instance on purpose: a
parameter cannot be described to the model as an integer and then decoded as something else. In Python
a tool's parameters are the fields of an input dataclass, which the SDK turns into the schema.

A tool that fails does not fail the request. Its error goes back to the model as an error result, and
the model usually recovers by correcting its arguments.

### Guardrails

A guardrail inspects text and either passes it or rejects the interaction with a reason. Input
guardrails run before the model is called, so a rejection costs nothing. Output guardrails run before
memory is written, so a rejected answer leaves no trace in the conversation. Guardrails run in the order
they are declared and stop at the first rejection, so put the cheap ones first.

On a streaming handler, output guardrails see the answer only after its tokens have been delivered.
They can still keep it out of memory, but they cannot take back what the reader saw. Anything that must
never be shown belongs in an input guardrail.

### Structured answers

A handler can ask for the answer as text, as a stream of tokens, or decoded into a type. A typed answer
lets a workflow act on what a model decided, for example a list of specialists to consult, and treat a
reply that does not decode as a failure rather than as prose.

## Model providers

Everything above sits on a `ModelProvider`, which completes a request and optionally streams one; a
provider that cannot stream falls back to one completion. Adding a model vendor means writing one
adapter, not re-implementing agent behaviour. `AnthropicProvider` ships with ankka and uses the
official Anthropic Java SDK for transport. `TestModelProvider` answers from a script and fails loudly
when the script runs out, so a test whose model quietly returned a default cannot pass by accident.

Every model call reports the tokens it used: input, output, and cache reads and writes where the
provider reports them. The platform adds them to the session and shows them in
[the local console](../operate/local-console.md). It reports tokens, not money, because the provider
reports tokens; there is no price table, and cost is shown as unknown rather than as zero.

## Compaction

A long conversation eventually outgrows any model's context window. Compaction replaces the oldest part
of a session with a summary, keeping the most recent messages verbatim, because those are what the model
needs in full.

It runs as a [consumer](../build/consumers.md) of session memory's own events, not inside the agent's
request. The request that pushes a session over the limit therefore never waits for a summarisation
call. The limit is a character count, not a token count, and should be set well below the model's
context window. Compacting a session twice folds the earlier summary into the new one rather than
stacking summaries.

The summariser calls the model provider directly rather than through an agent. An agent would need a
session, and the only sensible session is the one being summarised, so it would append its own turns to
the history it is trying to shorten. Compaction is best effort: a summarisation that fails is skipped
and the consumer moves on, because a consumer that kept retrying one session would stall compaction for
every session.

## Agents in Python

A Python agent declares the same things: instructions, tools, guardrails and the answer's shape. Its
handler returns the plan as data. The loop does not run in the Python process. It runs in the
[sidecar](polyglot.md), which calls the model, keeps and compacts the session and counts tokens. The
Python process is asked for three things only: to plan a request, to run a tool with the model's
arguments, and to check a guardrail.

Two consequences matter when deploying. The model's API key is configured on the sidecar, and the
Python process never holds it or calls a model. A session is the sidecar's entity, so it survives the
Python process restarting.
