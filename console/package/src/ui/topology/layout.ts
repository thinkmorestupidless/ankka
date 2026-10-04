/**
 * The rules for reading a topology: what is shown, what is left out, and where each thing goes.
 *
 * Pure functions, document in and plain data out. The local console applies the same rules
 * (`cli/src/main/resources/console/topology.js`), and both are tested against every fixture in
 * `console/package/fixtures/topology/`, so the two consoles cannot disagree about a rule without one
 * suite failing. Browser-safe: nothing here imports a Node module or the server entry point.
 */

export interface Handled {
  ok?: number;
  refused?: number;
  failed?: number;
}

export interface Unanswered {
  timedOut?: number;
  undelivered?: number;
}

export interface Pair {
  caller: string;
  callee: string;
  handled?: Handled;
  unanswered?: Unanswered;
  durationMillis?: { p50: number; p99: number; max: number; bucketed?: boolean };
  streaming?: boolean;
}

export interface Node {
  id: string;
  kind: string;
  layer: number;
  platform: boolean;
  handlers: { name: string; type: string; streaming?: boolean | null }[];
}

export interface Declared {
  from: string;
  to: string;
  kind: string;
}

export interface Call {
  from: string;
  to: string;
  pairs: Pair[];
}

export interface Window {
  seconds: number;
  since: string;
}

export interface Document {
  nodes?: Node[];
  declared?: Declared[];
  calls?: Call[];
  window?: Window;
}

export interface Through {
  component: string;
  relation: "reads" | "read-by" | "calls" | "called-by";
  kind?: string;
  handled?: number;
  unanswered?: number;
}

export interface PlacedNode extends Node {
  through: Through[];
  column: number;
  row: number;
  label: string;
}

export interface View {
  nodes: PlacedNode[];
  declared: Declared[];
  calls: Call[];
  hiddenPlatform: number;
}

export interface Options {
  showPlatform?: boolean;
  kinds?: string[] | null;
  focus?: string | null;
}

/** The handled and the unanswered calls, each added up: two numbers, never one. */
export function totals(pairs: Pair[] | undefined): { handled: number; unanswered: number } {
  const sum = { handled: 0, unanswered: 0 };
  for (const pair of pairs ?? []) {
    const h = pair.handled ?? {};
    const u = pair.unanswered ?? {};
    sum.handled += (h.ok ?? 0) + (h.refused ?? 0) + (h.failed ?? 0);
    sum.unanswered += (u.timedOut ?? 0) + (u.undelivered ?? 0);
  }
  return sum;
}

/** A failure or a call nothing answered marks a call; refusals alone never do. */
export function edgeMark(pairs: Pair[] | undefined): "warning" | null {
  for (const pair of pairs ?? []) {
    const h = pair.handled ?? {};
    const u = pair.unanswered ?? {};
    if ((h.failed ?? 0) > 0) return "warning";
    if ((u.timedOut ?? 0) + (u.undelivered ?? 0) > 0) return "warning";
  }
  return null;
}

/** From 1 to 5: one step for each power of ten of the handled calls. */
export function weight(pairs: Pair[] | undefined): number {
  const handled = totals(pairs).handled;
  return 1 + Math.min(4, Math.floor(Math.log10(Math.max(1, handled))));
}

/** The sentence wherever observed calls are shown: how far back they reach. */
export function observedLine(window: Window | undefined, now: number, timeZone?: string): string {
  const seconds = window?.seconds ?? 0;
  const since = window ? Date.parse(window.since) : NaN;
  const sinceStart = Number.isFinite(since) && since > now - seconds * 1000;
  const from = sinceStart ? ` (since ${clock(since, timeZone)} when the service started)` : "";
  return `Calls observed in the last ${inWords(seconds)}${from}. Calls not made in that time are not shown.`;
}

function inWords(seconds: number): string {
  const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  if (seconds >= 3600 && seconds % 3600 === 0) return count(seconds / 3600, "hour", "hours");
  if (seconds >= 60 && seconds % 60 === 0) return count(seconds / 60, "minute", "minutes");
  return count(seconds, "second", "seconds");
}

function clock(millis: number, timeZone?: string): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone }).format(new Date(millis));
}

/** What a node is called on the page. */
export function label(node: Pick<Node, "id" | "kind">): string {
  if (node.kind === "UnknownCaller") return "unknown caller";
  if (node.id === "service:(other)") return "other services";
  if (node.kind === "ExternalService") return node.id.slice("service:".length).split("/").pop() ?? node.id;
  if (node.kind === "Topic") return node.id.slice("topic:".length);
  if (node.kind === "ExternalComponent") return node.id.slice("external:".length);
  if (node.kind === "Endpoint") return node.id.slice("endpoint:".length);
  return node.id;
}

/**
 * Which local service a node opens. In the installation's console no service is local, so `services`
 * is empty and an external service is never a link; the rule is shared so the fixtures hold both.
 */
