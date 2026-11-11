# Data model: Every client reads a workflow's standing and how a copy stands

Nothing is stored. Both answers are read from what the runtime already holds; the wire forms are in
[contracts/client-reads.md](contracts/client-reads.md).

## Standing (`WorkflowLifecycle`)

| Field | Meaning | Rule |
|---|---|---|
| status | not started, running, paused, completed or failed | exactly one of the five; never unknown (unknown is only a change's, protocol 1.16) |
| pending step | the step the workflow is on or waits after | absent when it waits on none |
| retries | per step name, how many times it was retried | a step never retried is absent; never negative |
| failure | why it failed | present exactly when the status is failed |

Transitions are the engine's (`WorkflowHost.Status`): not started → running → (paused ↔ running)* →
completed or failed. The read observes them; it never causes one.

## Replication status (`ReplicationStatus`): how a copy stands

| Field | Meaning | Rule |
|---|---|---|
| home | the installation that handles the entity's writes | absent only before the copy has recorded one |
| replication set | the installations that hold the entity | contains the home when there is one; sorted on the wire |
| pending handover | the installation a handover waiting goes to | absent when none waits |
| conflict | the other home, when the entity was created at two installations | present exactly when in conflict |
| position | how many records this copy's history holds | never negative; at a follower it may be behind the home |

An installation outside the replication set answers with the copy it last held (Scala's behaviour,
unchanged).

## Rust in-memory standing (component test kit only)

`InMemory` keeps, per `(component, workflow id)`, the standing of each workflow the kit runs:
not started when created, running once started, paused on a pause effect, completed on its end,
failed with the reason when a step fails; with the step it is on. The kit applies no recovery, so
its retries are always empty; a test that needs retries sets them with `with_lifecycle`. An answer set with `with_lifecycle` or `with_replication` is kept apart and wins.
