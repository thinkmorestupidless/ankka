---
name: ankka-workflows
description: Write, change or test an ankka workflow (a durable multi-step process with commands, steps, transitions, pauses, timeouts, retries and compensation) or a timer and timed action (a named, database-backed call made later) in Scala or Python. Use when the task names a workflow, a step, stepEffects, transitionTo, thenPause, RecoverStrategy, a saga or compensation, a timer, TimerScheduler, a timed action, or a deadline such as "cancel after thirty minutes".
pages:
  - build/workflows.md
  - build/timers.md
  - build/component-client.md
  - build/multi-agent-orchestration.md
  - concepts/consistency.md
  - concepts/wire-names.md
  - build/testing.md
---

# ankka workflows and timers

A workflow is a durable process: an instance has an id, a state of your type and a current step, and
every transition is journalled before the next step runs, so a crash resumes at the step it reached. A
timer is a call the platform makes later, stored in the database under a name, retried until its handler
reports success. Both exist because a chain of calls from an endpoint dies with the request.

## Rules for workflows

1. **Commands decide and transition; steps do the work.** A command (called through the component
   client) sets state and starts or redirects the workflow: `effects.updateState(s).transitionTo(step)
   .thenReply(r)`. A step (run by the runtime, never called) does calls through `context.componentClient`
   and returns `stepEffects.updateState(s).thenTransitionTo(next)`, `.thenPause()`, `.thenEnd` or
   `.thenFail(message)`. Queries are read-only commands.
2. **Guard the start.** A command that transitions a running workflow redirects it. Check the state and
   refuse with `Conflict` when a second start is a mistake, so a double click cannot start two payments.
   Make the workflow id the id of the thing it processes (the order id) so a repeat addresses the same
   instance.
3. **Every step may run more than once.** A step that acted and crashed before its transition was
   journalled runs again. Make each call idempotent: pass the workflow id as the idempotency key to an
   external provider; call entities that refuse a duplicate.
4. **`thenFail` is a decision; a throw or a timeout is a fault.** Only faults get recovery. Recovery is
   declared in settings (`defaultStepTimeout` 30s, `stepTimeout(step, d)`, `defaultRecovery`,
   `stepRecovery(step, RecoverStrategy.maxRetries(n).failoverTo(step))`), because the runtime enforces
   it and a step cannot enforce it on itself.
5. **Retries default to zero.** Give a step retries only when running it again is harmless or the target
   refuses a duplicate. A step that exhausts its retries with no failover fails the workflow.
6. **A failover step takes no input.** Compensation reads what the workflow recorded in its state, so
   record what each step did (`updateState`) in the same transition, and keep the state to what the
   process needs, not copies of the entities it coordinates.
7. **Size timeouts to the slowest call.** A step calling an agent needs far more than 30 seconds; declare
   a step timeout to match and await the call with an explicit timeout
   (`ComponentClient.await(future, 90.seconds)`). `timeout` on the whole workflow is a ceiling from its
   start, after which it fails.
8. **Pausing is how a workflow waits.** `thenPause()` waits for a command to transition it;
   `thenPause(after, onTimeout)` gives up on its own. Use it for approvals and for deadlines that belong
   to this instance; use a timer for deadlines on things that are not workflows.
9. **The domain state does not say whether the engine is running.** A state that reads "consulting" looks
   the same in flight and failed an hour ago. In Scala every workflow answers the runtime's lifecycle
   query (`forWorkflow(id).lifecycle(Companion)`: `Running`, `Paused`, `Completed`, `Failed`, the pending
   step, retries, the failure reason). Handler names beginning `ankka:` are reserved.
10. **Step names are wire names.** `step("deposit")(_.depositStep)`: renaming the method is safe; renaming
    the string breaks every instance in flight. A step with an input needs a `given Serializer` for it in
    the companion, declared before the step.

## Rules for timers

1. **A timer is at least once, and "nothing to do" is success.** The handler runs once, or again after
   a failure, possibly after the situation changed. Check current state through the component client and
   return `effects.done()` when the work no longer applies. Returning `effects.error` for stale work
   reschedules it with backoff (3s doubling to 30s) forever. This is the sharpest edge in the API.
2. **The name is the identity.** Scheduling again under one name replaces the schedule, so "extend the
   deadline" is one call. Build names from the subject (`expire-<orderId>`) so the code that confirms the
   order can `delete` the timer by name without having stored anything. Cancelling a missing timer is not
   an error.
3. **Pass an id, not the data.** A timer's argument is limited to 1024 bytes. The handler reads what it
   needs when it fires.
4. **Order the extensions.** `TimerRuntime` provides `timerScheduler` once started; add it before
   `HttpServer` so an endpoint can be handed the scheduler when it is built. Asking earlier throws.
5. **Timers live in the service's database.** They outlive restarts and redeploys, and the sweeper is a
   cluster singleton polling once a second, which bounds lateness. Two services must never share a
   database: each deletes timers whose component it does not recognise.

## Coordinating agents from a workflow

Sequential steps for dependent calls, one journalled transition each. `invokeAsync` for independent
agents, collected with one `await` per result, so the step takes as long as the slowest. A selector
agent with `MemoryProvider.none` for dynamic routing, with the workflow validating the names it returns.
All agents on one session, the workflow's id. Let a workflow finish before a test ends, or it keeps
consuming the scripted model and starves the next test.

## Testing

Scala workflows and timers are tested through `AnkkaTestKit`, because transitions, recovery, timeouts and
firing are the runtime's behaviour; register `TimerRuntime` with a short poll interval to keep timer
tests fast. Python has `WorkflowTestKit` and `TimedActionTestKit`, which run commands, steps and handlers
by hand with no sidecar. Assert on the lifecycle and the state, not on timing.

## Mistakes to check for

- Work done in a command handler instead of a step, or a step that a caller tries to invoke.
- A retry on a step that charges a card, or a failover step that expects an argument.
- A 30-second default step timeout on a step that calls a model.
- A timed action that errors when the order is already confirmed.
- A test that reads the domain state to decide the workflow is finished; read the lifecycle.
