---
name: ankka-port
description: Port an existing system to ankka by matching what it demonstrably does — run the original locally, list its whole interface from source, drive every element against the running original and record its answers, write a specification whose every requirement says how it was established, design the ankka components, and hold the rebuild to the recorded answers with integration tests (parity tests) that outlive the original. Use when asked to port, migrate, rewrite, re-platform or rebuild an existing application, service or API on ankka, or to derive a specification from a system that already exists.
---

# Porting an existing system to ankka

A port does not ask anyone to say what the original system should do. It finds out what the system
*does*, records it, and holds the rebuild to the record. The target is **parity**: on the same requests,
in the same order, the rebuild answers as the original did. Everything below serves that target and
makes its gaps visible.

The work has three products, and a port is not finished without all three:

1. **A specification** in which every requirement says how it was established.
2. **Recordings** of the original's answers, one per scenario, kept in the rebuild's test tree.
3. **Parity tests**: integration tests in the rebuild's own test kit that replay each recording and
   compare. They never call the original, so they keep holding after it is switched off.

## 1. Settle everything before starting

A port runs for hours. Ask everything at the start, in one message, then do not ask again: an unresolved
question during the work is settled by an experiment against the original, or recorded as an assumption
(section 4), never by stopping.

- **The original.** A clone of its source, and how to run it locally: its own compose file, its README,
  its test setup. Both are needed; a port without source cannot list the interface, and a port without a
  running original can only guess its answers.
- **The scope.** The whole system, or named parts of it (a set of routes, one bounded context). Anything
  out of scope is named as out of scope, not silently absent.
- **The target language.** Prefer the original's language where an ankka SDK exists for it (Scala,
  Python, TypeScript, Rust): the domain logic can then move with little change, and most parity failures
  come from logic rewritten in a new language rather than from the platform.
- **Permission to run it.** The original runs on this machine and is driven with writes. Say so, and
  get agreement.

**Never drive a shared or production instance.** Parity recording sends creates, updates and deletes.
Run the original locally, against its own throwaway data, and nothing else.

## 2. Run the original

Start it with its own tooling, seeded as its own tests or quickstart seed it, and confirm it answers.
Run its own test suite too and keep the result: its tests are evidence of intended behaviour, and a test
that fails on the original is behaviour the original does not have.

## 3. List the whole interface

From the source, list every way the system can be reached or can act, into `specs/<port>/surface.md`:

- HTTP routes: from an OpenAPI document if the project has one, otherwise from the framework's route
  declarations. Method, path, parameters, body and response types, authentication.
- Anything else a caller can reach: CLI commands, queue or topic consumers, webhooks it receives.
- Everything it does on its own: scheduled jobs, timeouts, retries, outbound calls, emails, webhooks it
  sends.
- The data it keeps: tables or collections, constraints, and the validation code that guards them. These
  are the invariants the rebuild's entities must enforce.
- A user interface, if there is one.

Every element ends with a **disposition**: *covered* by named requirements, or *dropped* with a reason
(out of scope, no ankka equivalent, unused). An element nobody gave a disposition is the failure a port
most often hides; the list is finished only when every row has one.

## 4. Drive every element and record the answers

For each covered element, write scenarios the way a caller would use it: the ordinary case, then the
edges the source suggests (invalid input, a missing id, a duplicate, the same request twice, operations
in an order that should be refused). Run each against the original and record it in the rebuild's test
tree (for example `src/test/resources/port/` or `tests/port/`), one file per scenario:

```json
{
  "scenario": "a placed order cannot be changed",
  "requirement": "FR-012",
  "steps": [
    { "request": { "method": "POST", "path": "/orders", "body": {"customer": "c1"} },
      "response": { "status": 201, "body": {"id": "<order>", "status": "open"} },
      "capture": { "order": "$.id" } },
    { "request": { "method": "POST", "path": "/orders/{order}/place" },
      "response": { "status": 200, "body": {"id": "<order>", "status": "placed"} } },
    { "request": { "method": "POST", "path": "/orders/{order}/items", "body": {"sku": "p1"} },
      "response": { "status": 409, "body": {"error": "order is placed"} } }
  ],
  "vary": { "$.id": "generated by the original", "$.createdAt": "clock" }
}
```

- **Run every scenario twice, on fresh ids.** A field that differs between the two runs is not the
  original's behaviour but its randomness or its clock: list it under `vary` with the reason, and the
  parity test ignores its value but still requires it to be present. A field that did not differ must
  match exactly. Never add a field to `vary` because the rebuild gets it wrong: that makes the test pass
  while the behaviour is broken.
- **Capture what later steps need**, such as a generated id, and substitute it, so a scenario replays
  against a system that generates different ids.
- **Time.** Where behaviour depends on elapsed time (a deadline, an expiry), drive it by the original's
  own means if it has one (a configurable clock, a short timeout in configuration). Where it has none,
  record the requirement from the source at the `inspected` level and say that it was not exercised.
