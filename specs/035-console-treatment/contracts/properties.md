# Contract: the `--ac-*` properties

Generated from `console/package/src/ui/tokens.ts`; this table is the contract's shape, the docs
block is its current values. Each colour exists in both themes; the light values apply under `<Shell theme="light">`.

| Property | Role | Contract |
|---|---|---|
| `--ac-color-mesh-base`, `--ac-color-mesh-slate` | the backdrop's gradient | with the glow, must keep every ink ≥ 4.5:1 on every surface at the glow's peak |
| `--ac-color-glow` | the ember glow at the screen's centre, as an rgba whose alpha is its peak | **the limit**: the brightest a host may set is the shipped alpha; raising it fails the package's test and the docs say so |
| `--ac-color-peach` | the lower-right warmth | same rule, lower weight |
| `--ac-color-dot` | the dot grid | decorative; no contrast rule |
| `--ac-color-glass`, `--ac-color-glass-strong`, `--ac-color-glass-line` | the shell's surfaces (rail, bar, panels) and their edges | every ink on `glass` ≥ 4.5:1 |
| `--ac-color-tile`, `--ac-color-tile-line` | cards, listings' tiles | every ink on `tile` ≥ 4.5:1 |
| `--ac-color-node`, `--ac-color-node-line`, `--ac-color-edge` | the shape's parts, their edges and the joins | `ink`, `ink-2` on `node` ≥ 4.5:1 |
| `--ac-color-inset` | the darker inset row in a part | `ink` on `inset` ≥ 4.5:1 |
| `--ac-color-ink`, `--ac-color-ink-2`, `--ac-color-ink-3` | text: primary, secondary, labels | — |
| `--ac-color-amber`, `--ac-color-amber-ink` | the accent and the ink on it | `amber-ink` on `amber` ≥ 4.5:1 |
| `--ac-color-green`, `--ac-color-red`, `--ac-color-red-wash` | lifecycle and failure | `red` on `glass` ≥ 4.5:1 |
| `--ac-shadow-part` | the parts' shadow | — |
| `--ac-radius-panel`, `--ac-radius-card`, `--ac-radius-control`, `--ac-radius-node` | 20 / 14 / 10 / 18 px | — |
| `--ac-font-sans`, `--ac-font-mono` | Inter Variable with its fallbacks; the system mono stack | a host replacing `sans` serves its own face from its own origin |
| `--ac-size-rail`, `--ac-size-panel`, `--ac-size-inspector`, `--ac-size-bar` | 56 / 232 / 300 / 64 px | — |

The shell's breakpoints (1180 and 720 px) are not properties: a media query cannot read a custom
property, so a host that set one would change nothing.

**As built** (T010): dark mesh `#111f26`/`#1e2e3a`, glow `rgba(212, 128, 88, 0.34)`, glass 0.05,
tile 0.06, node 0.10 white; lowest contrast 4.62:1 dark, 4.58:1 light. The current values are the
generated block in `docs/reference/console-package.md`.

The generated block in `docs/reference/console-package.md` lists each with its dark and light
value; `test/docs-properties.test.ts` holds the two to each other.
