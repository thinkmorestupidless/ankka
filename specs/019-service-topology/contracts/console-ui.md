# Contract: The Topology View in Both Consoles

The two consoles are separate implementations: the local console is hand-written JavaScript served
by the CLI, and the installation console is the `ankka-console` package. Both draw the same document
by the same rules. Anything that would otherwise differ between them is decided by the document
(`layer`, `platform`, node ids), not by the console.

## Drawing

- **Columns** by `layer`, left to right, and **rows** within a column by node id. The layout is
  deterministic: the same document always draws the same picture.
- **Nodes** carry a label with the id and a mark for the kind. External and unknown nodes are drawn
  outlined, not filled, and the unknown node is labelled *unknown caller*.
- **Declared edges** are solid lines. **Call edges** are dashed, and drawn wider as the handled
  count grows. The legend names both kinds.
- **Wherever call edges are visible** the view carries one line in this form: *"Calls observed in
  the last 10 minutes (since 09:12 when the service started). Calls not made in that time are not
  shown."*
- **A call edge with any `handled.failed` or `unanswered`** is marked with a warning. Refusals alone
  never mark an edge (FR-008).

## Interaction

| Action | Result |
|---|---|
| choose a node | side panel: kind, handlers, declared edges in and out, call pairs in and out with counts |
| choose a call edge | side panel: every pair, with handled (ok / refused / failed), unanswered (timed out / undelivered) and p50 / p99 / max |
| a streaming call | its pair is labelled *stream*; its duration is the stream's lifetime |
| focus | show only the chosen node and its direct neighbours; *show all* returns to the whole graph |
| filter by kind | toggles per kind |
| platform components | hidden by default; edges to a hidden node are drawn to the node that uses it, marked *via platform* |
| external service node | in the local console, a link to that service's topology when exactly one local service has that name; otherwise marked *not running here*. `service:(other)` is labelled *other services* and is never a link. |
| external component node (a declared source) | never a link: it names a component, not a service |

## One set of rules, two implementations

The rules that decide what is drawn — column placement, platform folding, focus, kind filters, the
warning mark, the observed line and which nodes are links — are pure functions in each console
(`cli/src/main/resources/console/topology.js` and `console/package/src/ui/topology/layout.ts`). Both
are tested against the same documents and expected results in `console/package/fixtures/topology/`,
so the two consoles cannot disagree about a rule without one suite failing.

## Updates

- **Local**: the Topology tab refreshes every 2 s while it is the open tab (the other tabs keep
  their 3 s tick), so a call is on screen within 3 s. Selection and focus survive a refresh.
- **Installation**: the topology page subscribes to `stream/services/:projectId/:name?topology`,
  which sends a `topology` event when the document changes on the existing 2 s tick.

## Installation-only

- **A header line**: *"2 of 3 instances"*, plus a *partial* badge that names each missing instance
  and its status.
- **Differences** (FR-024) are listed above the graph. The nodes they concern are drawn with a
  *not on every instance* mark.
- **Navigation**: the page is reached from the service page's actions, beside *Logs*.
- **Refusals**: the control plane's refusals are shown verbatim, the same as on every other page.

## Accessibility

- The graph is an SVG with `role="img"` and an `aria-label` summarising its counts.
- A text table of nodes and edges is always present beside it, so a screen reader and a test both
  read the same facts without parsing SVG.
- `accessibility.spec.ts` adds the page.
