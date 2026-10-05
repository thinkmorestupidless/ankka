/**
 * The installation console's topology rules against the same fixtures the local console's are run
 * against (`cli/src/test/js/topology.test.js`), compared the same way, so the two consoles cannot
 * come to disagree about a rule without one of the two suites failing.
 */
import { describe as group, test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as topology from "../src/ui/topology/layout.ts";
import type { Document, Options, Window } from "../src/ui/topology/layout.ts";

const dir = fileURLToPath(new URL("../fixtures/topology/", import.meta.url));
const files = readdirSync(dir).filter((f) => f.endsWith(".json")).sort();

interface Fixture {
  scenario?: string;
  about?: string;
  document: Document & { window: Window };
  options?: Options;
  services?: string[];
  now?: string;
  timeZone?: string;
  expected: Record<string, unknown>;
}

function observed(fixture: Fixture): Record<string, unknown> {
  const shown = topology.view(fixture.document, fixture.options);
  const outside = shown.nodes.filter((n) => n.kind.startsWith("External"));
  const key = (c: { from: string; to: string }) => `${c.from}>${c.to}`;
  return {
    shown: shown.nodes.map((n) => n.id),
    platform: shown.nodes.filter((n) => n.platform).map((n) => n.id),
    positions: Object.fromEntries(shown.nodes.map((n) => [n.id, [n.column, n.row]])),
    declared: shown.declared.map((e) => `${e.from}>${e.to}:${e.kind}`),
    calls: shown.calls.map(key),
    counts: Object.fromEntries(shown.calls.map((c) => [key(c), topology.totals(c.pairs)])),
    marks: Object.fromEntries(shown.calls.map((c) => [key(c), topology.edgeMark(c.pairs)])),
    weights: Object.fromEntries(shown.calls.map((c) => [key(c), topology.weight(c.pairs)])),
    observedLine: topology.observedLine(fixture.document.window, Date.parse(fixture.now ?? fixture.document.window.since), fixture.timeZone ?? "UTC"),
    through: Object.fromEntries(shown.nodes.filter((n) => n.through.length).map((n) => [n.id, n.through])),
    hiddenPlatform: shown.hiddenPlatform,
    links: Object.fromEntries(outside.map((n) => [n.id, topology.linkFor(n, fixture.services ?? [])])),
    labels: Object.fromEntries(shown.nodes.map((n) => [n.id, n.label])),
    describe: topology.describe(shown),
  };
}

group("the installation console's topology rules", () => {
  test("there are fixtures to run", () => {
    assert.ok(files.length > 0, `no fixtures in ${dir}`);
  });

  for (const file of files) {
    const fixture = JSON.parse(readFileSync(dir + file, "utf8")) as Fixture;
    test(`${file}: ${fixture.scenario ?? fixture.about}`, () => {
      const actual = observed(fixture);
      const keys = Object.keys(fixture.expected);
      assert.ok(keys.length > 0, "a fixture that expects nothing checks nothing");
      for (const k of keys) {
        assert.ok(k in actual, `'${k}' is not something a fixture can expect`);
        const expected = fixture.expected[k] as Record<string, unknown>;
        const compared =
          k === "labels" || k === "links"
            ? Object.fromEntries(Object.keys(expected).map((id) => [id, (actual[k] as Record<string, unknown>)[id]]))
            : actual[k];
        assert.deepEqual(compared, expected, `${file}: ${k}`);
      }
    });
  }

  const doc = JSON.parse(readFileSync(dir + "calls-weight.json", "utf8")).document as Document;

  test("the same document lays out identically twice", () => {
    assert.deepEqual(topology.view(doc), topology.view(structuredClone(doc)));
  });

  test("a refusal-only call has no warning; a failure or an unanswered call has one", () => {
    const pair = (handled: object, unanswered: object) => ({ caller: "a", callee: "b", handled, unanswered });
    assert.equal(topology.edgeMark([pair({ ok: 1, refused: 50 }, {})]), null);
    assert.equal(topology.edgeMark([pair({ failed: 1 }, {})]), "warning");
    assert.equal(topology.edgeMark([pair({}, { undelivered: 1 })]), "warning");
  });

  test("a socket route is shown among its endpoint's handlers, as the topology names it", () => {
    const d: Document = {
      nodes: [
        {
          id: "endpoint:/notices",
          kind: "Endpoint",
          layer: 0,
          platform: false,
          handlers: [
            { name: "GET /notices", type: "route", streaming: false },
            { name: "SOCKET /notices/stream", type: "route", streaming: true },
          ],
        },
      ],
      declared: [],
      calls: [],
    };
    const shown = topology.view(d);
    assert.deepEqual(shown.nodes[0]!.handlers.map((h) => h.name), ["GET /notices", "SOCKET /notices/stream"]);
  });

  test("focus keeps exactly the node and its neighbours", () => {
    const d: Document = {
      nodes: ["a", "b", "c", "d"].map((id, i) => ({ id, kind: "View", layer: i, platform: false, handlers: [] })),
      declared: [{ from: "a", to: "b", kind: "events" }, { from: "c", to: "d", kind: "events" }],
      calls: [{ from: "c", to: "b", pairs: [] }],
    };
    assert.deepEqual(topology.view(d, { focus: "b" }).nodes.map((n) => n.id), ["a", "b", "c"]);
  });
});
