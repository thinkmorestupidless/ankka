# Contract: the deletion of a key value entity

What a service and its storage see when a key value entity is deleted. It is the behaviour an
event sourced entity already has, stated for the other kind.

## For the entity

| | Before | After |
|---|---|---|
| A handler returns `deleteEntity()` | the entity's row is removed | the row stays: the empty state, marked deleted, at the next revision |
| A command after the deletion | sees the empty state | sees the empty state |
| A write after the deletion | starts again at revision 1 (after a restart) | continues: the revision after the deletion's |
| `commandContext.sequenceNumber` after the deletion | 0 after a restart | the deletion's revision |
| The state's data | gone | gone: the stored payload is the empty state's |
| The entity's id and revision | gone | kept |

## For a view over the entity

The row for a deleted entity is removed, by the view's deletion handler (`onDelete`, whose default
deletes the row). Before, nothing reached the view and the row stayed.

## For a consumer over the entity

The deletion handler runs, with the entity's id as subject and the deletion's revision as sequence
number. Before, it never ran. In a Scala service `messageContext.sequenceNumber` is the revision
for every change, where it was 0.

## The stored form

`StateRecord(manifest, payload, deleted, expiryMillis)` is unchanged. A deletion is written as
`deleted = true`, `expiryMillis = 0`, with the serialised empty state as payload in process, and
an absent value for a remote entity. A row written before this change is never marked deleted and
reads as it did. A runtime from before this change reads a deleted row as the empty state.

No table, column or index changes.

## What is not a deletion

An entity whose state has expired. Expiry is noticed when a command next arrives; nothing is
written and nothing is delivered when the time passes.

## Tests that hold this

- a suite pinning `StateRecord`'s bytes for a live and a deleted state, written first;
- over Postgres: delete then read; delete then write, within one incarnation and across a restart;
  the revision sequence across both;
- a view over a key value entity loses its row on deletion; a consumer's deletion handler runs at
  the right revision — in process and through the remote path;
- conformance: `kv.delete-is-a-change`, `kv.delete-then-write`, `consumer.kv-sequence`.
