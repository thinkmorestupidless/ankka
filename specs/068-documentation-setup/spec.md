# Feature Specification: Setting Up a Repository's Documentation With One Command, and a Skill for Writing It

**Feature Branch**: `068-docs-init`

**Created**: 2026-11-28

**Status**: Draft

**Input**: User description: "Fix how documentation gets produced across the family. (1) `docs init`
in ankka's docs tool, so a repository's documentation setup is generated from the tool's own
templates and not copied from a sibling: the pyproject, mkdocs.yml, the Pages workflow, the Justfile
recipes, the contributing page, the skill stub and the plugin manifest. (2) An `ankka-docs` skill,
rendered from ankka's own 'Writing documentation' page so it cannot drift from the rules, whose body
holds the procedure the page does not give: read the code and the contract first, run the thing and
capture real replies, choose each page's kind, write the limitations from what the code does, keep a
skill's body to rules, questions and mistakes."

## Context

ankka's documentation tool checks and builds the documentation of five repositories: ankka,
ankka-flow, satisfactory, ankka-reasoning and, since 2026-11-28, ankka-surface. The tool is shared.
The set-up around it is not: each repository got its `tools/docs/pyproject.toml`, its `mkdocs.yml`,
its workflow, its recipes, its contributing page, its skill and its plugin manifest by someone
reading a sibling's and copying them. ankka-surface was set up that way on 2026-11-28, from
ankka-reasoning's files, and the copy took longer than any page did.

The copies have drifted, because nothing holds them to anything:

- ankka-flow names the tool at a release tag; ankka-reasoning names the `main` branch, so a rule
  added to the tool can fail ankka-reasoning's build with no change in ankka-reasoning.
- The family's conventions say a site is published by a release, so it describes what a reader can
  install; ankka-reasoning and ankka-surface publish from `main`.
- ankka's own page on writing documentation tells a new repository to write the dependency line by
  hand, and shows one that names `main`.

And nothing tells an agent how to document a repository. The rules a page follows are on one page of
ankka's documentation, a contributing page, and contributing pages are in no skill. The procedure is
written nowhere: what to read before writing, that a reply shown on a page is the service's own
output, how a limitations page is written, what belongs in a skill's body. An agent asked to write
documentation in a sibling has the platform's skills for entities and endpoints and none for this.

This feature closes both. The decisions:

- **One command sets a repository's documentation up.** Run in a repository that has none, it writes
  everything a documentation build needs, from the tool's own templates, and the build passes on what
  it wrote. It asks for nothing it can read from the repository (its name, where it is kept) and takes
  the rest as arguments: a description, and the address the site will have.
- **What it writes is of two kinds.** Files the tool keeps: the build's settings (which release of
  the tool builds this repository) and the workflow. They are the same in every repository but for
  the repository's name, a person has no reason to edit them, and the check fails when one differs
  from what the tool would write. Files the tool starts and the repository owns: the navigation and
  site settings, a first page, a page on writing documentation here, a limitations page, a glossary
  page, the project's skill and its plugin's description. They are written once and never touched
  again.
- **It never overwrites.** A file already there is left as it is and named in the report. Running
  the command again in a set-up repository writes only what is missing, which is also how a
  repository takes a kept file a later release of the tool adds, and how a kept file changed by hand
  is put back: the person removes it and runs the command.
- **The set-up names the release of the tool that made it.** The repository builds with that release
  until someone raises it, so a change to the tool reaches a repository when the repository asks for
  it. The dependency is written in the form the repository's dependency updates already understand.
- **A set-up repository checks on every pull request and publishes on a release.** The workflow the
  tool keeps runs the build as a check on a pull request that touches the documentation or what it is
  generated from, and publishes the site when a release is tagged, never from the default branch. A
  tag that marks a pre-release publishes nothing. A repository with no release has no site yet, and
  its pull requests are still checked.
