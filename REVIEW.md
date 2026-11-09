# Review checklist

What a review of this repository checks beyond the general and language checklists of `pr-review`.
`CLAUDE.md` and the rule files under `.claude/rules/` come first; this is the list a reviewer walks
after them.

## Documentation

- **Every sample in every language.** A change to what a service can do is documented on the page for
  it with a code sample in every language the page's `languages` name, as one tab set in `mkdocs.yml`'s
  order (Scala, Java, Kotlin, Python, TypeScript, Rust, Go), each tab included from that language's
  tested examples. A language left out carries `<!-- languages: <shown>; <reason> -->` on the line
  before, and the reason is a limitation `docs/reference/limitations.md` states; a reason the
  limitations page does not state, or a page whose `languages` leave out an SDK the feature reaches, is
  a finding. `docs check` enforces the shape; the review checks the reasons, and that no SDK the change
  reaches was left undocumented.
- **A feature is not done until its documentation is.** A pull request that adds behaviour to the
  platform or to one SDK and leaves a sibling SDK's sample, its page or the limitations page behind is
  "Request changes", not a nit: the next reader of that page in that language is told the feature does
  not exist.
