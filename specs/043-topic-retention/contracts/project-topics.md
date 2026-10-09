# Contract: a project's topics — settings, defaults, bounds and changes

What a member writes and reads. Held by `features/broker/retention.feature`,
`features/broker/cleanup-policy.feature`, `features/broker/copies.feature`,
`features/broker/changing.feature` and the suites named in the plan.

## Declaring

```text
PUT /projects/{id}/topics/{name}
{
  "partitions": 12,
  "retention": "90d",            "retentionSize": "50GiB",        "cleanup": "delete",
  "tombstoneWindow": "1d",       "minCompactionLag": "0s",        "maxCompactionLag": "none",
  "copies": 3,                   "minInSync": 2,
  "compacted": false,            "contract": { "name": "...", "schema": {...} },
  "removes": "messages older than 30 days"
}
```

Every field is optional, `partitions` included on a topic already declared. On a topic's first
declaration a setting left out is filled from the installation's default and marked so in the
listing, and `partitions` is required. On a redeclaration a setting left out keeps its current value,
so changing one setting never resets another, and partitions left out are unchanged. Values: durations `<n>ms|s|m|h|d`; `retention` also `"everything"`;
sizes `<n>B|KiB|MiB|GiB|TiB`, `retentionSize` also `"none"`; `maxCompactionLag` also `"none"`;
`cleanup` one of `delete`, `compact`, `compact,delete`. `compacted: true` is the short form of
`cleanup: "compact"` (kept from the earlier shape); the two disagreeing is refused. `removes` is sent
only on a declaration that removes messages (below).

```bash
ankka projects topics set transactions --partitions 12 --retention 90d --retention-size 50GiB -p money
ankka projects topics set notices --partitions 3 -p money                         # every setting the installation's default
ankka projects topics set deltas --partitions 3 --cleanup compact --tombstone-window 1d --min-compaction-lag 1h -p casino
ankka projects topics set deltas --partitions 3 --compacted -p casino             # the same as --cleanup compact
ankka projects topics set transactions --partitions 12 --retention everything -p money
ankka projects topics set transactions --partitions 12 --copies 3 --min-in-sync 2 -p money
ankka projects topics set transactions --retention 30d -p money                   # asks before it removes (below)
ankka projects topics set transactions --retention 30d --removes "messages older than 30 days" -p money   # scripted
ankka projects topics list -p money
ankka projects history -p money
```

`--partitions` is required when the topic is new and optional when it is already declared.

## Refusals, before anything is written

| | Status | Text |
|---|---|---|
| partitions left out on a first declaration | 400 | `partitions is required for a new topic` |
| a value the parser does not read | 400 | `retention: "90 days" is not a duration (ms, s, m, h, d, or "everything")` |
| `compacted` and `cleanup` disagree | 400 | `compacted and cleanup disagree` |
| longer than the installation's longest | 400 | `retention time 2y is longer than the installation's longest, 1y` |
| larger than the installation's largest | 400 | `retention size 1TiB is larger than the installation's largest, 100GiB` |
| more copies than the installation's most | 400 | `copies 5 is more than the installation's most, 3` |
| minimum in-sync copies outside 1..copies | 400 | `minimum in-sync copies must be between 1 and the copies, 3` |
| copies or minimum in-sync copies changed | 409 | `copies and minimum in-sync copies are fixed when a topic is declared`; nothing else applied |
| fewer partitions | 409 | as today |
| a removal by a member who is not an owner | 403 | `owner role required: this declaration removes messages older than 30 days` |
| a removal without `removes`, or with another text | 400 | `this declaration removes messages older than 30 days; a declaration that removes messages says so with "removes"` |
| `removes` on a declaration that removes nothing | 400 | `this declaration removes nothing` |

The control plane never refuses for the broker's node count, which it does not know; a topic with
more copies than the broker has broker nodes is declared, and its row says `failed: topic
'money.transactions' asks for 5 copies and the broker has 3 broker nodes`.

## A change that removes messages

A shorter retention time, a smaller retention size, or a cleanup policy that loses `compact`
removes messages. The owner's acknowledgement is part of the request: the declaration names what it
removes, in the control plane's own words, and the control plane refuses one that does not. The
removal texts are:

- `messages older than <new retention>`
- `messages beyond <new size> on a partition`
- `every message but the last under each key`

(several joined with `; `). The CLI sends the declaration without `removes`, prints the 400's text,
asks `Remove them? [y/N]`, and resends with the text; with no terminal it refuses unless `--removes`
gave the text. The console shows the text in a dialog and resends on confirmation. A platform
administrator passes the owner check as on every route.

## The listing

```text
GET /projects/{id}/topics
[{"name": "transactions", "partitions": 12, "phase": "provisioned", "detail": null,
  "compacted": false, "contract": null, "checks": [],
  "settings": {"retention": "90d", "retentionSize": "50GiB", "cleanup": "delete", "tombstoneWindow": "1d",
               "minCompactionLag": "0s", "maxCompactionLag": "none", "copies": 3, "minInSync": 2,
               "defaulted": ["cleanup", "tombstoneWindow", "minCompactionLag", "maxCompactionLag", "copies", "minInSync"]},
  "copiesHeld": 3, "brokerNodes": 3}]
```

```text
TOPIC         PARTITIONS  RETENTION  SIZE    CLEANUP  COPIES  IN-SYNC  PHASE        DETAIL
transactions  12          90d        50GiB   delete*  3*      2*       provisioned
notices       3           7d*        none*   delete*  3*      2*       provisioned
events        3           7d*        none*   delete*  broker (1)  -    provisioned  copies are the broker's; below the installation's default of 3
```

`*` marks a value the installation supplied. `COPIES` shows `broker (N)` for a topic declared before
this feature, whose copies are what the broker holds. A one-node broker's row says `single copy`. The
first line of the listing names the broker: `broker: 3 nodes`. The contract and checks columns are
unchanged. `ankka projects topics list -o json` is the JSON above.

## The history

```text
GET /projects/{id}/history
[{"kind": "topic-changed", "topic": "transactions", "actor": {"subject": "...", "display": "Ada", "administrative": false},
  "at": "2026-10-08T10:00:00Z", "changes": [{"setting": "retention", "from": "90d", "to": "180d"}]},
 {"kind": "topic-filled", "topic": "notices", "actor": null, "at": "...", "changes": []},
 {"kind": "topic-declared", "topic": "transactions", "actor": {...}, "at": "...", "changes": []}]
```

```text
AT                    KIND            TOPIC         ACTOR         CHANGES
2026-10-08T10:00:00Z  topic-changed   transactions  Ada           retention 90d → 180d
2026-10-08T09:00:00Z  topic-filled    notices       the platform
```

Newest first, capped as a service's history is. A declaration that changes nothing adds no entry.

## Order of writes

As today: the schema document first (037), then the entity records the declaration, then the trigger
writes `AnkkaProject`. The owner check and the acknowledgement are checked before the schema write.
