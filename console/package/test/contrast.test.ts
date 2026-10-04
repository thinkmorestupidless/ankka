/**
 * FR-005, SC-004: text keeps its contrast at the glow's brightest point. The console's surfaces
 * are translucent, so a line of text has no single background; its worst case is a function of the
 * properties alone. This composites each surface the stylesheet stacks over the backdrop's brightest
 * and darkest points, in each theme, and requires WCAG AA's 4.5:1 for every ink used on it.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { tokens, type Theme, type Tokens } from "../src/ui/tokens.ts";

type Rgb = [number, number, number];
type Rgba = [number, number, number, number];

export function parse(colour: string): Rgba {
  const hex = colour.match(/^#([0-9a-f]{6})$/i);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1].slice(i, i + 2), 16)).concat(1) as Rgba;
  const rgba = colour.match(/^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+))?\s*\)$/);
  if (rgba) return [Number(rgba[1]), Number(rgba[2]), Number(rgba[3]), rgba[4] === undefined ? 1 : Number(rgba[4])];
  throw new Error(`not a colour this test reads: ${colour}`);
}

/** `top` drawn over an opaque `under`. */
export function over(under: Rgb, top: string): Rgb {
  const [r, g, b, a] = parse(top);
  return [r * a + under[0] * (1 - a), g * a + under[1] * (1 - a), b * a + under[2] * (1 - a)];
}

function luminance([r, g, b]: Rgb): number {
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

export function ratio(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const opaque = (c: string): Rgb => parse(c).slice(0, 3) as Rgb;

/**
 * The points of the backdrop a surface can sit over: its two mesh colours alone, and each with the
 * glow and the peach at their peaks. The worst of these is the worst case. The dot grid is left
 * out: a one-pixel dot is not the background a glyph is read against.
 */
function backdrops(theme: Theme): { name: string; rgb: Rgb }[] {
  const points: { name: string; rgb: Rgb }[] = [];
  for (const [mesh, colour] of [
    ["mesh-base", theme.mesh.base],
    ["mesh-slate", theme.mesh.slate],
  ] as const) {
    const base = opaque(colour);
    points.push({ name: mesh, rgb: base });
    points.push({ name: `${mesh} under the glow`, rgb: over(base, theme.mesh.glow) });
    points.push({ name: `${mesh} under the peach`, rgb: over(base, theme.mesh.peach) });
  }
  return points;
}

type Ink = keyof Theme["ink"];

/** Each surface the stylesheet draws text on, as the layers it stacks, and the inks it sets there. */
function stacks(theme: Theme): { name: string; layers: string[]; inks: Ink[] }[] {
  const s = theme.surface;
  return [
    { name: "glass (rail, bar, listing, inspector)", layers: [s.glass], inks: ["ink", "ink2", "ink3"] },
    { name: "a button on glass", layers: [s.glass, s.glassStrong], inks: ["ink"] },
    { name: "a listing item on glass", layers: [s.glass, s.tile], inks: ["ink", "ink2"] },
    { name: "the current listing item", layers: [s.glass, s.glassStrong], inks: ["ink", "ink2"] },
    { name: "a field on glass", layers: [s.glass, s.inset], inks: ["ink", "ink3"] },
    { name: "a card", layers: [s.tile], inks: ["ink", "ink2", "ink3"] },
    { name: "a field or log in a card", layers: [s.tile, s.inset], inks: ["ink", "ink2", "ink3"] },
    { name: "a part of the shape", layers: [s.node], inks: ["ink", "ink2"] },
    { name: "a part's inset row", layers: [s.node, s.inset], inks: ["ink"] },
    { name: "a section link on the page", layers: [s.inset], inks: ["ink", "ink2"] },
    { name: "the current section link", layers: [s.inset, s.glassStrong], inks: ["ink"] },
    { name: "a refusal", layers: [s.tile, theme.accent.redWash], inks: ["ink"] },
  ];
}

export interface Failure {
  theme: string;
  message: string;
}

/** Every pair below 4.5:1, worst first; empty when the properties pass. */
export function failures(t: Tokens): Failure[] {
  const out: (Failure & { r: number })[] = [];
  for (const [name, theme] of [
    ["dark", t.dark],
    ["light", t.light],
  ] as const) {
    for (const point of backdrops(theme)) {
      for (const stack of stacks(theme)) {
        const surface = stack.layers.reduce(over, point.rgb);
        for (const ink of stack.inks) {
          const r = ratio(opaque(theme.ink[ink]), surface);
          if (r < 4.5) {
            const prop = ink === "ink" ? "--ac-color-ink" : ink === "ink2" ? "--ac-color-ink-2" : "--ac-color-ink-3";
            out.push({ theme: name, r, message: `${name}: ${prop} on ${stack.name} over ${point.name} is ${r.toFixed(2)}:1 (check --ac-color-glow and the surface's alpha)` });
          }
        }
      }
    }
    // Text set on a solid accent.
    const pairs: [string, string, string][] = [
      ["--ac-color-amber-ink on --ac-color-amber", theme.accent.amberInk, theme.accent.amber],
      ["--ac-color-mesh-base on --ac-color-red (a destructive button)", theme.mesh.base, theme.accent.red],
    ];
    for (const [what, fg, bg] of pairs) {
      const r = ratio(opaque(fg), opaque(bg));
      if (r < 4.5) out.push({ theme: name, r, message: `${name}: ${what} is ${r.toFixed(2)}:1` });
    }
    // Red text: a failed lifecycle, a field's error, a panel that failed, on glass or a card.
    for (const point of backdrops(theme)) {
      for (const [where, layers] of [
        ["glass", [theme.surface.glass]],
        ["a card", [theme.surface.tile]],
      ] as const) {
        const r = ratio(opaque(theme.accent.red), layers.reduce(over, point.rgb));
        if (r < 4.5) out.push({ theme: name, r, message: `${name}: --ac-color-red on ${where} over ${point.name} is ${r.toFixed(2)}:1 (check --ac-color-glow and the surface's alpha)` });
      }
    }
  }
  return out.sort((a, b) => a.r - b.r).map(({ theme, message }) => ({ theme, message }));
}

/** The lowest ratio any ink reaches on any surface, per theme: printed so a change shows its margin. */
function lowest(t: Tokens, which: "dark" | "light"): number {
  const theme = t[which];
  let min = Infinity;
  for (const point of backdrops(theme))
    for (const stack of stacks(theme))
      for (const ink of stack.inks) min = Math.min(min, ratio(opaque(theme.ink[ink]), stack.layers.reduce(over, point.rgb)));
  return min;
}

test("every ink keeps 4.5:1 on every surface at the backdrop's brightest point, in both themes", () => {
  process.stdout.write(`contrast: lowest ${lowest(tokens, "dark").toFixed(2)}:1 dark, ${lowest(tokens, "light").toFixed(2)}:1 light\n`);
  const found = failures(tokens);
  assert.deepEqual(found.map((f) => f.message), []);
});

test("a glow brighter than the limit fails the build, naming the glow", () => {
  const [r, g, b, a] = parse(tokens.dark.mesh.glow);
  const brighter: Tokens = { ...tokens, dark: { ...tokens.dark, mesh: { ...tokens.dark.mesh, glow: `rgba(${r}, ${g}, ${b}, ${Math.min(1, a + 0.4)})` } } };
  const found = failures(brighter);
  assert.ok(found.length > 0, "raising the glow past its limit must fail");
  assert.match(found[0].message, /--ac-color-glow/);
});