- **It says what a person still has to do.** Turning the site's hosting on, pointing the address at
  it, adding the recipes to the repository's command file when it has one, and pushing the plugin to
  the marketplace from the repository's release are not things a command run in a checkout can do.
  The report lists each, with the lines to add.
- **The rules for writing a page are in a skill, and are the published page.** A new skill of the
  platform, for writing documentation, carries ankka's page on writing documentation as it is
  published, so the skill and the site cannot say different things. A contributing page may be
  carried by a skill that names it; no other skill changes.
- **The procedure is a page too.** A new page in ankka's documentation says how a repository is
  documented, in order: set it up; read the code, the contract and the living features before
  writing; decide the pages by kind; take every sample from code a test runs, and give a sample that
  has no home one; show replies the running thing gave; write the limitations from what the code
  does, including where it differs from its contract; write the skill's body as rules, questions to
  settle and mistakes to check for, and nothing a page already says; break one included sample and
  watch the check fail. The skill carries this page, and its own body is the short form of it.
- **ankka's page on writing documentation stops teaching the copy.** It names the command.

Not in this feature: moving ankka-flow, satisfactory, ankka-reasoning and ankka-surface onto the
kept files (a pull request in each, once a release carries the command); holding a page's replies to
the running service; the check that refuses a hand-written sample in a repository whose pages name
no languages; and leaving a file under the documentation's directory out of the pages.

## Clarifications

### Session 2026-11-28

- Q: When does a repository set up by the command publish its site? → A: On a release tag. A pull
  request is checked; the default branch publishes nothing. ankka-reasoning and ankka-surface move
  to this when they adopt the kept workflow (Trevor).
- Q: Which version of the tool does the written dependency name? → A: The release the command ran
  from. Nothing names `main` (Trevor).
- Q: Where does the skill for writing documentation ship? → A: In the platform's own plugin, beside
  its other skills. Nothing is copied into a repository (Trevor).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A repository's documentation is set up by one command (Priority: P1)

A developer with a repository that has no documentation runs one command at its root, giving a
description and the site's address. The repository now holds everything a documentation build needs,
the build passes on it, and the report says what was written, what was left alone and what the
developer still has to do by hand. They start writing pages.

**Why this priority**: It is the half of the problem that costs the most each time and is the source
of every drift; the skill's procedure begins with it.

**Independent Test**: In an empty repository with a remote, run the command, then the build; the
build reports no problem and a site, the machine renderings and the project's skill exist.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/setting-up.feature`: a repository with no documentation is set up by one command and its documentation builds
- added `features/documentation/setting-up.feature`: the set-up names the release of the documentation tool that made it
- added `features/documentation/setting-up.feature`: setting up leaves what the repository already holds as it is and names it
- added `features/documentation/setting-up.feature`: setting up a repository that is already set up writes only what is missing
- added `features/documentation/setting-up.feature`: the set-up says what is left for a person to do

---

### User Story 2 - A set-up repository is held to the set-up (Priority: P2)

A maintainer of a set-up repository opens a pull request that touches a page. The workflow the tool
keeps checks the documentation and publishes nothing. When a release is tagged, the site is
published for that release. Months later someone edits the workflow by hand; the check fails and
says how to put it back.

**Why this priority**: Without it the command only moves the copy from a sibling to a template, and
the drift starts again the day after.

