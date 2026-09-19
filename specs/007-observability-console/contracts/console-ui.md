# Contract: The Local Console

`ankka local console` → a web UI on loopback, imitating
[Akka's local console](https://doc.akka.io/getting-started/author-your-first-service.html#_explore_the_local_console).
Hand-written HTML and JavaScript served from the CLI's resources by `jdk.httpserver`; no build
step, no bundler, no lockfile (R7).

```
$ ankka local console
Local console: http://localhost:9889
```

Akka uses 9889. Imitating the port as well as the idea costs nothing and means a developer who
knows one knows the other. If it is taken, say so and take the next free one — do not fail.

## The five panels

### 1. Services

The landing page: every ankka service the `Source` reports. Locally that is every service running
on this machine, from the registry (R3).

| Column | From |
|---|---|
| Name | the service |
| State | whether it answers |
| Instances | its instance list — always `1` locally, and a column anyway |
| Address | the instance's real HTTP address |

- No services running → say so plainly. Starting a console before a service is the normal order,
  not a mistake (P1 scenario 1).
- A service that starts appears within 5 seconds; one that exits disappears within 5 (SC-007).
- A stale entry — the file exists, nothing answers — is dropped and its file removed. `kill -9`
  during development is common, and a dead row that errors on click is worse than no row.

### 2. Components

A service's registered components, grouped by kind, from `GET /observability/service`. This is the
inventory the runtime already holds because registration is explicit; the console discovers
nothing.

### 3. Invoke

The built-in HTTP client, replacing the `curl` a developer would otherwise write. Route templates
become a form: path parameters, query parameters, headers, body.

**The request goes to the service's own HTTP address, as an ordinary client** (R5). Consequences,
all deliberate:

- The `acl` applies. A route that would refuse an external caller refuses the console, and the
  console shows the refusal (FR-012). There is no privileged path to get wrong later.
- A service with `"http": false` has nothing to invoke; the panel says so.
- The response is shown whole — status, headers, body.
- A streaming (SSE) response renders **as it arrives**. An agent's stream may run for a long time
  and a panel that waits for the end shows nothing for most of the interesting period.

After the response, the trace for that request is offered directly — the invoke-then-explain loop
is the point of the panel.

### 4. Traces

Recent requests, newest first, each expandable into its tree.

Each span shows its component, handler, duration and share of the total. **Unattributed time is
shown as its own row**, not distributed across spans — it is usually the answer. Akka's own example
is a request that was 99.9% waiting on a model; a console that hid that behind tidy percentages
would have hidden the finding.

- A partial trace is labelled "this window does not hold all of it", without naming the cause.
- An orphan span appears at the root, its parent marked unknown. Never reattached by guessing (R4).
- A failed invocation shows the failure and which component produced it (P1 scenario 6).
- The header states that this is a bounded recent window (FR-017).

### 5. Agents

Sessions for a service's agents. A session opens into its stored memory — the conversation as the
platform holds it, which is an event-sourced entity and so is the real record, not a reconstruction.

Beside it: tokens in, tokens out, and cost — per session and totalled per service.

**Unknown cost shows as `—` with a reason**, never `0` (R9). A service with no agents does not show
this panel at all rather than showing an empty one.

## Where the data comes from

The UI never reads the registry, or any file, or any service, directly. It reads the aggregation
API, which reads a **`Source`**. This feature ships exactly one — `LocalSource`, backed by registry
discovery — and the UI must be written as though it does not know which one it has.

Concretely, for this feature that means:

- **A service is rendered from a list of instances**, and the list happens to have one entry. The
  Services panel shows an instance column even when it always reads `1`, because a panel that shows
  a single address is the one that has to be rebuilt later.
- **`partial` on a trace is rendered as "this window does not hold all of it"**, without naming
  eviction as the cause. Locally eviction is the only cause; saying so in the UI would make the
  other cause a new case later.
- **No panel assumes the data is cheap to fetch.** Locally it is a loopback call; a later source
  fans out across pods. Nothing should be written such that it only works when reads are instant.

None of this adds a panel or a feature. It is a constraint on how the five panels are written.

## Cross-cutting

- **Loopback only.** No authentication, because it reaches only this machine's services and holds
  no credential. This is the reason the console is local-only, and the reason it is safe without a
  login.
- **It shows whatever the application put in its state** — prompts, personal data, arguments. On
  a developer's own machine that is the same exposure as a debugger. It is why this does not become
  a hosted console without an authentication story of its own.
- **Read-only except Invoke**, which is an ordinary HTTP request and is labelled as such.
- **No persistence.** Reloading the page re-reads the services; the console stores nothing.
- **Degrade, never blank.** A service that stops answering mid-view shows what was last known plus
  a plain statement that it is gone — not an error page, and not a spinner forever.
