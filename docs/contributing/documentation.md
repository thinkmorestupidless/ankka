---
title: Writing documentation
description: What is particular to ankka's own documentation beyond the rules every page follows, from its facets and sample trees to its generated blocks, checked descriptors and rendered skills.
kind: contributing
related: [reference/documentation-rules.md, reference/glossary.md]
---

# Writing documentation

ankka's documentation is one tree of plain Markdown files under `docs/`, checked and rendered by the
documentation tool in `tools/docs`. Every rule a page follows, the commands and every setting of
`extra.docs` are [Documentation rules](../reference/documentation-rules.md), which holds for every
repository the tool documents; this page says what is particular to ankka.

## The build

```bash
uv run --project tools/docs docs check    # every rule; exits 1 on a problem
uv run --project tools/docs docs sync     # refresh included samples, generated tables and the skills
uv run --project tools/docs docs build    # check, build the site, write the machine renderings
uv run --project tools/docs pytest tools/docs   # the tool's own tests, against repositories that are not ankka
```

`just docs`, `just docs-sync` and `just docs-serve` are the same commands. The site lands in
`target/docs-site`. ankka is the tool's own repository, so `tools/docs/pyproject.toml` is the tool's
project and not a set-up's build settings, and the site is published by the release workflow after
Maven Central rather than by a documentation job: `mkdocs.yml` says `kept: {settings: false, job: false}`.
The documentation workflow every set-up repository calls, `.github/workflows/documentation.yml`, lives
here, and its release is the tool's.

A change to a rule is a change for every repository that uses the tool. The tool's tests run it
against repositories that are not ankka, and anything ankka-specific belongs in `extra.docs`, never in
the tool's code.

## The facets

ankka's pages carry two facets beyond the required frontmatter:

| Field | Meaning |
|---|---|
| `languages` | Any of `scala`, `java`, `kotlin`, `python`, `typescript`, `rust`, `go`: every language a reader of the page may be writing in, which is every language that can do what the page shows. Leave it out for a page that is not about writing code. |
| `components` | The component kinds the page is about: `event-sourced-entity`, `key-value-entity`, `view`, `consumer`, `workflow`, `timed-action`, `agent`, `autonomous-agent`, `http-endpoint`, `grpc-endpoint`, `mcp-endpoint`. |

Tabs follow the order `mkdocs.yml` lists the languages in: Scala, Java, Kotlin, Python, TypeScript,
Rust, Go. A language left out of a page names a limitation that
[Limitations](../reference/limitations.md) states.

## Where samples come from

Every language has a tested example tree to mark regions in, so a tab set's seven tabs come from seven
tested sources: `samples/` and the modules' tests for Scala, `samples/shopping-cart-java` and the Java
SDK's tests for Java, `sdks/kotlin` for Kotlin, and `sdks/python`, `sdks/typescript`, `sdks/rust` and
`sdks/go`, each with an `examples/` shopping cart and a conformance service, for the rest. A sample that
has no home there yet gets one before it gets a tab. A link to a source file uses its address on
`https://github.com/thinkmorestupidless/ankka/blob/main/`.

## Generated blocks

| Block | Page | Generated from | By |
|---|---|---|---|
| `configuration` | `reference/configuration.md` | the runtime's HOCON files | `docs sync` |
| `protocol` | `reference/sidecar-protocol.md` | the protocol's `.proto` files | `docs sync` |
| `cli` | `reference/cli.md` | the CLI's command tree | `CliReferenceSuite` |
| `control-plane-routes` | `reference/control-plane-api.md` | the control plane's endpoints | `ControlPlaneRoutesReferenceSuite` |
| `model-presets` | `reference/model-providers.md` | the agent module's presets | `ModelPresetsReferenceSuite` |
| `console-properties` | `reference/console-package.md` | the console's design tokens | the console package's tests |

The JVM suites fail when their page is stale and rewrite it when asked:

```bash
sbt -Dankka.docs.update=true 'cli/testOnly *CliReferenceSuite' 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'
```

A new CLI command or control plane route fails those suites until its page has been rewritten and the
prose describes it.

## Descriptors are checked

A `json` code block whose title is `service.json` is a complete service descriptor:

````markdown
```json title="service.json"
{ "name": "cart", "service": { "image": "cart:1.0.0" } }
```
````

`DocumentationDescriptorsSuite` in `controlplane-api` reads every such block in `docs/` and applies the
same validation `ankka services apply` does, so no page can show a descriptor the platform would refuse.
Show a deliberately invalid descriptor with no title.

## Where the skills go

ankka's skills are curated under `tools/docs/skill/` and rendered in two places, both committed and
checked: the `ankka` plugin under `marketplace/plugins/ankka/`, which the release workflow pushes to the
`thinkmorestupidless/ankka-marketplace` repository on every tag, and the template's `.claude/skills/`,
whose copy escapes every `$` for Giter8. The `pr-review` plugin beside it is hand-written, not rendered
from pages, and is pushed by the same job.
