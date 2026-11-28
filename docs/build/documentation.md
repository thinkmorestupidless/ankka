---
title: Document a service
description: Set up a service's documentation with ankka's documentation tool, then write it in order, from the code, the contract and what the running service replies, so every page passes the check.
kind: guide
related: [reference/documentation-rules.md, build/testing.md]
---

# Document a service

Any developer of an ankka service, or of any repository on GitHub, documents it with ankka's
documentation tool: one command sets the documentation up, and the pages are then written in the order
below, each step feeding the next. The rules each page follows are
[Documentation rules](../reference/documentation-rules.md); this page is the procedure.

## Set the documentation up

Set up first, before any page is written, with `docs init` from a release of the tool, at the root of the
repository:

```bash
uvx --from "git+https://github.com/thinkmorestupidless/ankka@v<release>#subdirectory=tools/docs" \
  docs init --description "Carts that customers fill and check out." --site-url https://docs.cart.example/
uv run --project tools/docs docs sync
uv run --project tools/docs docs build
```

`<release>` is a release of ankka that carries `docs init`: the one a service's `ankka` dependency
names, which the README of a service made from the template writes out in full, or the newest of
https://github.com/thinkmorestupidless/ankka/releases. The repository builds with that release of the
tool until `docs init` is run from a later one.

The command reads the repository's name and address from its `origin` on GitHub, writes the build
settings, the documentation job, `mkdocs.yml`, a first page, a page on writing documentation, a
limitations page, a glossary page, the service's skill and its plugin's description, and prints what is
left for a person to do:

```text
Left for a person:
  - run `uv run --project tools/docs docs sync`, then commit
  - turn the site on: the repository's Settings, Pages, Source "GitHub Actions"
  - let release tags publish: Settings, Environments, github-pages, Deployment branches and tags, add the tag rule "v*"
  - point docs.cart.example at GitHub Pages: a DNS CNAME, and the custom domain in the same settings
  - add to the Justfile:
      docs:
          uv run --project tools/docs docs build
      docs-sync:
          uv run --project tools/docs docs sync
      docs-serve:
          uv run --project tools/docs docs serve
  - the skill is published by this repository's own release: push marketplace/plugins/cart/ from it
```

The build passes on what the command wrote. From then on the documentation job checks every pull
request, and the site is published when a release is tagged. GitHub lets a tag deploy to Pages only
once the `github-pages` environment has a tag rule for `v*`; until a person adds it, the first release's
publish fails in the documentation job and nothing else of the release depends on it. A repository that publishes somewhere
other than GitHub Pages says `kept: {job: false}` under `extra.docs`, as
[the settings](../reference/documentation-rules.md#kept) describe.

## Read before writing

Read the code, its contract and its living features before writing a sentence about them. The code says
what the service does; the contract (an OpenAPI document, a `.proto` file, the descriptor) says what it
promises; the living features under `features/` say, scenario by scenario, what a user can rely on, in
the words of the glossary. A page written from memory of what the service should do describes a service
that does not exist. Where the three disagree, the code is what a reader will meet.

## Choose the pages by kind

Decide the pages before writing them, one job each, by the reader's question:

- a tutorial walks a newcomer from nothing to the service answering once;
- a concept page explains how one part works and why, for a reader deciding how to use it;
- a guide gets one task done, such as calling an endpoint or deploying;
- a reference page lists facts to look up, such as every route, every error or every setting.

A service usually starts with a tutorial, one guide per task a caller has, a reference page for its API
and its errors, and its limitations. Each page goes in the `nav` and in the skill's `pages:` list.

## Take every sample from tested code

Every sample a page shows is included from code a test runs: mark a region with `docs:start` and
`docs:end` comment lines in a test or an example, name it in an `<!-- include: -->` comment before the
page's block, and run `docs sync`. A sample with no tested home yet gets one first: a test that calls
the endpoint the way the page shows, or an example the build compiles. A sample typed into a page is
never run, and it stops being true the day the code changes.

## Show replies the running service gave

A reply shown on a page, such as the body an endpoint returns or the error a refusal carries, is one
the running service gave, captured from it rather than written by hand. Run the service locally
(`ankka local up`, or the service's own `run`), make the call the page shows, and copy what it printed,
status and body, into a `text` block beside the command. A reply composed from the contract shows what
the service promises, which is not always what it returns. Better still, a test that makes the same call
and asserts the same reply holds the page to it.

## Write the limitations from what the code does

The limitations page lists what the service does not do, read from the code: a route that refuses a case,
a field it ignores, an operation it does not offer, a size it caps. Where the service differs from its
contract, such as a status the OpenAPI document names and the code never returns, that difference is a
limitation too, stated plainly. Each limitation is also said on the page where a reader meets it.

## Write the skill's body last

The skill carries the pages; its body holds only what an agent must hold while working with the service:
the rules that apply to every task, the questions to settle before starting, and the mistakes to check
for. It restates no table or list a page gives, because the pages are in the skill already and a second
copy would drift.

## Break a sample and watch the check fail

Prove the samples are held: change one line of an included region in its source, run `docs check`, and
see it fail naming the page; then put the line back. A check that passes on a drifted sample is holding
nothing.

```text
docs/build/add-items.md:1: an included sample has drifted from its source; run `docs sync`
```