**Independent Test**: In a set-up repository, change one line of the kept workflow and run the
check: it fails naming the file. Remove the file, run the command, run the check: it passes.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/setting-up.feature`: a set-up repository checks its documentation on a pull request and publishes nothing
- added `features/documentation/setting-up.feature`: a set-up repository publishes its documentation when a release is tagged
- added `features/documentation/setting-up.feature`: a part of the set-up the documentation tool keeps that was changed by hand fails the check
- added `features/documentation/setting-up.feature`: a part of the set-up the documentation tool keeps is put back by removing it and setting up again
- added `features/documentation/setting-up.feature`: a repository that says the documentation tool keeps no documentation job there is held to none

---

### User Story 3 - A coding agent is told how to write documentation (Priority: P2)

A developer asks a coding agent holding the platform's skills to document a repository, or to add a
page. The agent loads the skill for writing documentation, sets the repository up with the command
when it is not, reads the code and the contract before it writes, takes its samples from tested
code, shows replies the service gave, and writes pages the check passes.

**Why this priority**: The rules exist and are enforced; what is missing is that the reader who most
often writes the pages is never given them, or the order to work in.

**Independent Test**: The platform's plugin holds a skill for writing documentation whose references
are the two pages as published, and the check fails when the rendered skill is stale.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/writing-documentation.feature`: a coding agent holding the platform's skills is told the rules a page follows
- added `features/documentation/writing-documentation.feature`: the rules the skill gives are the rules the documentation publishes
- added `features/documentation/writing-documentation.feature`: the skill says how a repository is documented, in order
- added `features/documentation/writing-documentation.feature`: the documentation says how a repository's documentation is set up, and no longer by copying

---

### Edge Cases

- The command is run somewhere that is not a repository's root, or in a repository whose name it
  cannot read: it writes nothing and says what it needs.
- The command is run from a checkout of the tool that is not a release (a branch, a local path): it
  refuses to write a dependency on something that is not a release, unless the release is named.
- The repository already has some of the files (a `mkdocs.yml` of its own, a workflow of the same
  name): each is left and named; the report says the build may not pass until they agree.
- The repository has no command file: the recipes are printed and no file is created for them.
- A set-up repository raises the tool's release and the new release's kept workflow differs: the
  check fails on the old one and says to remove it and set up again.
- A repository deliberately keeps a workflow of its own (ankka publishes its documentation inside
  its release workflow): it states, in its settings, that the tool keeps no workflow there, and the
  check holds it to nothing.
- A release is tagged in a repository whose site hosting was never turned on: the publish fails in
  the workflow, visibly, and nothing else of the release depends on it.

## Requirements *(mandatory)*

### Functional Requirements

**Setting up**

- **FR-001**: The documentation tool MUST have a command that sets up a repository's documentation,
  runnable in a repository that does not yet depend on the tool.
- **FR-002**: The command MUST read the project's name and where the repository is kept from the
  repository, take a description and the site's address as arguments, and write nothing when it
  cannot determine one of them, naming it.
- **FR-003**: The command MUST write, when absent: the build's settings; the workflow; the
  navigation and site settings with the tool's own block filled for this repository; a first page; a
  page on writing documentation in this repository; a limitations page; a glossary page; the
  project's skill naming every page written except the contributing one; and the description of the
  project's plugin.
- **FR-004**: The documentation build MUST pass, with no problem reported, on a repository holding
  only what the command wrote.
- **FR-005**: The command MUST NOT change or replace a file that exists; it MUST name each such file
  in its report as left.
- **FR-006**: The command MUST be safe to run again: in a repository where everything is present it
  writes nothing and says so.
- **FR-007**: The build's settings the command writes MUST name the release of the tool the command
  ran from, and never a branch. Run from something that is not a release, the command MUST refuse
  unless a release is named as an argument.
- **FR-008**: The command's report MUST list what was written, what was left, and what a person
  still has to do: turn the site's hosting on, point the site's address at it, add the recipes
  (given in full) to the command file, and push the plugin from the repository's release.
- **FR-009**: The pages the command writes MUST follow every rule the check holds, and MUST say
  plainly that they are a start: the first page says what is not yet written, and the limitations
  page is not left empty.

**Held to the set-up**

- **FR-010**: The check MUST fail when a file the tool keeps differs from what the tool's release
  named in the build's settings would write for this repository, naming the file and saying how it is
  put back.
- **FR-011**: The check MUST NOT fail on a kept file that is absent in a repository that states the
  tool keeps no such file there; absent with no such statement, it fails and names the command.
