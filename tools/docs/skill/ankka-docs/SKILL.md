---
name: ankka-docs
description: Write, change or review the documentation of an ankka service or any repository documented with ankka's documentation tool — setting a repository's documentation up with docs init, a page and its frontmatter and kind, samples included from tested code, replies captured from the running service, a limitations page written from what the code does, a glossary, a project's skill and its pages list, docs check, docs sync, docs build, extra.docs in mkdocs.yml, and the kept build settings and documentation job. Use when the task is to document a repository or a service, write or fix a page, a guide, a tutorial or a reference page, write or review limitations, write a skill, or set a repository's documentation up.
pages:
  - build/documentation.md
  - reference/documentation-rules.md
---

# Writing documentation with ankka's documentation tool

The two reference files are the procedure (`build/documentation.md`) and the rules every page is held
to (`reference/documentation-rules.md`). Read both before writing a page; what follows is what to hold
while writing, not a second copy of either.

## Rules

- Set the repository up before writing anything: if there is no `mkdocs.yml` and no
  `tools/docs/pyproject.toml`, run `docs init` from a release of the tool. Never copy another
  repository's set-up, and never write a dependency on the tool that names a branch.
- Never edit `tools/docs/pyproject.toml` or `.github/workflows/docs.yml` by hand in a set-up repository.
  They are the tool's; `docs init` writes them, and the check refuses any other content.
- Every sample is an include of code a test runs. When the code you want to show has no test, write the
  test first, mark the region, then include it.
- Every reply, error or output a page shows is one the running service printed. Run it and copy; do not
  compose a plausible body.
- Write what the code does, not what the specification, the contract or the task description says it
  should do. Where they differ, the difference is a limitation.
- A page stands alone: no "see above", no feature numbers, no history of how something came to be.
- A new page goes in the `nav` and in a skill's `pages:` list in the same change, or the check fails.
- Run `docs sync`, then `docs check`, before saying a page is done; a page is done when the check
  passes.

## Questions to settle before writing

- Who reads this page, and what will they be able to do after it? That decides its kind.
- Which test or example already runs the code the page will show? If none, which test will?
- What does the service refuse, ignore or not offer in this area? Each answer is a line on the
  limitations page and a sentence on this one.
- Which words does the glossary already define, and which new ones does this page need it to?

## Mistakes to check for

- A code block typed into the page that `docs sync` does not fill.
- A reply that is the shape the contract promises rather than what the service returned.
- A limitations page that restates the specification's out-of-scope list instead of the code's actual
  behaviour, or is left empty.
- A skill body that repeats a table or list its pages already carry.
- A description that says what the page "covers" rather than what the reader will be able to do.
- Done without proof: change one line of an included region, see `docs check` fail naming the page, put
  it back.
