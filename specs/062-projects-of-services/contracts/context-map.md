# Contract: the context map file and its checks

## The file

`context-map.json` at the root of a project. Decoded strictly by `core`'s `ContextMap` codec: an
unknown field is "the context map names a field the platform does not know: `<field>`".

```json
{
  "project": "shop",
  "services": [
    {"name": "orders", "owns": "orders and their lines",
     "boundary": {"criterion": "ownership", "why": "the orders team releases on its own cadence"}},
    {"name": "billing", "owns": "invoices and payments",
     "boundary": {"criterion": "ownership", "why": "finance owns invoicing"}}
  ],
  "crossings": [
    {"from": "orders", "to": "billing", "topic": "orders-placed",
     "contract": "order.v1", "schema": "contracts/order.v1.json"},
    {"from": "orders", "to": "billing", "route": "POST /invoices"}
  ]
}
```

The template writes this map from the names given to `--services`, whatever they are: the first
service publishes to the declared topic `<first>-events` carrying the contract `<first>.v1`, whose
schema is `contracts/<first>.v1.json`, and the second reads it; the first also calls
`GET /<second>/{id}` of the second. `owns` and `why` are filled for the sample. `ankka init
<service>` inside a project appends `{"name": "<service>", "owns": "", "boundary": {"criterion":
"ownership", "why": ""}}` and no crossing, and says so.

## `ankka context-map check [--dir <path>]`

Reads `<dir>/context-map.json` (default `.`), the `services/` directory, `contracts/` and
`CONTEXT-MAP.md`. Exit 0 with no output on success. Otherwise exit 1 and one line per problem,
in this order and with these words (the suites assert them):

| Problem | Line |
|---|---|
| no file | `<dir> holds no project started from the project template (no context-map.json)` |
| not JSON, or unknown field | `context-map.json: <jsoniter message>` / `the context map names a field the platform does not know: "<field>"` |
| invalid project id | `the context map's project "<p>" is not a valid project name: <rule>` |
| a service with no directory | `the context map names "<name>", and services/<name>/service.json does not exist` |
| a directory not in the map | `<project> has a service "<name>" (services/<name>/service.json) that the context map does not name` |
| a descriptor whose name differs from its directory | `services/<dir>/service.json is named "<name>", not "<dir>"` |
| an unresolved `from` or `to` | `the crossing from "<from>" to "<to>" names "<x>", which is not a service of <project>; another project's service is written "<project>/<service>"` |
| a crossing of neither shape | `the crossing from "<from>" to "<to>" is neither a declared topic with a contract and a schema nor a route` |
| both shapes | `the crossing from "<from>" to "<to>" is both a topic and a route; a crossing is one or the other` |
| missing schema | `the crossing "<topic>" names the schema <path>, which does not exist` |
| schema not JSON | `the schema <path> is not a JSON document: <message>` |
| invalid contract name | `the contract "<c>" is not a valid contract name: <rule>` |
| invalid topic name | `the topic "<t>" is not a valid topic name: <rule>` |
| invalid route | `the route "<r>" is not "<METHOD> <path>"` |
| two equal crossings | `the crossing from "<from>" to "<to>" by "<topic or route>" is recorded twice` |
| rendering out of step | `CONTEXT-MAP.md is out of step with context-map.json; run ankka context-map render` |
| no rendering | `CONTEXT-MAP.md does not exist; run ankka context-map render` |

The scenario steps map to these: "they say that `shop` has no service `shipping`" is the first
service line with `shipping`; "the context map does not name `billing`" is the directory line.

## `ankka context-map render [--dir <path>]`

Reads the file; writes `<dir>/CONTEXT-MAP.md`; exit 0 and prints the path. Exit 1 with the parse
problems above when the file is not readable. The rendering, deterministic:

```markdown
# shop

*Rendered from `context-map.json` by `ankka context-map render`; edit the file, not this page.*

## Services

| Service | Owns | Boundary | Why |
|---|---|---|---|
| orders | orders and their lines | ownership | the orders team releases on its own cadence |
| billing | invoices and payments | ownership | finance owns invoicing |

## Crossings

| From | To | What passes | Contract or route |
|---|---|---|---|
| orders | billing | the declared topic `orders-placed` | `order.v1` (`contracts/order.v1.json`) |
| orders | billing | a call | `POST /invoices` |
```

Services in the file's order; crossings in the file's order. Empty `owns` or `why` render as an
empty cell.

## `core.ContextMap` (Scala)

```scala
final case class ContextMap(project: String, services: Vector[ContextMap.Service], crossings: Vector[ContextMap.Crossing])
object ContextMap:
  def read(root: Path): Either[Vector[String], ContextMap]        // parse problems
  def check(root: Path, map: ContextMap): Vector[String]           // the static problems, in the table's order
  def render(map: ContextMap): String                              // CONTEXT-MAP.md
  def topicCrossings(map: ContextMap, service: String): (published: Vector[Crossing], read: Vector[Crossing])
```

No dependency outside `core`'s own (jsoniter, the descriptor name rules it already holds). The
rules for project, service, topic and contract names are the ones `controlplane-api` applies; the
ones `core` does not have yet move into `core` so that both apply one definition.

## `testkit.ContextMapCheck` (Scala)

```scala
object ContextMapCheck:
  /** The problems between what `kit`'s service states and the map's crossings naming `service`. */
  def problems(map: ContextMap, service: String, kit: AnkkaTestKit): Vector[String]
```

Lines: `"<service>" publishes to "<topic>" as "<contract>", and the context map records no
crossing from "<service>" by "<topic>"`; `"<service>" reads "<topic>", and the context map records
no crossing to "<service>" by "<topic>"`; `"<service>" publishes to "<topic>" as "<stated>"; the
context map says "<contract>"`; `the context map records a crossing from "<service>" by "<topic>",
and no component of "<service>" publishes to it` (and the reading side likewise). The generated
`ContextMapSuite` asserts `problems` is empty for each Scala service. A crossing with another
project's service (`<project>/<service>`) records its topic by the bare name, and is stated by a
component naming either that name or `<project>/<name>`, the address a component gives another
project's topic: the topic may be this project's or theirs.