- **FR-012**: The kept workflow MUST run the build as a check on every pull request that touches the
  documentation, the tool's settings, the skill, or a path the repository's settings say samples or
  generated content come from, and MUST publish nothing from a pull request or from the default
  branch.
- **FR-013**: The kept workflow MUST publish the site when a release is tagged, built for that
  release, and MUST publish nothing for a pre-release tag.
- **FR-014**: The kept workflow MUST need no secret and no credential beyond what publishing the
  site itself requires.

**The skill**

- **FR-015**: The platform's plugin MUST hold a skill for writing documentation, rendered by the
  documentation tool as the platform's other skills are, and the check MUST fail when its rendering
  is stale.
- **FR-016**: The skill MUST carry ankka's page on writing documentation and the page on documenting
  a repository exactly as published; a skill MUST be able to carry a contributing page it names, and
  a contributing page no skill names stays out of every skill, as now.
- **FR-017**: ankka's documentation MUST have a page that says how a repository is documented, in
  order, covering: setting up with the command; what to read before writing; choosing pages by kind;
  samples from tested code and giving a sample a tested home; replies taken from the running thing;
  the limitations written from behaviour, including a difference from the contract; what a skill's
  body holds; and proving the check fails on a drifted sample.
- **FR-018**: The skill's body MUST hold the rules an agent holds while writing, the questions to
  settle before writing, and the mistakes to check for, and MUST NOT restate a table or list either
  page gives.
- **FR-019**: The skill's description MUST say when to load it in the words a request would use:
  writing, changing or reviewing documentation, a page, a limitations page, a skill, or setting a
  repository's documentation up.
- **FR-020**: ankka's page on writing documentation MUST name the command as how a repository takes
  the tool, and MUST NOT show a dependency line that names a branch or tell the reader to copy a
  sibling.
- **FR-021**: The family's conventions MUST be changed, when this is released, to say a repository's
  documentation is set up by the command, and the conventions' existing rule (published by a
  release) stands.

### Key Entities *(include if feature involves data; each a term in the project glossary)*

- **Documentation tool**: the program the platform publishes that checks, builds and now sets up a
  repository's documentation.
- **Set-up**: what the documentation tool writes into a repository so its documentation builds.
- **Kept part**: a part of the set-up the tool keeps the same in every repository, and the check
  holds to what the tool would write: the build settings and the documentation job.
- **Documentation job**: the repository's automation that checks its documentation on a pull request
  and publishes it on a release. The spec's prose calls its file the workflow; the features do not,
  because the glossary's workflow is a component.
- **Skill**: as the glossary has it; the new one is the skill for writing documentation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person sets up a repository that has no documentation and has a passing
  documentation build in under five minutes, without opening another repository.
- **SC-002**: After setting up, no file in the repository names a branch of the tool, and none was
  written by hand for the build to pass.
- **SC-003**: Every kept file is byte-identical across set-up repositories on the same release of
  the tool, except where it names the repository.
- **SC-004**: A change made by hand to a kept file is reported by the next check, in every set-up
  repository.
- **SC-005**: An agent given only the platform's plugin and the request "document this repository"
  sets it up with the command and produces pages the check passes, with every sample included from
  tested code, without being told a rule in the request.
- **SC-006**: The rules in the skill and the rules on the published page differ in no word.

## Assumptions

- A repository is a git checkout with a remote on GitHub; its name and address are read from that.
- The site is hosted as the family's sites are, by GitHub's pages, and a release is a tag that starts
  with `v`; a tag with a hyphen is a pre-release.
- A repository's command file, when it has one, is a `Justfile`; the command does not edit it.
- Pushing a plugin to the marketplace stays in each repository's release workflow, which needs a
  token the kept workflow must not hold.
- ankka itself keeps publishing its documentation from its release workflow and states that the tool
  keeps no workflow there.
- The four siblings adopt the kept files in pull requests of their own after the release that
  carries the command; until then they build as they do now.
- The brand assets of a site (a logo, a favicon) are the repository's to add; the set-up uses none.