export function linkFor(node: Pick<Node, "id" | "kind">, services: string[]): { service: string | null; note: string | null } {
  if (node.kind !== "ExternalService") return { service: null, note: null };
  if (node.id === "service:(other)") return { service: null, note: "other services" };
  const name = label(node);
  const matches = services.filter((s) => s === name);
  return matches.length === 1 ? { service: name, note: null } : { service: null, note: "not running here" };
}

function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function edgeOrder(a: { from: string; to: string; kind?: string }, b: { from: string; to: string; kind?: string }): number {
  return compare(a.from, b.from) || compare(a.to, b.to) || compare(a.kind ?? "", b.kind ?? "");
}

function throughOrder(a: Through, b: Through): number {
  return compare(a.component, b.component) || compare(a.relation, b.relation);
}

type Withthrough = Node & { through: Through[] };

function fold(nodes: Withthrough[], declared: Declared[], calls: Call[]) {
  const hidden = new Set(nodes.filter((n) => n.platform).map((n) => n.id));
  const through = new Map<string, Through[]>();
  const note = (visible: string, entry: Through) => {
    if (!through.has(visible)) through.set(visible, []);
    through.get(visible)!.push(entry);
  };
  for (const edge of declared) {
    if (hidden.has(edge.from) && !hidden.has(edge.to)) note(edge.to, { component: edge.from, relation: "reads", kind: edge.kind });
    else if (!hidden.has(edge.from) && hidden.has(edge.to)) note(edge.from, { component: edge.to, relation: "read-by", kind: edge.kind });
  }
  for (const call of calls) {
    const counts = totals(call.pairs);
    if (!hidden.has(call.from) && hidden.has(call.to)) note(call.from, { component: call.to, relation: "calls", ...counts });
    else if (hidden.has(call.from) && !hidden.has(call.to)) note(call.to, { component: call.from, relation: "called-by", ...counts });
  }
  const visible = (e: { from: string; to: string }) => !hidden.has(e.from) && !hidden.has(e.to);
  return {
    nodes: nodes.filter((n) => !hidden.has(n.id)).map((n) => ({ ...n, through: [...(through.get(n.id) ?? [])].sort(throughOrder) })),
    declared: declared.filter(visible),
    calls: calls.filter(visible),
    hidden: hidden.size,
  };
}

function within(nodes: Withthrough[], declared: Declared[], calls: Call[]) {
  const ids = new Set(nodes.map((n) => n.id));
  const both = (e: { from: string; to: string }) => ids.has(e.from) && ids.has(e.to);
  return { nodes, declared: declared.filter(both), calls: calls.filter(both) };
}

function place(nodes: Withthrough[]): (Withthrough & { column: number; row: number })[] {
  const layers = [...new Set(nodes.map((n) => n.layer))].sort((a, b) => a - b);
  const rows = new Map<number, number>();
  return [...nodes]
    .sort((a, b) => a.layer - b.layer || compare(a.id, b.id))
    .map((node) => {
      const column = layers.indexOf(node.layer);
      const row = rows.get(column) ?? 0;
      rows.set(column, row + 1);
      return { ...node, column, row };
    });
}

/**
 * What to draw, given what the reader asked to see: platform folding first, then kinds, then focus,
 * so focusing never brings back what the reader left out.
 */
export function view(doc: Document, options?: Options): View {
  const asked = { showPlatform: false, kinds: null as string[] | null, focus: null as string | null, ...(options ?? {}) };
  const all: Withthrough[] = (doc.nodes ?? []).map((n) => ({ ...n, through: [] }));
  const folded = asked.showPlatform
    ? { nodes: all, declared: doc.declared ?? [], calls: doc.calls ?? [], hidden: 0 }
    : fold(all, doc.declared ?? [], doc.calls ?? []);
  const kinds = asked.kinds ? within(folded.nodes.filter((n) => asked.kinds!.includes(n.kind)), folded.declared, folded.calls) : folded;
  let focused: { nodes: Withthrough[]; declared: Declared[]; calls: Call[] } = kinds;
  if (asked.focus && kinds.nodes.some((n) => n.id === asked.focus)) {
    const near = new Set([asked.focus]);
    for (const e of [...kinds.declared, ...kinds.calls]) {
      if (e.from === asked.focus) near.add(e.to);
      if (e.to === asked.focus) near.add(e.from);
    }
    focused = within(kinds.nodes.filter((n) => near.has(n.id)), kinds.declared, kinds.calls);
  }
  return {
    nodes: place(focused.nodes).map((n) => ({ ...n, label: label(n) })),
    declared: [...focused.declared].sort(edgeOrder),
    calls: [...focused.calls].sort(edgeOrder),
    hiddenPlatform: folded.hidden,
  };
}

/** What a picture says, for a reader who cannot see it. */
export function describe(shown: View): string {
  const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  return [
    count(shown.nodes.length, "component", "components"),
    count(shown.declared.length, "declared connection", "declared connections"),
    count(shown.calls.length, "observed call", "observed calls"),
  ].join(", ");
}
