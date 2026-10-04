/**
 * The topology as text: every fact the picture shows, in tables a screen reader and a search can
 * read. Handled and unanswered are separate columns, because they are separate facts.
 */
import type { Pair, View } from "./layout.ts";

function nameOf(shown: View, id: string): string {
  return shown.nodes.find((n) => n.id === id)?.label ?? id;
}

function kindInWords(kind: string): string {
  return kind.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
}

export function duration(pair: Pair): string {
  const d = pair.durationMillis;
  return d ? `~${d.p50} / ~${d.p99} / ~${d.max} ms` : "";
}

export function Table({ shown, differs }: { shown: View; differs: Map<string, string[]> }) {
  return (
    <>
      <div className="ac-table-wrap">
        <table className="ac-table" aria-label="Components">
          <caption className="ac-visually-hidden">Components</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Kind</th>
              <th scope="col">Handlers</th>
            </tr>
          </thead>
          <tbody>
            {shown.nodes.map((n) => (
              <tr key={n.id} data-node={n.id}>
                <td>{n.label}</td>
                <td>
                  {kindInWords(n.kind)}
                  {n.platform ? ", platform" : ""}
                  {differs.has(n.id) ? `, only on ${differs.get(n.id)!.join(", ")}` : ""}
                </td>
                <td>{n.handlers.map((h) => h.name).join(", ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {shown.declared.length > 0 ? (
        <div className="ac-table-wrap">
          <table className="ac-table" aria-label="Declared connections">
            <caption className="ac-visually-hidden">Declared connections</caption>
            <thead>
              <tr>
                <th scope="col">From</th>
                <th scope="col">To</th>
                <th scope="col">As</th>
              </tr>
            </thead>
            <tbody>
              {shown.declared.map((e) => (
                <tr key={`${e.from}>${e.to}:${e.kind}`}>
                  <td>{nameOf(shown, e.from)}</td>
                  <td>{nameOf(shown, e.to)}</td>
                  <td>{e.kind}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {shown.calls.length > 0 ? (
        <div className="ac-table-wrap">
          <table className="ac-table" aria-label="Observed calls">
            <caption className="ac-visually-hidden">Observed calls</caption>
            <thead>
              <tr>
                <th scope="col">From</th>
                <th scope="col">To</th>
                <th scope="col">Handler to handler</th>
                <th scope="col" className="ac-num">Ok</th>
                <th scope="col" className="ac-num">Refused</th>
                <th scope="col" className="ac-num">Failed</th>
                <th scope="col" className="ac-num">Timed out</th>
                <th scope="col" className="ac-num">Undelivered</th>
                <th scope="col">p50 / p99 / max</th>
              </tr>
            </thead>
            <tbody>
              {shown.calls.flatMap((c) =>
                c.pairs.map((p) => (
                  <tr key={`${c.from}>${c.to}:${p.caller}>${p.callee}`} data-pair={`${p.caller} -> ${p.callee}`}>
                    <td>{nameOf(shown, c.from)}</td>
                    <td>{nameOf(shown, c.to)}</td>
                    <td>
                      {p.caller} → {p.callee}
                      {p.streaming ? " (stream)" : ""}
                    </td>
                    <td className="ac-num">{p.handled?.ok ?? 0}</td>
                    <td className="ac-num">{p.handled?.refused ?? 0}</td>
                    <td className="ac-num">{p.handled?.failed ?? 0}</td>
                    <td className="ac-num">{p.unanswered?.timedOut ?? 0}</td>
                    <td className="ac-num">{p.unanswered?.undelivered ?? 0}</td>
                    <td>{duration(p)}</td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}
