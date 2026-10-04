/**
 * The console's look as values: the one source the stylesheet is generated from, the contrast test
 * reads, and the documentation's table of properties is written from. Each becomes a `--ac-*`
 * custom property a host may set; the names are a contract, the values are not.
 *
 * Text sits on translucent surfaces, so its contrast depends on what is behind it. The backdrop is
 * brightest where the glow peaks, and every ink must keep 4.5:1 on every surface there, in both
 * themes. The glow's alpha is therefore a limit: `test/contrast.test.ts` fails if it is raised past
 * what the inks can stand.
 */

export type Rgba = string;

export interface Theme {
  mesh: { base: Rgba; slate: Rgba; glow: Rgba; peach: Rgba; dot: Rgba };
  surface: {
    glass: Rgba;
    glassStrong: Rgba;
    glassLine: Rgba;
    tile: Rgba;
    tileLine: Rgba;
    node: Rgba;
    nodeLine: Rgba;
    inset: Rgba;
    edge: Rgba;
  };
  ink: { ink: Rgba; ink2: Rgba; ink3: Rgba };
  accent: { amber: Rgba; amberInk: Rgba; green: Rgba; red: Rgba; redWash: Rgba };
  shadow: string;
}

export interface Tokens {
  dark: Theme;
  light: Theme;
  radius: { panel: string; card: string; control: string; node: string };
  font: { sans: string; mono: string };
  size: { rail: string; panel: string; inspector: string; bar: string };
}

