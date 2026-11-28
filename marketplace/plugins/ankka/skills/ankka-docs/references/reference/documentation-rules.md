# Documentation rules

> The rules every page documented with ankka's documentation tool follows, the commands that check and build it, and every setting a repository gives the tool.

Source: https://docs.ankka.cloud/reference/documentation-rules/
A repository documented with ankka's documentation tool keeps its documentation as one tree of plain
Markdown files under `docs/`, and every way of reading it is a rendering of that tree. People read the
site. Models read `llms.txt`, `llms-full.txt`, a Markdown copy of each page, or the agent skill the
repository's plugin carries. None of those has its own copy of the content, so a correction made once
reaches every reader.

The most common reader of any single page is a model that retrieved it alone, with no navigation around
it and no memory of the page before. Most of the rules here exist for that reader. They also make pages
better for people, which is why there is one set of rules rather than two. `docs check` holds every page
to them, and [Document a service](../build/documentation.md) says in what order a repository's
documentation is written.

## The commands

Everything runs through one command, from anywhere inside the repository:

```bash
uv run --project tools/docs docs check    # every rule on this page; exits 1 on a problem
uv run --project tools/docs docs sync     # refresh included samples, generated tables and the skills
uv run --project tools/docs docs build    # check, build the site, write the machine renderings
uv run --project tools/docs docs serve    # the site with live reload, while writing
uv run --project tools/docs docs init     # write the parts the tool keeps, and any missing part of the set-up
```

`docs build --release <major.minor.patch>` builds the site for that release of the repository and also
writes `latest.json`, naming it as the newest. The tool finds the repository by the nearest `mkdocs.yml`
at or above the directory it runs in.

A repository with no documentation yet takes the tool with `docs init`, run once from a release of the
tool before the repository depends on it:

```bash
uvx --from "git+https://github.com/thinkmorestupidless/ankka@v<release>#subdirectory=tools/docs" \
  docs init --description "Widgets, as a service." --site-url https://docs.widget.example/
```

`<release>` is a release of ankka that carries the command, as
https://github.com/thinkmorestupidless/ankka/releases lists them. It reads the repository's name and address from its `origin` on GitHub and writes the set-up: the build
settings and the documentation job, which the tool keeps, and the navigation, a first page, a page on
writing documentation, a limitations page, a glossary page, the project's skill and its plugin's
description, which the repository owns. A file the repository owns is written only when it is absent
and is never written over. `--release <major.minor.patch>` names the release of the tool the build
settings depend on when the tool was not run from a release tag, and `--force` writes a kept part over a
file at its path that the tool did not write.

The site and its renderings land in the `site_dir` of `mkdocs.yml`:

| File | For |
|---|---|
| `index.html` and one directory per page | people |
| `<page>.md` beside every page | a model reading one page |
| `llms.txt` | a model choosing pages: every page's title, link and one-sentence description, by section |
| `llms-full.txt` | a model that can take everything: every page, in navigation order, in one file |
| `docs-index.json` | a retriever: every page's metadata, headings and addresses |

## The parts the tool keeps

Two files of a set-up are the tool's, not the repository's. `tools/docs/pyproject.toml`, the build
settings, names the release of the tool that builds the documentation, and never a branch, so a change
to the tool reaches a repository when the repository asks for it. `.github/workflows/docs.yml`, the
documentation job, calls the documentation workflow in ankka's repository at the same release: it runs
the build as a check on every pull request, publishes the site to GitHub Pages when a tag `v*` with no
hyphen is pushed, and publishes nothing for a pre-release tag or from a branch. It holds no secret.
It publishes the directory `target/docs-site`, the `site_dir` the set-up writes, so a repository that
moves its `site_dir` publishes nothing. GitHub lets a tag deploy to Pages only when the repository's
`github-pages` environment has a tag rule for `v*`, which a person adds once (Settings, Environments);
without it the publish fails in the job.

