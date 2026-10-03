/**
 * A topology as a picture: a column per layer, declared connections solid, observed calls dashed and
 * heavier the more they were made, a warning mark only for failed or unanswered calls. The picture
 * says what it holds in its label; the same facts are in the table beside it.
 */
import { describe, edgeMark, weight, type PlacedNode, type View } from "./layout.ts";

const GRID = { margin: 16, column: 210, row: 64, width: 170, height: 40 };

function clip(text: string, length: number): string {
  return text.length > length ? text.slice(0, length - 1) + "…" : text;
}

function kindInWords(kind: string): string {
  return kind.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
}

export function Graph({
  shown,
  differs,
  selected,
  onSelect,
}: {
  shown: View;
  /** Nodes not on every instance that answered. */
  differs: Set<string>;
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const columns = Math.max(0, ...shown.nodes.map((n) => n.column)) + 1;
  const rows = Math.max(0, ...shown.nodes.map((n) => n.row)) + 1;
  const width = GRID.margin * 2 + (columns - 1) * GRID.column + GRID.width;
  const height = GRID.margin * 2 + (rows - 1) * GRID.row + GRID.height;
  const at = new Map(shown.nodes.map((n) => [n.id, { x: GRID.margin + n.column * GRID.column, y: GRID.margin + n.row * GRID.row }]));

  const path = (from: string, to: string, below: number) => {
    const a = at.get(from);
    const b = at.get(to);
    if (!a || !b || from === to) return null;
    const forwards = b.x >= a.x;
    const x1 = forwards ? a.x + GRID.width : a.x;
    const x2 = forwards ? b.x : b.x + GRID.width;
    const y1 = a.y + GRID.height / 2 + below;
    const y2 = b.y + GRID.height / 2 + below;
    const bend = (x2 - x1) / 2;
    return { d: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`, mx: (x1 + x2) / 2, my: (y1 + y2) / 2 };
  };

  const node = (n: PlacedNode) => {
    const { x, y } = at.get(n.id)!;
    const outside = n.kind.startsWith("External") || n.kind === "UnknownCaller";
    const classes = ["ac-topology-node", outside ? "ac-topology-outside" : "", n.platform ? "ac-topology-platform" : "", differs.has(n.id) ? "ac-topology-differs" : "", n.id === selected ? "ac-topology-selected" : ""]
      .filter(Boolean)
      .join(" ");
    const kind = kindInWords(n.kind) + (n.platform ? ", platform" : "") + (differs.has(n.id) ? ", not on every instance" : "");
    return (
      <g
        key={n.id}
        className={classes}
        transform={`translate(${x} ${y})`}
        tabIndex={0}
        role="button"
        aria-label={`${n.label}: ${kind}`}
        onClick={() => onSelect(n.id)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") onSelect(n.id);
        }}
      >
        <rect width={GRID.width} height={GRID.height} rx={6} />
        <text x={10} y={16} className="ac-topology-name">
          {clip(n.label, 22)}
        </text>
        <text x={10} y={30} className="ac-topology-kind">
          {clip(kind, 28)}
        </text>
      </g>
    );
  };

  return (
    <svg className="ac-topology-graph" viewBox={`0 0 ${width} ${height}`} width={width} height={height} role="group" aria-label={describe(shown)}>
      {shown.declared.map((e) => {
        const p = path(e.from, e.to, 0);
        return p ? (
          <path key={`d:${e.from}>${e.to}:${e.kind}`} className="ac-topology-declared" d={p.d}>
            <title>{`${e.from} to ${e.to}: ${e.kind}`}</title>
          </path>
        ) : null;
      })}
      {shown.calls.map((c) => {
        const p = path(c.from, c.to, 7);
        if (!p) return null;
        const warning = edgeMark(c.pairs) === "warning";
        return (
          <g key={`c:${c.from}>${c.to}`}>
            <path className={`ac-topology-call${warning ? " ac-topology-warning" : ""}`} d={p.d} style={{ strokeWidth: `${0.75 + weight(c.pairs) * 0.75}px` }}>
              <title>{`${c.from} calls ${c.to}`}</title>
            </path>
            {warning ? (
              <text className="ac-topology-mark" x={p.mx - 3} y={p.my - 4}>
                !
              </text>
            ) : null}
          </g>
        );
      })}
      {shown.nodes.map(node)}
    </svg>
  );
}
