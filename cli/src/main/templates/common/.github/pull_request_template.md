<!--
Title: a Conventional Commits header, `<type>(<scope>): <short description>`, under 72 characters:
`feat(orders): reserve stock when an order is placed`, `fix(api): ...`, `docs: ...`. The title
becomes the squash commit on main.

Body: the five sections below, under about 300 words outside the fenced block. Bullets of at most
two lines; anything longer belongs in a design note or the docs, linked from here. These comments do
not render; delete them or leave them.
-->

## Why

<!-- The problem or the gap, and what this introduces to close it. Link the issue or the design note.
Two to five sentences. -->

## What changed

<!-- How it is done: the components and files touched and the shape of the change, as at most eight
bullets. Say where a reviewer should start reading, and anything that will surprise them. -->

## Left out

<!-- What is deliberately not done here and where it goes (a follow-up, an issue). What this waits
on. Contracts, wire formats, events or schema touched, ideally "none". -->

## How it is tested

<!-- The commands run and their results, in one fenced block. New tests and what each would catch.
What was not run, and why. -->

## Risk and rollback

<!-- What could go wrong once deployed, how it would show, and whether reverting this PR alone is
enough. "Low: additive, no event or wire change" is a complete answer. -->