Both open with the line `# Kept by ankka's documentation tool`, and both are the same in every
repository but for its name. `docs check` renders them again for the release the build settings name
and fails when either differs, naming the file. `docs init` writes them again every time it runs, which
is how a hand edit is put back and how a repository takes a later release of the tool: run `docs init`
from that release and both files move together.

## The settings

Everything that names a path, a vocabulary or a target in a repository is `extra.docs` in its
`mkdocs.yml`, never the tool's code. Each key is optional, and the tool refuses one it does not know.

### facets

The frontmatter keys a page may carry beyond `title`, `description`, `kind` and `related`, each with the
values it allows:

```yaml
extra:
  docs:
    facets:
      languages: [scala, java, kotlin, python, typescript, rust, go]
```

### samples

Which facet names a page's languages, the fence labels beyond the value itself that mean each language,
and whether every tab's code must be included from a tested file:

```yaml
extra:
  docs:
    samples:
      facet: languages
      fences: {typescript: [ts]}
      tested: true
```

### skills

Where the curated skills are (`tools/docs/skill` unless said otherwise), and every directory the
rendered skills are written to, committed and checked. A target read by Giter8, which takes `$` as its
own syntax, is marked `escape: giter8`:

```yaml
extra:
  docs:
    skills:
      source: tools/docs/skill
      targets:
        - path: marketplace/plugins/widget/skills
        - path: widget.g8/src/main/g8/.claude/skills
          escape: giter8
```

### generated

