# Contract: conformance cases (`ConformanceSuite`, prefix `auto.`)

Routes are under `/autonomous` (their own endpoint, clear of the reference's generic forwarder). The reference service of every target (Scala `ConformanceReference`, Python
`examples/shopping_cart/conformance.py`, TypeScript `examples/shopping-cart/conformance.ts`)
declares one autonomous agent `answerer` accepting task type `answer` (result `{answer: string,
sources: string[]}`, rule `cites-sources` rejecting an empty list, budget 4) with tool `lookup`
(as the request agent's) and guardrail `no-secrets`, and routes:

| Route | Does |
|---|---|
| `POST /autonomous/tasks/{type}` body = instructions | `tasks.create` + `runSingleTask`; answers `{"taskId","instanceId"}` |
| `GET /autonomous/tasks/{id}` | `forTask(id).get()` as JSON |
| `POST /autonomous/tasks/{id}/cancel` | `cancel` |
| `POST /autonomous/instances/{instance}/assign` body = `["id",…]` | `assign` |
| `POST /autonomous/instances/{instance}/{suspend\|resume\|terminate}` | the operation |
| `GET /autonomous/instances/{instance}/state` | `state` |
| SSE `GET /autonomous/instances/{instance}/notifications` | forwards `notifications()` |
| `POST /autonomous/tasks/{type}/dependent` body = `{"instructions","dependsOn":[…]}` | create with dependencies, no run |

Cases, all three targets unless marked:

| Name | Asserts |
|---|---|
| `auto.completes-with-typed-result` | script: `lookup` then `complete_task`; task `completed`, result decodes, `lookup` received the model's arguments, `iterations == 2` |
| `auto.fails-on-request` | script: `fail_task`; status `failed`, reason equals the model's |
| `auto.malformed-result-is-tool-error` | script: `complete_task` with `{"answer": 1}` then a good one; completed on the second; the second request carries the decode error as a tool result |
| `auto.rule-rejects-then-accepts` | empty sources then filled; passed through `result-rejected`; second request carries "sources must not be empty" |
| `auto.budget-fails-task` | script never completes; `failed` after 4 model calls; reason names the budget; request 4 says it is the last iteration |
| `auto.queue-runs-in-order` | assign three to `r1`; state lists them; completed in order |
| `auto.suspend-resume` | suspend an idle instance; assign; no model call for 2s, the task stays `assigned`; resume; completes |
| `auto.terminate-unassigns` | terminate `r2` with one working and one queued; both `pending`, no assignee; further `assign` is `409` |
| `auto.cancel-queued` | on a suspended instance, cancel a queued task: `cancelled`, and it leaves the queue (cancelling a task mid-iteration needs a tool the test can hold, which only the Scala fixture has: `AutonomousAgentSuite`) |
| `auto.dependency-result-in-context` | `B` depends on `A`; run `A` then assign `B`; `B`'s first request contains `A`'s result JSON |
| `auto.dependency-failure-cascades` | `A` fails; `B` becomes `cancelled` naming `A` (process targets: through the sidecar's consumer) |
| `auto.notifications-in-order` | each `data:` is a JSON string; parsing it yields a notification object; the sequence of `type`s for a reject-then-complete run |
| `auto.notifications-no-replay` | subscribe after completion; nothing but what happens next |
| `auto.tool-error-continues` | `lookup` throws; the model sees an error result; task completes |
| `auto.guardrail-fails-task` | instructions containing `sk-` → `failed` with `no-secrets`, zero model calls |
| `auto.rule-check-fault-is-iteration-failure` | the reference's `steady` rule throws the first time it sees `flaky-once` → the check is made again, not taken as a rejection; the model is not asked again (all targets) |
| `auto.survives-process-restart` (process targets, proven by `RemoteAutonomousAgentSuite`) | assume-skipped here, as `agent.session-survives-process-restart` is |
| `auto.state-of-idle-instance` | `state` on an id never used: `idle`, empty queue, no wake (no `Activated` seen by a subscriber attached after) |

TypeScript runs every case: the declaration and client are what the cases exercise, and the
reference service's routes are ordinary endpoints.
