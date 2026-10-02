---
description: "Write or update the living features and glossary a spec describes, and reference the scenarios from the spec"
---

# Write the spec's scenarios into the living features

The living features describe what the system does now; a spec describes one change to it. This
command moves the spec's acceptance scenarios into the features, as Gherkin, and leaves the spec
naming them, so every scenario is written once.

## User input

```text
$ARGUMENTS
```

Consider the user input before proceeding, if it is not empty.

## Setup

1. Read `.specify/extensions/bdd/bdd-config.yml`. Where it is missing, use `glossary: GLOSSARY.md`,
   `features: features`, `specs: specs`, no `specs-from`, and as the `checker`
   `uvx --from "git+https://github.com/thinkmorestupidless/speckit-bdd@v0.2.0#subdirectory=checker" speckit-bdd`.
2. The spec is the one `/speckit-specify` just wrote. When that is unclear, take the most recently
   modified `specs/*/spec.md` and say which you took.
3. Read the glossary, and every `.feature` file under the features directory. When the glossary does
   not exist, create it:

   ```markdown
   # Glossary

   The words this project's features use, each in exactly one sense.

   ## Everyday words

   ```

## Writing scenarios

For each acceptance scenario the spec describes, decide whether it is new, changes a scenario that
exists, or removes one. Look for an existing scenario before writing a new one.

- **One `Feature` per capability**, in `features/<area>/<capability>.feature`. A `Background` holds
  the preconditions every scenario in the file shares.
- **A scenario's name is its rule, in one sentence**: "an unpaid order is cancelled after thirty
  minutes", not "cancellation test". The spec references it by this name.
- **`Given` is a state, `When` is one action, `Then` is an outcome a person could observe.** A
  scenario with two actions is two scenarios, unless the order of the actions is the point.
- **Write in the domain's words, and only the glossary's.** Never a screen, a button, an HTTP route,
  a status code, a table or a component: "When the customer cancels the order", not "When I POST to
  /orders/1/cancel". The same scenario must be runnable against one component and against the whole
  running system.
- **Values a step carries go in quotes** (`Given a card numbered "4242"`); they are data, not
  vocabulary.
- **Edge cases of one rule are a `Scenario Outline` with an `Examples` table**, one row per case.

## The glossary

Every word a step uses is a glossary term or an everyday word. For a domain word that is new,
add a term:

```markdown
### unpaid order
*Proposed.* An order that has been placed and not yet paid for.
```

`*Proposed.*` marks a term `/speckit-clarify` must settle: whether it is the right word, what it
does and does not mean, and which synonyms to refuse (`Avoid: basket, trolley`). An ordinary word
(pays, sent, holds) goes on the `## Everyday words` line instead. Never use two words for one thing:
if the glossary has a term, use it.

## The spec

Replace each acceptance scenario in the spec with a reference, under the same
`**Acceptance Scenarios**:` heading:

```markdown
- added `features/checkout/cancel.feature`: an unpaid order is cancelled after thirty minutes
- changed `features/checkout/pay.feature`: a declined card leaves the order unpaid
- removed `features/checkout/pay.feature`: a cheque is accepted
```

No Given/When/Then stays in the spec.

## Check

Run the checker from the project root, with the configured paths:

```bash
<checker> check --root . --glossary <glossary> --features <features> --specs <specs>
```

When the config has `specs-from`, add `--specs-from <specs-from>`: the specs before it were written
before the project had features, and the checker does not read them. Leave those specs as they are.

Fix what is yours to fix: a refused synonym is reworded; an undefined word becomes a proposed term
or an everyday word; a reference that names no scenario is corrected. Leave a **contradiction** as
it is: it is a question about what the system should do, and `/speckit-clarify` asks it.

## Report

List the features written or changed, each scenario added, changed or removed, the terms proposed,
and the findings left for clarification, each with its file and line.