export const tokens: Tokens = {
  dark: {
    mesh: {
      base: "#111f26",
      slate: "#1e2e3a",
      glow: "rgba(212, 128, 88, 0.34)",
      peach: "rgba(222, 170, 132, 0.16)",
      dot: "rgba(255, 255, 255, 0.08)",
    },
    surface: {
      glass: "rgba(255, 255, 255, 0.05)",
      glassStrong: "rgba(255, 255, 255, 0.09)",
      glassLine: "rgba(255, 255, 255, 0.2)",
      tile: "rgba(255, 255, 255, 0.06)",
      tileLine: "rgba(255, 255, 255, 0.14)",
      node: "rgba(255, 255, 255, 0.1)",
      nodeLine: "rgba(255, 255, 255, 0.38)",
      inset: "rgba(0, 0, 0, 0.2)",
      edge: "rgba(255, 255, 255, 0.7)",
    },
    ink: { ink: "#f5f8f7", ink2: "#dfe8e6", ink3: "#ccd9d6" },
    accent: { amber: "#f0b83c", amberInk: "#13343b", green: "#8fe0ad", red: "#ffc4bc", redWash: "rgba(255, 138, 128, 0.16)" },
    shadow: "0 18px 50px rgba(5, 15, 18, 0.28)",
  },
  light: {
    mesh: {
      base: "#e6eeee",
      slate: "#cfe0dc",
      glow: "rgba(224, 165, 38, 0.30)",
      peach: "rgba(241, 213, 194, 0.80)",
      dot: "rgba(19, 52, 59, 0.10)",
    },
    surface: {
      glass: "rgba(255, 255, 255, 0.50)",
      glassStrong: "rgba(255, 255, 255, 0.85)",
      glassLine: "rgba(19, 52, 59, 0.14)",
      tile: "rgba(255, 255, 255, 0.50)",
      tileLine: "rgba(19, 52, 59, 0.10)",
      node: "rgba(255, 255, 255, 0.62)",
      nodeLine: "rgba(19, 52, 59, 0.22)",
      inset: "rgba(19, 52, 59, 0.06)",
      edge: "rgba(19, 52, 59, 0.55)",
    },
    ink: { ink: "#13343b", ink2: "#33504f", ink3: "#4a6465" },
    accent: { amber: "#e0a526", amberInk: "#13343b", green: "#1f6b3a", red: "#a8231b", redWash: "#fbe9e7" },
    shadow: "0 18px 50px rgba(19, 52, 59, 0.10)",
  },
  radius: { panel: "20px", card: "14px", control: "10px", node: "18px" },
  font: {
    sans: '"Inter Variable", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    mono: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  },
  size: { rail: "56px", panel: "232px", inspector: "300px", bar: "64px" },
};

/** Every colour property of a theme, by its CSS name without the `--ac-` prefix. */
export function colours(theme: Theme): [string, Rgba][] {
  return [
    ["color-mesh-base", theme.mesh.base],
    ["color-mesh-slate", theme.mesh.slate],
    ["color-glow", theme.mesh.glow],
    ["color-peach", theme.mesh.peach],
    ["color-dot", theme.mesh.dot],
    ["color-glass", theme.surface.glass],
    ["color-glass-strong", theme.surface.glassStrong],
    ["color-glass-line", theme.surface.glassLine],
    ["color-tile", theme.surface.tile],
    ["color-tile-line", theme.surface.tileLine],
    ["color-node", theme.surface.node],
    ["color-node-line", theme.surface.nodeLine],
    ["color-inset", theme.surface.inset],
    ["color-edge", theme.surface.edge],
    ["color-ink", theme.ink.ink],
    ["color-ink-2", theme.ink.ink2],
    ["color-ink-3", theme.ink.ink3],
    ["color-amber", theme.accent.amber],
    ["color-amber-ink", theme.accent.amberInk],
    ["color-green", theme.accent.green],
    ["color-red", theme.accent.red],
    ["color-red-wash", theme.accent.redWash],
    ["shadow-part", theme.shadow],
  ];
}

/** The properties that are the same in both themes. */
export function constants(t: Tokens): [string, string][] {
  return [
    ["radius-panel", t.radius.panel],
    ["radius-card", t.radius.card],
    ["radius-control", t.radius.control],
    ["radius-node", t.radius.node],
    ["font-sans", t.font.sans],
    ["font-mono", t.font.mono],
    ["size-rail", t.size.rail],
    ["size-panel", t.size.panel],
    ["size-inspector", t.size.inspector],
    ["size-bar", t.size.bar],
  ];
}

/**
 * The stylesheet's theme. The console is dark; its values are the theme itself. Light redefines the
 * same properties when a host chooses it, by putting `ac-light` on the shell. Browsers report a
 * preference for light when nobody has stated one, so the browser's preference is not followed.
 * Both sit on `:root` and inside the `theme` layer, so a host's own, unlayered declaration of any of
 * them wins in either theme, on any element.
 *
 * Tailwind prefixes the `@theme` names (`--color-ink` becomes `--ac-color-ink`); the light block is
 * plain CSS and names the prefixed property itself.
 */
export function toCss(t: Tokens): string {
  const decl = (pairs: [string, string][], prefix: string) => pairs.map(([k, v]) => `  ${prefix}${k}: ${v};`).join("\n");
  return [
    "/* Generated from src/ui/tokens.ts by scripts/build.ts. Do not edit. */",
    "@theme static {",
    "  --*: initial;",
    "  --spacing: 0.25rem;",
    decl(colours(t.dark), "--"),
    decl(constants(t), "--"),
    "}",
    "",
    "@layer theme {",
    "  :root:has(.ac-light) {",
    decl(colours(t.light), "    --ac-"),
    "  }",
    "}",
    "",
  ].join("\n");
}

/** What each property is for, by its name without the `--ac-` prefix: the documentation's table. */
export const roles: Record<string, string> = {
  "color-mesh-base": "The backdrop's darker colour, and what a surface is drawn over where blur is unavailable.",
  "color-mesh-slate": "The backdrop's lighter colour.",
  "color-glow": "The glow at the centre of the screen. Its alpha is the brightest the backdrop gets: the limit.",
  "color-peach": "The warmth in the lower corner.",
  "color-dot": "The dot grid across the backdrop.",
  "color-glass": "The rail, the bar, the listing and the inspector.",
  "color-glass-strong": "A button, and the item being read in the listing.",
  "color-glass-line": "The edge of the glass surfaces.",
  "color-tile": "A card on the page, and an item in the listing.",
  "color-tile-line": "A card's edge and the rules in its tables.",
  "color-node": "A part of a service's shape and of its topology.",
  "color-node-line": "A part's edge.",
  "color-inset": "Fields, logs, the section links and a part's inset row.",
  "color-edge": "The joins between parts.",
  "color-ink": "Text.",
  "color-ink-2": "Secondary text: labels, hints, counts.",
  "color-ink-3": "Section titles and placeholders.",
  "color-amber": "The accent: focus, the current area, toggles, the primary operation.",
  "color-amber-ink": "Text on the accent.",
  "color-green": "A ready lifecycle's mark.",
  "color-red": "Failure: a failed lifecycle, a destructive button, a field's error.",
  "color-red-wash": "Behind a refusal.",
  "shadow-part": "The shadow under a part.",
  "radius-panel": "The shell's surfaces.",
  "radius-card": "Cards.",
  "radius-control": "Fields and listing items.",
  "radius-node": "A part of the shape.",
  "font-sans": "The face; the package serves Inter itself.",
  "font-mono": "Logs, descriptors and secrets.",
  "size-rail": "The rail's width.",
  "size-panel": "The listing's width.",
  "size-inspector": "The inspector's width.",
  "size-bar": "The bar's height.",
};
