# Contract: versions and rebuild for a view that reads entities

Feature 024 gave a view that reads a topic a declared version, a recorded version, an emptying that
happens once, and the rule that an instance behind the recorded version stops writing. This
contract extends exactly that to a view that reads entities, plain or keyed. Nothing here changes
for a topic.

## Rules at registration

`TopicSourceRules` (which holds rules T1–T5) changes one rule and is otherwise as it was.

| Rule | Was | Is |
|---|---|---|
| T4 | a version on a view or a consumer that reads an entity is refused | a version on a **consumer** that reads an entity is refused; on a view it is accepted |
| T5 | a version below 1 is refused | unchanged, for every view and consumer |

## What a version is, for entities

| | |
|---|---|
| declared version | what the view's companion (or discovery) states; none is 1 |
| recorded version | `ankka_view_versions.version` for the view; none is 1. The table is now created by any service with a view, and a row ensured for every view |
| projection name | what the view's offsets are stored under. At version 1: exactly today's name, so no existing offset moves. At version *n* ≥ 2: a name carrying the version, where no component id can spell it |

The daemon process the projections run in is named for version 1 whatever the version
(`ViewProjections.daemon`): its coordinator runs on the oldest instance, and only if that instance started
the same name, so every instance must. Only the projection's id carries the version. The projection id
carrying the version is to an entity source what the group naming the version is to a topic: a new name has read nothing, so it begins at the source's first event or state, and
the name it replaces keeps its offsets, untouched and never used again.

| View | Source | Projection name at version 1 | at version *n* ≥ 2 |
|---|---|---|---|
| plain | its one entity | `ankka-view-<id>` | `ankka-view.v<n>-<id>` |
| keyed | each entity | `ankka-keyed-view-<id>+<source id>` | `ankka-keyed-view.v<n>-<id>+<source id>` |

`ViewProjections.name` is the one function that gives every one of these, and no other code
spells a projection's or a daemon's name. A component id may contain `.`, `-` and `_`, so a
version or a source attached to the id could be spelled by another id: `summary` at version 2 and
`summary-v2` at version 1 would be one name, and one set of offsets. So, as `ConsumerGroups` does
for a group:

- the version sits beside the kind, before the id, behind a `.` that today's names never have
  there: every version-1 plain name begins `ankka-view-`, every versioned one `ankka-view.v`;
- a keyed view's names begin `ankka-keyed-view`, which no plain name does;
- a keyed view's id and its source's are joined by `+`, which no component id may contain.

**Properties a test holds** (`ViewProjectionsSuite`): a plain view at version 1 is named exactly
as it is today; two different (shape, view, source, version) never give one name, over ids that
try to (`summary` and `summary-v2`, `v2-summary`, `a` with source `b-c` and `a-b` with source
`c`, an id ending `.v2`); every name is one a daemon process and an offset row accept.

## At startup, per view

1. Read the recorded version.
2. **Equal to declared**: start the view's projections under the declared version's names.
3. **Lower than declared**: `ViewVersions.rebuild`, unchanged from 024 — under the view's
   exclusive lock, read the recorded version again, and only if it is still lower empty the table
   and record the declared version, in one transaction. Then start the projections. The instance
   that waited for the lock finds the work done and only starts.
4. **Higher than declared**: the view is *behind*. Say so (L2), and start its projections all the
   same — the daemon process must exist on every instance for its coordinator to run — knowing every
   write they try is refused by the guard, which pauses them. The service is ready; the rows are
   served as they are. During a roll, a slice held by an older instance waits, paused, until that
   instance leaves, so a view may lag until the roll completes.

Unlike a topic, nothing is asked of anything before the table is emptied: an entity's journal is in
the same database as the table, and holds everything.

## While running: the guarded write

Every write a view that reads entities makes is made in the change's transaction, after:

1. the view's lock — **shared** for a plain view, **exclusive** for a keyed view (which is also
   what makes it handle one change at a time, see [keyed-views.md](keyed-views.md));
2. reading the recorded version.

If the recorded version equals the declared one, the write is made. If not, nothing is written,
the transaction fails so the source's progress is not recorded, the projection is paused, and the
instance says it is behind (L2), once.

A rebuild holds the exclusive lock, so a write either commits before the emptying or starts after
it and finds the new version. This is 024's argument, R7 there, and the same two statements.

## Log lines

| | When | Says |
|---|---|---|
| L1 | a rebuild empties the table | the view, the version it was recorded at, the version it is now, each source read again |
| L2 | an instance finds the view behind | the view, declared, recorded; that this instance reads and writes nothing for it |
| L3 | a rebuild finds the work done | the view, the version |

L2's wording is 024's for a topic with "its topic" replaced by "its sources". The log is the one
place an entity view says it is behind: it is not in the metrics or the service document, which
list topic sources.

## What a rebuild serves while it runs

The table is empty at the start and fills in each source's own order. Queries are answered from
what is there. For a keyed view whose handler reads rows another source writes, a handler meets
the table as the rebuild has so far made it, exactly as it did the first time the view was built;
a handler that needs a row to exist must cope with it not existing yet, as it always had to.

## What is not offered

- Lowering a version never rebuilds.
- A rebuild cannot be asked for without raising the version.
- An instance running a release before this one has no guarded write for a view that reads
  entities. Raising such a view's version while one is still running lets it write rows the
  rebuild then has to overwrite, and a row it writes after the rebuild passed that entity stays
  until the entity changes again. The upgrading page says: raise a view's version in a deploy
  after the one that brought every instance to this release.
