<!--
Title: a Conventional Commits header, `<type>(<scope>): <short description>`, under 72 characters.
The scope is the spec number for spec work (`feat(057): project tokens and the administrator`),
otherwise the area (`fix(operator): ...`, `docs: ...`). The title becomes the squash commit on main.

Body: the five sections below, under about 300 words outside the fenced block. Bullets of at most
two lines; anything longer belongs in the spec, research.md or the docs, linked from here. These
comments do not render; delete them or leave them.
-->

## Why

<!-- The problem or the gap, and what this introduces to close it. Link the spec (`specs/NNN-name/`)
or the issue. Two to five sentences; the spec holds the rest. -->

## What changed

<!-- How it is done: the components, modules or pages touched and the shape of the change, as at most
eight bullets. Say where a reviewer should start reading, and anything that will surprise them. -->

## Left out

<!-- What is deliberately not done here and where it goes (a follow-up, a task, a later spec). What
this waits on: a stacked base PR, a sibling repository's contract. Contracts, wire formats or schema
touched, ideally "none". -->

## How it is tested

<!-- The commands run and their results, in one fenced block. New suites or scenarios and what each
would catch. What was not run, and why. The three review-before-push lines when the branch came
through implement. -->

## Risk and rollback

<!-- What could go wrong once deployed, how it would show, and whether reverting this PR alone is
enough. "Low: additive, no schema or wire change" is a complete answer. -->
