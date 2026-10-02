# Where these fixtures come from

The graph delta contract, `ankka.graph-delta.v1`, belongs to
[ankka-flow](https://github.com/thinkmorestupidless/ankka-flow), whose merge sink reads what a
graph consumer here publishes. ankka depends on nothing of ankka-flow's; these files are how the
two are shown to agree.

| File | Whose | What a row is |
|---|---|---|
| `keys.json` | ankka-flow's, copied byte for byte from `protocol/fixtures/graph-deltas/keys.json` | `{"delta", "key"}`: a delta and the record key it must have |
| `deltas.json` | ankka-flow's, copied byte for byte from `protocol/fixtures/graph-deltas/deltas.json` | `{"delta", "key", "reads"}`: a delta, its key, and the kind the sink reads each property as |
| `refused.json` | ankka's | `{"name", "element", "why"}`: an element every SDK's builder must refuse, and the reason's name. `"elements"` in place of `"element"` for a result refused as a whole; `"sequence"` is the change's sequence number when it is not 1 |

The copies were taken from ankka-flow at commit `9905de1bfd49bf8664bb12410a67262a31a5cc0d`
(branch `graph-delta-fixtures`, ahead of its release).

## How they are used

Every SDK builds the element each row of `keys.json` and `deltas.json` describes through its own
builder and checks the record's key against `key` and the value, read back, against `delta`. The
sink's own suite in ankka-flow reads the same rows, so a row here is one the reader is proven to
accept. Equality is the reader's: `2.0` and `2` are the same property value, and `labels` and
`properties` that are absent equal ones that are empty.

Every SDK builds each row of `refused.json` and expects a refusal for the reason `why` names (the
reasons are listed in `contracts/graph-builder.md` of the feature that added them and on the
*Publish a graph* page). A row a language's types cannot express — a version that is not a whole
number, where a version is a 64-bit integer by type — counts as refused there. A float that is
not finite cannot be written in JSON and is tested in each SDK's own suite.

## Keeping them equal

No build reaches into the other repository. When ankka-flow changes either of its files, copy
them again, update the commit above, and run each SDK's copy script
(`sdks/python/scripts/proto.py`, `npm run proto` in `sdks/typescript`, `sdks/rust/scripts/proto.sh`).

This directory is not `EncodingFixturesSuite`'s: that suite owns the JSON files directly under
`protocol/fixtures/` and nothing beneath it.
