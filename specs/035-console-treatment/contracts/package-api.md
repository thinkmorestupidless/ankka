# Contract: the package's API after this feature

What changes for a host of `ankka-console`. Everything feature 017's `package-api.md` promised
still holds; this adds to it.

## Entry points

| Specifier | Adds |
|---|---|
| `ankka-console` (browser-safe) | `Shell`, `Backdrop`, `Bar`, `Rail`, `Listing`, `Inspector`, `Page`, `SectionTitle`, `SegmentedLinks`, `ServiceSections`, `Shape`, `Lifecycle`, `Breadcrumbs`, `Field`, `Submit`, `OperationForm`, `Switch`, `Select`, `button`; `useConsole().shell` |
| `ankka-console/server` | `consoleRoutes()` gains `projects/:projectId/services/:name/history` |
| `ankka-console/styles.css` | the compiled stylesheet; references `./fonts/*.woff2` relatively |
| `ankka-console/theme.css` | **new**: the `--ac-*` properties alone, for a host running its own Tailwind (`@import "ankka-console/theme.css"`) |
| `ankka-console/client`, `/testing` | unchanged |

## Mounting the shell

A host's layout composes the parts it has content for. The installation's console:

```tsx
<ConsoleProvider extensions={extensions}>
  <Shell>
    <Backdrop />
    <Rail />
    <Bar />
    <Listing />
    <Outlet />          {/* each page renders <Page inspector={…}>: its body and its inspector */}
  </Shell>
</ConsoleProvider>
```

The hosted product's website:

```tsx
<Shell>
  <Backdrop />
  <Bar><nav aria-label="Site">…</nav></Bar>
  <Outlet />
</Shell>
```

Rules:
- `Shell` is the console's root (`.ac-root`); `theme="light"` chooses the light theme. The console is
  dark otherwise; the browser's preference is not followed.
- `Backdrop` is the fixed mesh behind every surface.
- A part not rendered takes no space; the grid lays out what is present.
- `Bar` takes `wordmark` (replacing the console's), `children` (rendered beside it, for a host's own
  navigation) and `end` (replacing the signed-in person, for a host whose visitors may not be signed in).
- The package's pages render `<Page>` themselves; a host's own page goes inside `<Page>` too, which is
  its `main` landmark.

## Page data

`ConsolePageData` (every package page's `loaderData.console`) gains `shell: ShellData` (see
`data-model.md`). `useConsole()` exposes it as `shell`. A host's own page may supply a `console`
object with `shell` to drive the rail and bar; it is optional — `Bar` and `Rail` render with no
crumbs, no primary operation and no current area when absent.

## Styling contract

- Every colour, face, radius and space is a `--ac-*` custom property declared on `:root` by the
  stylesheet and read by every rule and utility through `var()`. A host overrides them on
  `.ac-root` or any ancestor of its own, as today. The list is `contracts/properties.md` and the
  generated block in `docs/reference/console-package.md`.
- Every package rule is in a cascade layer (`theme`, `base`, `components`, `utilities`). A host's
  unlayered rule wins over any of them regardless of specificity.
- Utility classes are prefixed `ac:`; component classes stay prefixed `ac-`. A host may rely on
  the `ac-` component classes named in the docs; `ac:` utilities are the package's own and may
  change.
- No preflight: the stylesheet resets nothing outside `.ac-root`.
- The stylesheet's `@font-face` references `./fonts/inter-latin-wght-normal.woff2` and
  `./fonts/inter-latin-ext-wght-normal.woff2`, shipped in `dist/fonts/`. A bundler that rebases
  CSS `url()` (Vite does) serves them from the host's origin with no configuration.

## Compatibility

- Pages' markup changes (the shell, the sections, the inspector). A host that styled package
  pages by its own selectors on `ac-` classes keeps the classes named in the docs; others may
  change. `ankka-cloud` styles its own pages only and overrides properties, so it is unaffected
  beyond mounting the bar.
- `Breadcrumbs` is still exported for a host's own pages but the package's pages no longer render
  it in the body; it is the bar's.
- The history table moves from the service's overview to `…/history`; a host linking to the
  service page still reaches everything.
