# Where these fixtures come from

The graph delta contract, `ankka.graph-delta.v1`, is ankka's: a graph consumer here publishes
deltas, and the graph sink (`ankka-graph-sink`, over a store interface; a store over Neo4j and an
image of the sink into it are ankka-contrib's) reads them into a store. These files are how the two sides, and every SDK's builder, are shown
to agree.

| File | Whose | What a row is |
|---|---|---|
| `keys.json` | written by `GraphFixturesSuite` in `modules/core` | `{"delta", "key"}`: a delta and the record key it must have |
| `deltas.json` | written by `GraphFixturesSuite` in `modules/core` | `{"delta", "key", "reads"}`: a delta, its key, and the kind the sink reads each property as |
| `refused.json` | authored by hand | `{"name", "element", "why"}`: an element every SDK's builder must refuse, and the reason's name. `"elements"` in place of `"element"` for a result refused as a whole; `"sequence"` is the change's sequence number when it is not 1 |

## How they are used

Every SDK builds the element each row of `keys.json` and `deltas.json` describes through its own
builder and checks the record's key against `key` and the value, read back, against `delta`. The
sink's suite (`GraphSinkSuite` in `modules/graph-sink`) reads the same rows into the reference
store and reads back what `reads` says, so a row here is one the sink is proven to accept;
ankka-contrib's Neo4j store proves the same against a copy of these files. Equality is the
reader's: `2.0` and `2` are the same property value, and `labels` and `properties` that are
absent equal ones that are empty.

Every SDK builds each row of `refused.json` and expects a refusal for the reason `why` names (the
reasons are on the *Publish a graph* page). A row a language's types cannot express — a version
that is not a whole number, where a version is a 64-bit integer by type — counts as refused there.
A float that is not finite cannot be written in JSON and is tested in each SDK's own suite.

## Keeping them equal

`GraphFixturesSuite` refuses a row whose key or delta is not what the core builder writes for its
element; `sbt -Dankka.fixtures.regenerate=on 'core/testOnly *GraphFixturesSuite'` rewrites both
files from their own elements through the builder. After a change, run each SDK's copy script
(`sdks/python/scripts/proto.py`, `npm run proto` in `sdks/typescript`, `sdks/rust/scripts/proto.sh`),
which CI holds to the originals.

This directory is not `EncodingFixturesSuite`'s: that suite owns the JSON files directly under
`protocol/fixtures/` and nothing beneath it.