- **Outbound effects.** Point the original's email, payment and webhook calls at a local stub, and record
  what it sent as part of the scenario.

## 5. Write the specification

Write the specification (`specs/<port>/spec.md`, in the spec template if the project has one) from the
recordings and the source. Every requirement is annotated with how it was established, the strongest
that applies:

| Evidence | Meaning |
|---|---|
| `exercised` | a recording shows the original doing it; the requirement names the recording |
| `documented` | the original's own documentation or tests say so, but no recording shows it |
| `inspected` | read in the source only |
| `assumed` | a decision taken where discovery could not settle the question, with the reason |

Claim only the evidence earned: a requirement annotated `exercised` without a recording is `inspected`.
Assumptions stay in the specification, visible, so a reader knows which requirements nobody observed.
The specification carries no open clarification markers.

## 6. Design the rebuild

Map the original onto components with the `ankka-design` skill, and write the component table, with the
close alternative for each close call. Tables, services and jobs in the original are evidence, not
components: a table with a foreign key to another is often one entity's state, a cron job that scans for
expired rows is a timer per row, a service method that updates two tables in a transaction is a workflow
or a single entity whose boundary was drawn wrong.

Then list the **divergences**: behaviour the rebuild will not reproduce, each with its reason, before
writing code. Read `references/reference/limitations.md` for what ankka does not do. The divergences a
port most often meets:

- **Read-after-write through a listing.** The original may answer a list request with a row it wrote a
  moment ago, because both read one database. In ankka a listing is a view and lags its source. Either
  the parity test retries on the value that changes, and the divergence says a list may lag, or the
  scenario reads the entity by id.
- **Protocol.** A service has one HTTP port, HTTP/1.1 through the gateway. gRPC, WebSockets and other
  protocols in the original are divergences.
- **Wire format.** The rebuild must answer with the original's paths, statuses, field names and error
  bodies, or every caller breaks. Where an ankka default differs, the endpoint chooses its own response
  (`references/build/http-endpoints.md`); do not accept a difference because a default is convenient.
- **Transactions across things.** A multi-row transaction in the original becomes a workflow, which is
  eventually consistent between its steps. A scenario that observes the intermediate state diverges.

A divergence is a decision for the user, taken with the others at the end, not a reason to stop.

## 7. Parity tests, then the rebuild

Write the parity tests before the components: one integration test per recording, in the target
language's test kit (`references/build/testing.md`, "Acceptance scenarios as integration tests"), which
replays the steps with the captured values substituted, compares each response with the `vary` fields
checked for presence only, and is named after the scenario. They fail first, because nothing is built.
Break one on purpose once to see it fail for the right reason, as that section describes.

Then build feature by feature, until the parity tests for that feature pass, the rebuild's own unit tests
pass, and the `ankka-inspect` skill finds the running rebuild consistent with the component table. A
parity test that cannot pass because of a listed divergence is marked with the divergence it
demonstrates, not deleted.

## 8. Report

- **Parity:** scenarios passing out of scenarios recorded, per interface element, and the list of any
  that do not pass with why.
- **Surface:** elements covered, dropped (with reasons) and out of scope, which add up to the whole list.
- **Evidence:** requirements by evidence level; every `assumed` one named.
- **Divergences:** each with its reason and the scenario that demonstrates it.
- What the original's test suite said, and anything the port could not exercise.

Stop the original and everything the port started. The recordings and parity tests stay with the rebuild;
the original is no longer needed to run them.

Moving the original's existing data into the rebuild is a separate task from porting its behaviour, and
is not part of this one: the rebuild's entities are rebuilt from events, which the original never wrote.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/coding-agents.md` — Give a coding agent ankka's documentation as a skill, and its hands through `ankka mcp` — the CLI's verbs, the local services on your machine, and this version's docs as MCP tools.

### Concepts

- `references/concepts/designing-services.md` — The concepts that shape an ankka system — consistency boundaries, events, read models, reactions, processes, time, agents, the edge and service boundaries — with a worked example mapping a checkout onto components.
- `references/concepts/consistency.md` — The guarantees ankka gives — strong consistency per entity, eventually consistent views, exactly-once and at-least-once delivery, ordering, timeouts and timers — and what each means for the code you write.
- `references/concepts/polyglot.md` — How ankka hosts a service in Python, TypeScript or Rust — the runtime runs beside a process as a sidecar or loads a WebAssembly module, owning everything durable while the service's code decides.

### Build

- `references/build/http-endpoints.md` — Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala, Python or TypeScript.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in every language, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in Scala, Python, TypeScript and Rust, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

### Run and deploy

- `references/deploy/run-locally.md` — Run an ankka service on your own machine against a local Postgres, configure its database and HTTP port, form a two-node cluster in two terminals, and run a Python service beside the sidecar.

### Reference

- `references/reference/limitations.md` — What ankka does not do yet, stated plainly and grouped — the platform, networking and security, observability, components, and SDKs and releases — so you can plan around a gap before you reach it.
