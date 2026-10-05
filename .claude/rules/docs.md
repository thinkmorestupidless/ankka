---
paths:
  - "docs/**"
  - "tools/docs/**"
  - "mkdocs.yml"
  - "marketplace/**"
  - "README.md"
---

# Documentation

## Documentation

One tree, `docs/`, of plain Markdown with YAML frontmatter; every way of reading it is a rendering
built by `tools/docs` (a `uv` project): the MkDocs Material site, `llms.txt`, `llms-full.txt`, a raw
Markdown copy of each page, `docs-index.json`, the Agent Skills (one per kind of task, curated in
`tools/docs/skill/<name>/SKILL.md` whose `pages:` list names the pages it carries; committed into
`marketplace/plugins/ankka/skills/` and into the template at `ankka.g8/src/main/g8/.claude/skills/`), and the pages on the CLI's classpath
that `ankka mcp` serves. `docs/contributing/documentation.md` is the full set of rules; the ones that
bite:

- **The tool is not ankka's alone.** Everything ankka-specific — the frontmatter vocabularies, the
  skill targets, which generator owns which block, the files that link to the site — is `extra.docs`
  in `mkdocs.yml`, and the tool finds the repository by the nearest `mkdocs.yml` above its working
  directory. satisfactory (`../satisfactory`) and ankka-flow (`../ankka-flow`) depend on `ankka-docs`
  from `tools/docs` and write their own blocks, so a rule changed here changes there. `uv run --project tools/docs pytest tools/docs` runs the tool
  against a fixture repository that is not ankka; a new ankka-specific constant in the Python is wrong,
  it goes in the block.

- **A page stands alone.** Its most common reader is a model that retrieved it alone. No positional
  references ("see above"), no internal history (feature numbers, specs, "a test found") — `docs check`
  refuses both. Explain behaviour as a property of the system.
- **Samples come from tested code.** Mark a region with `// docs:start name` / `// docs:end name` in a
  sample or test, name it in `<!-- include: path#name -->` before the page's code block, and run
  `just docs-sync`. The copy lives in the page on purpose — the raw Markdown must be complete — and
  `docs check` fails when it drifts, so a renamed method breaks the docs build, not the reader.
- **Reference facts are generated.** Between `<!-- generated:start name -->` comments: configuration
  and the protocol by `docs sync`; the CLI's commands (`CliReferenceSuite`) and the control plane's
  routes (`ControlPlaneRoutesReferenceSuite`) by Scala suites that fail on a stale page and rewrite it
  under `-Dankka.docs.update=true`. A coverage check makes the prose beside each table mention every
  fact, so a new variable or route is a failing build until someone says what it does.
- **Every `service.json` block in `docs/` is a valid descriptor.** `DocumentationDescriptorsSuite` in
  `controlplane-api` decodes and validates each with the platform's own rules.
- **Giter8 reads `$` as template syntax**, so the skill's copy in the template is written with every
  `$` escaped (`\$`); `TemplateSuite` expands the template and would catch a miss.
- **A new page goes in `mkdocs.yml`'s `nav` and in at least one skill's `pages:` list**, or `docs check`
  fails. `marketplace/` is ankka's part of the Claude Code marketplace, `thinkmorestupidless/ankka-marketplace`,
  which holds one plugin per project (ankka's, satisfactory's, ankka-flow's). The release workflow's `marketplace` job
  clones that repository, replaces `plugins/ankka/` and ankka's manifest entry only, and pushes an
  ordinary commit — never a subtree split or a force push, which would erase the other projects' plugins;
  the plugin's version is written by that job from the tag, so the checked-in `0.0.0` is deliberate. A new CLI command or control
  plane route fails the JVM suites until `just docs-reference` has run and the route has a
  hand-written section.
