/**
 * FR-011: the console's documentation lists every property a host may set, from the same values
 * the stylesheet is generated from. The block between `generated:start console-properties` and its
 * end in docs/reference/console-package.md is this test's to write: it fails on a stale block and,
 * with CONSOLE_DOCS_UPDATE=1, rewrites it.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { colours, constants, roles, tokens } from "../src/ui/tokens.ts";

const page = new URL("../../../docs/reference/console-package.md", import.meta.url);
const open = "<!-- generated:start console-properties -->\n";
const close = "<!-- generated:end console-properties -->";

export function table(): string {
  const light = new Map(colours(tokens.light));
  const rows: string[] = ["| Property | What it is | Dark | Light |", "|---|---|---|---|"];
  const cell = (v: string) => "`" + v.replace(/\|/g, "\\|") + "`";
  for (const [name, dark] of colours(tokens.dark)) rows.push(`| \`--ac-${name}\` | ${roles[name] ?? ""} | ${cell(dark)} | ${cell(light.get(name)!)} |`);
  for (const [name, value] of constants(tokens)) rows.push(`| \`--ac-${name}\` | ${roles[name] ?? ""} | ${cell(value)} | the same |`);
  return rows.join("\n") + "\n";
}

test("every property is described", () => {
  const names = [...colours(tokens.dark), ...constants(tokens)].map(([n]) => n);
  assert.deepEqual(names.filter((n) => !roles[n]), [], "properties with no description in tokens.ts's roles");
  assert.deepEqual(Object.keys(roles).filter((n) => !names.includes(n)), [], "descriptions of properties that do not exist");
});

test("the documentation of the console says how a host restyles it", () => {
  const text = readFileSync(page, "utf8");
  const at = text.indexOf(open);
  const end = text.indexOf(close);
  assert.ok(at >= 0 && end > at, "docs/reference/console-package.md has no console-properties block");
  const expected = table();
  const actual = text.slice(at + open.length, end);
  if (actual !== expected && process.env.CONSOLE_DOCS_UPDATE === "1") {
    writeFileSync(page, text.slice(0, at + open.length) + expected + text.slice(end));
    return;
  }
  const want = expected.split("\n");
  const got = actual.split("\n");
  const first = want.findIndex((line, i) => line !== got[i]);
  assert.equal(first, -1, `the properties block is stale at its line ${first + 1}: run CONSOLE_DOCS_UPDATE=1 npm test -w package\n  want: ${want[first]}\n  have: ${got[first]}`);
  // The prose beside the table names the limit and why.
  const prose = text.slice(text.indexOf("## Restyle the pages"), at);
  assert.match(prose, /--ac-color-glow/);
  assert.match(prose, /4\.5:1/);
});