Who owns each `generated:start <name>` block: `hocon` (a configuration table from HOCON files, with
`files`, `prefix` and `described-on`), `protobuf` (a protocol's messages, from a `directory`), or
`external`, a block another tool rewrites and this one leaves alone.

### site-links

Files outside `docs/` that link to the site, and hosts the site no longer lives on. A link to a retired
host reads as correct and goes nowhere, so it is refused wherever the site is linked from, and a file
named here that does not exist is refused too:

```yaml
extra:
  docs:
    site-links:
      retired-hosts: [old.widget.example]
      files: [README.md]
```

### release

Where pages name a release of the repository: the file of patterns that say which forms are rewritten
to the release being built, and the git tags that are releases. With it, `docs check` refuses a version
equal to a release tag written in a form the patterns do not cover:

```yaml
extra:
  docs:
    release:
      patterns: docs/release-patterns.txt
      tags: "v*"
```

### kept

Which of the parts the tool keeps it keeps in this repository; both are kept when this says nothing.
`settings: false` says `tools/docs/pyproject.toml` is the repository's own, and `job: false` says the
repository publishes its documentation from a workflow of its own, or somewhere other than GitHub
Pages. The check holds a disclaimed part to nothing, and `docs init` neither writes nor reports it:

```yaml
extra:
  docs:
    kept:
      job: false
```

## Where a page goes

Every page has one job, named by its `kind`:

| Kind | Directory | The reader wants to | Example |
|---|---|---|---|
| `tutorial` | `get-started/` | be walked from nothing to something working | Your first service in Scala |
| `concept` | `concepts/` | understand how something works and why | Effects are data |
| `guide` | `build/`, `deploy/`, `operate/`, `platform/` | get one task done | Expose a service |
| `reference` | `reference/` | look one fact up | Service descriptor |
| `contributing` | `contributing/` | change the repository itself | Writing documentation |

A page that is drifting into a second kind is two pages. A guide that stops to explain a design at length
links to the concept page instead. A concept page that needs a table of every option links to the
reference page instead.

A new page is added to the `nav` in `mkdocs.yml`. `docs check` fails on a page the navigation does not
list, and on a navigation entry with no page.

## Frontmatter

Every page starts with this block:

```yaml
---
title: Views
description: Build a queryable projection of an entity's changes, query it with SQL, and choose between entity, key value and topic sources.
kind: guide
languages: [scala, python, typescript]
related: [concepts/consistency.md, build/topics.md]
---
```

| Field | Required | Meaning |
|---|---|---|
| `title` | yes | The page's name. The body's first line is `# ` and exactly this. |
| `description` | yes | One sentence, under 240 characters, ending with a full stop. It is what `llms.txt` and the skill show beside the link, so it says what the reader will be able to do or will understand, not what the page "covers". |
| `kind` | yes | `tutorial`, `concept`, `guide`, `reference` or `contributing`. |
| `related` | no | Paths under `docs/` of the pages a reader of this one most often needs next. |

Any other key is one of the repository's facets, with the values `extra.docs.facets` allows.

## Rules for every page

**A page stands alone.** It will often be read with no other page in view. Never point at other text by
its position on the page or in the site; a phrase such as `see below` means nothing to a reader holding
one chunk. Link to the section by its anchor instead: `[the journal](../concepts/consistency.md#the-journal-is-the-source-of-truth)`. The check
refuses the common forms of the positional phrase.

**Define a term on first use, or link to the glossary.** A word a repository gives a specific sense,
such as "wire name", "effect", "session" or "sidecar" in ankka, is used exactly as its glossary defines
it, and two words are never used for one thing.

**Open each section with its answer.** A heading is a question, and a retriever cuts pages at headings.
The first sentence under a heading answers it, so a chunk that begins at that heading is useful on its
own. Reasons and qualifications follow the answer.

**Explain why, as a property of the system.** A reader who knows the reason for a choice can apply it to
a case the page does not cover. State the reason as how the system behaves: "a query cannot persist,
because `query` accepts only a read-only effect". Do not tell the project's history: no feature numbers,
specification references, research notes or "a test caught this". The check refuses the common forms.

**Show, then say.** A concept is followed by code, and a command by what it prints. Output goes in the same
block as a comment, or in a `text` block after it.

**Every code block names its language.** `scala`, `java`, `kotlin`, `python`, `ts`, `rust`, `go`, `bash`,
`json`, `yaml`, `hocon`, `protobuf`, or `text` for output. A block with no language fails the check.

**Complete samples, or say it is an excerpt.** A sample a reader can copy should compile or run as shown,
imports included. Where a sample is an excerpt of something larger, the sentence before it says so and
links to the whole.

**Every language, in one tab set.** A page shows what it shows in every language it names. When a page's
languages name more than one, each code sample is a tab set with one tab per language, in the order
`mkdocs.yml` lists them. A sample in one language alone tells every other reader that the feature is not
theirs, and the gap is invisible to a writer who reads one language at a time, so the check refuses it: a
tab set with one of the page's languages missing, and a block in one of those languages outside a tab
set, both fail. Each tab opens with `/// tab | <name>` and closes with `///` on its own line:

````markdown
/// tab | Scala

```scala
val getCart = query("get-cart")(_.getCart)
```

///

/// tab | Python

```python
@query("get-cart")
def get_cart(self) -> ReadOnlyEffect[ShoppingCart, ShoppingCartEvent, ShoppingCart]: ...
```

///
````

On the site the reader picks a language once and every tab set on every page follows, remembered between
visits. Everywhere else — the raw Markdown, `llms-full.txt`, the agent skills — the markers are flattened
back to a bold language label above each block, so a page still reads correctly with no renderer at all.

This is the **only** renderer syntax a page may use. No admonitions, no snippet includes, no other
extensions. Tabs earn the exception because the alternative is the same example several times down the
page; they are written in the `///` blocks syntax rather than the indented one precisely so the fenced
blocks stay flush left and a reader with no renderer still sees ordinary Markdown.

A tab set that shows the same *idea* in each language must show the same *example* in each: one domain,
one set of names. Three languages solving three different problems under one set of tabs reads as a
translation and is not one.

**A language is left out only when the feature is not available in it**, and the repository's
[limitations](limitations.md) say so. Say so where the check can read it, in a comment on the line
before the tab set or the block, naming exactly the languages shown and the reason:

```markdown
```

The prose beside it says the same thing in words a reader sees. A page's languages are every language
the page's subject exists in, so a page whose every sample needs the comment has its languages wrong,
and a comment naming every language the page does is refused as noise. A page about one language, such
as a first-service tutorial or an SDK reference, names that language alone and needs no tabs.

**A diagram is an SVG whose alt text says everything it shows.** Diagrams live under `docs/assets/` as
hand-written SVG, each with its own background and a `prefers-color-scheme` rule for dark mode, so one
file reads correctly on the site, on GitHub and in either theme. Only the site shows the picture: a model
reading the raw Markdown, `llms-full.txt` or a skill gets the alt text and nothing else, so the alt text
names every part and every path the diagram draws, and the prose beside it still stands on its own. The
check refuses an image with no alt text, and one whose file does not exist under `docs/`.

**Link to repository files by their GitHub address.** Links between pages are relative
(`../build/views.md`) and the check verifies both the page and the anchor. A link to a source file uses
its full address on GitHub, because a relative path out of `docs/` does not exist on the site.

**Say what is not there.** A limitation the reader will hit belongs on the page where they hit it, stated
plainly, and on the repository's [limitations](limitations.md).

## Samples come from tested code

A sample that the build compiles and tests is included from its source rather than typed into the page.
Mark the region in the source file with comment lines:

```text
// docs:start add-item
def addItem(item: LineItem): Effect[Done] =
  if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
  else effects.persist(ItemAdded(item)).thenReply(_ => Done)
// docs:end add-item
```

In a language whose comments open with `#`, the same two lines are `# docs:start add-item` and
`# docs:end add-item`.

Name the region in a comment on the line before the page's code block:

````markdown
```scala
```
````

`docs sync` fills the block with the region, dedented. `docs check` fails when a block has drifted from
its source, so a renamed method breaks the documentation build rather than silently leaving a page that no
longer compiles. The copy lives in the page on purpose: a page must be complete as raw Markdown, and an
include resolved only at render time would leave a model reading the source with an empty block. Leave out
the region name to include a whole file. The markers are comment lines: `//` in Scala, Java, Kotlin,
TypeScript, Rust and Go, `#` in Python.

`docs sync` copies the working tree, not the last commit, so an uncommitted edit to an included file
lands in every page that includes it. A sample that has no tested home yet gets one before it gets a
tab.

With `samples.tested` set, `docs check` refuses a tab whose code is written in the page. A tab that no
test can hold, such as a line of the reader's own build file, says why in a comment on the line before
its block, and the reason is what a reviewer checks:

````markdown
/// tab | Scala

```scala
"com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion
```

///
````

A block outside a tab set, such as a shell command or a descriptor, is written by hand. Keep it short.

## Generated reference

Reference facts that the code already knows are generated from it, between two comments:

```markdown
| Variable | Configuration key | Default | Applies in |
...
```

The content between the comments belongs to the generator named in `extra.docs.generated`, and hand
edits there are overwritten. The prose around the block is written by hand, and a coverage check fails
when the prose does not describe every fact the table lists. Adding a configuration key is therefore a
build failure until someone writes a sentence about it.

## The skills

The documentation renders into Agent Skills, one per kind of task, under the skills source: each
directory holds one hand-written `SKILL.md` whose frontmatter names the skill, says when an agent should
load it, and lists in `pages:` the documentation pages it carries. The body holds the rules an agent
must hold for that task, the questions to settle before writing, and the mistakes to check for. Keep it
to what the pages do not say in one place; everything else is generated: an index of the named pages
with their descriptions, and the pages themselves under `references/`.

A page may belong to several skills, and every page that is not a contributing page must belong to at
least one, so adding a page means naming the skill it serves or `docs check` fails. A page with a good
description is therefore a page an agent can find. No skill carries a contributing page, which is about
changing the repository rather than using what it makes. The rendered skills are committed in every
target `extra.docs.skills` names, and `docs check` fails when one is stale.
