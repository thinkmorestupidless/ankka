# Contract: the `design-build` extension

## `extension.yml`

```yaml
schema_version: "1.0"

extension:
  id: design-build
  name: "Design, then build"
  version: "0.1.0"
  description: "Two commands that run the feature workflow: /speckit-design takes a description to an analysed task list on the design model, asking only what the person must decide; /speckit-build takes the task list to a reviewed branch and a pull request marked ready on the build model. Each skill's model: line is the only record of its model; they differ because the deeper-thinking model decides and a cheaper one codes once everything is decided."
  author: thinkmorestupidless
  repository: https://github.com/thinkmorestupidless/ankka
  license: Apache-2.0

requires:
  speckit_version: ">=0.14.0"
  extensions:
    - bdd
    - review
  tools:
    - name: claude
      required: true
    - name: gh
      required: true

provides:
  commands:
    - name: speckit.design
      file: commands/speckit.design.md
      description: "Design a feature: specify, clarify, plan, tasks and analyze in one run on the design model"
    - name: speckit.build
      file: commands/speckit.build.md
      description: "Build a designed feature: implement, review and the pull request in one run on the build model"

tags:
  - "workflow"
  - "design"
  - "build"
```

No `hooks`: nothing runs the two commands but the person. No `config`: the model lines are in the
skills.

## The `extensions.yml` entry

```yaml
installed:
- bdd
- review
- design-build
```

Nothing under `hooks` changes.

## The command files

`commands/speckit.design.md`:

```yaml
---
description: "Design a feature: specify, clarify, plan, tasks and analyze in one run on the design model"
argument-hint: "<feature description>, or --from <stage>"
---
```

`commands/speckit.build.md`:

```yaml
---
description: "Build a designed feature: implement, review and the pull request in one run on the build model"
argument-hint: "optional --base <branch>"
---
```

The body of each is byte-for-byte the body of the matching SKILL.md. `DesignAndBuildExtensionSuite`
fails when they differ.

## Installing in another repository

Copy, never `specify extension add` (which would drop the `model:` lines):

```text
.specify/extensions/design-build/          → the same path
.claude/skills/speckit-design/SKILL.md     → the same path
.claude/skills/speckit-build/SKILL.md      → the same path
.specify/extensions.yml                    → add design-build under installed
```

The repository must already have `bdd` and `review` installed the same way, and the `pr-review`
plugin installed in the session (`/plugin install pr-review@ankka`).

## README.md

Says: what the two commands do; the stages each runs and where each asks; that the design model is
`fable` and the build model `opus` as shipped, why they differ, and that the only place to change
either is the `model:` line of its SKILL.md; the install-by-copy procedure above and why; the
individual `/speckit-*` commands for repairing one stage; that nothing merges, tags, publishes or
deploys.
