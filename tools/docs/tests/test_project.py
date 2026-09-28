"""The tool against a repository that is not ankka.

Everything ankka-specific is `extra.docs` in `mkdocs.yml`, so a fixture repository with its own name,
vocabularies, generators and skill targets must pass every check, render every artefact, and be refused
for the same mistakes ankka's pages are.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ankka_docs import check, generate, render, skill, snippets
from ankka_docs.pages import ConfigError, find_root, load, project

MKDOCS = """\
site_name: widget
site_description: Widgets, as a service.
site_url: https://docs.widget.example/
docs_dir: docs
site_dir: build/site
nav:
  - Home: index.md
  - Build:
      - Make a widget: build/make.md
  - Reference:
      - Configuration: reference/configuration.md
  - Contributing:
      - Writing documentation: contributing/documentation.md
extra:
  docs:
    facets:
      flavours: [sweet, sour]
    skills:
      source: docs-skills
      targets:
        - path: plugin/skills
        - path: template/.claude/skills
          escape: giter8
    generated:
      configuration:
        kind: hocon
        files:
          - { path: src/reference.conf, scope: every widget }
        prefix: widget.
        described-on: reference/configuration.md
      routes:
        kind: external
    site-links:
      retired-hosts: [old.widget.example]
      files: [README.md]
"""

INDEX = """\
---
title: widget
description: What a widget is and where to start.
kind: concept
---

# widget

A widget is one thing, and costs $3. Read [Make a widget](build/make.md#the-recipe) next.
"""

MAKE = """\
---
title: Make a widget
description: Build one widget from its recipe.
kind: guide
flavours: [sweet]
related: [index.md]
---

# Make a widget

## The recipe

<!-- include: src/Widget.scala#recipe -->
```scala
```

/// tab | Scala

```scala
val w = Widget("sweet")
```

///

/// tab | Python

```python
w = Widget("sweet")
```

///
"""

CONFIGURATION = """\
---
title: Configuration
description: Every setting a widget reads.
kind: reference
---

# Configuration

<!-- generated:start configuration -->
<!-- generated:end configuration -->

`WIDGET_SIZE` is how big the widget is.

<!-- generated:start routes -->
| kept | as is |
<!-- generated:end routes -->
"""

CONTRIBUTING = """\
---
title: Writing documentation
description: How these pages are written.
kind: contributing
---

# Writing documentation

Pages are plain Markdown.
"""

SKILL = """\
---
name: widget
description: Build a widget. Use when a task names one.
pages:
  - index.md
  - build/make.md
  - reference/configuration.md
---

# widget

Make one thing at a time.
"""

SOURCE = """\
object Widget:
  // docs:start recipe
  def make(flavour: String): Widget =
    Widget(flavour)
  // docs:end recipe
"""

CONF = """\
widget {
  size = 3
  size = ${?WIDGET_SIZE}
  colour = "blue"
}
"""


def write(root: Path, path: str, text: str) -> None:
    file = root / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text, encoding="utf-8")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    write(tmp_path, "mkdocs.yml", MKDOCS)
    write(tmp_path, "docs/index.md", INDEX)
    write(tmp_path, "docs/build/make.md", MAKE)
    write(tmp_path, "docs/reference/configuration.md", CONFIGURATION)
    write(tmp_path, "docs/contributing/documentation.md", CONTRIBUTING)
    write(tmp_path, "docs-skills/widget/SKILL.md", SKILL)
    write(tmp_path, "src/Widget.scala", SOURCE)
    write(tmp_path, "src/reference.conf", CONF)
    write(tmp_path, "README.md", "See https://docs.widget.example/\n")
    return tmp_path


def problems(root: Path) -> list[str]:
    tree = load(project(root))
    return [str(p) for p in check.run(tree) + skill.check(tree)]


def test_the_root_is_the_nearest_mkdocs_yml(repo: Path) -> None:
    assert find_root(repo / "docs" / "build") == repo
    with pytest.raises(ConfigError):
        find_root(repo.parent)


def test_a_synced_repository_passes_every_check_and_renders_everything(repo: Path) -> None:
    tree = load(project(repo))
    assert snippets.sync(tree) == (["build/make.md"], [])
    assert generate.sync(tree) == (["reference/configuration.md"], [])
    skill.sync(load(project(repo)))

    assert problems(repo) == []

    page = (repo / "docs/build/make.md").read_text()
    assert "def make(flavour: String): Widget =" in page
    table = (repo / "docs/reference/configuration.md").read_text()
    assert "| `WIDGET_SIZE` | `widget.size` | `3` | every widget |" in table
    assert "| `widget.colour` | `\"blue\"` | every widget |" in table
    assert "| kept | as is |" in table, "an external block is left alone"

    plugin = (repo / "plugin/skills/widget/SKILL.md").read_text()
    assert plugin.startswith("---\nname: widget\n")
    assert "pages:" not in plugin
    assert "- `references/build/make.md` — Build one widget from its recipe." in plugin
    rendered = (repo / "plugin/skills/widget/references/build/make.md").read_text()
    assert "**Scala**" in rendered and "/// tab" not in rendered, "tabs are flattened off the site"
    assert "<!-- include:" not in rendered
    assert "Source: https://docs.widget.example/build/make/" in rendered
    plain = (repo / "plugin/skills/widget/references/index.md").read_text()
    escaped = (repo / "template/.claude/skills/widget/references/index.md").read_text()
    assert "costs $3" in plain and "costs \\$3" in escaped, "only the giter8 target escapes `$`"

    site = repo / "build/site"
    render.write(load(project(repo)), site)
    llms = (site / "llms.txt").read_text()
    assert llms.startswith("# widget\n\n> Widgets, as a service.\n")
    assert "- [Make a widget](https://docs.widget.example/build/make.md): Build one widget from its recipe." in llms
    index = json.loads((site / "docs-index.json").read_text())
    make = next(r for r in index if r["path"] == "build/make.md")
    assert make["flavours"] == ["sweet"]
    assert "languages" not in make, "only this repository's facets are indexed"
    assert make["headings"] == ["The recipe"]
    assert (site / "llms-full.txt").read_text().startswith("# widget documentation\n")


def test_stale_copies_are_reported_not_rewritten(repo: Path) -> None:
    found = problems(repo)
    assert "docs/build/make.md:1: an included sample has drifted from its source; run `docs sync`" in found
    assert "docs/reference/configuration.md:1: a generated block is stale; run `docs sync`" in found
    assert "docs/../plugin/skills:1: the rendered skills are stale; run `docs sync`" in found
    assert "docs/../template/.claude/skills:1: the rendered skills are stale; run `docs sync`" in found


def test_the_rules_use_this_repositorys_vocabulary(repo: Path) -> None:
    write(
        repo,
        "docs/build/make.md",
        MAKE.replace("flavours: [sweet]", "flavours: [bitter]\nlanguages: [scala]").replace(
            "## The recipe", "## The recipe\n\nAs described above, see [the code](../../src/Widget.scala)."
        ),
    )
    found = problems(repo)
    assert "docs/build/make.md:1: flavours value 'bitter' is not one of sweet, sour" in found
    assert any(p.startswith("docs/build/make.md:1: unknown frontmatter key 'languages'") for p in found)
    assert any("'described above' assumes the reader arrived in order" in p for p in found)
    assert any("link '../../src/Widget.scala' leaves docs/" in p for p in found)


def test_a_page_every_skill_leaves_out_and_a_contributing_page_a_skill_names(repo: Path) -> None:
    write(repo, "docs-skills/widget/SKILL.md", SKILL.replace("  - build/make.md\n", "  - contributing/documentation.md\n"))
    found = problems(repo)
    assert "docs/build/make.md:1: no skill carries this page; add it to a `pages:` list under docs-skills/" in found
    assert (
        "docs/../docs-skills/widget/SKILL.md:1: `pages:` names `contributing/documentation.md`, a contributing page, which is not about using widget"
        in found
    )


def test_a_retired_host_is_refused_wherever_the_site_is_linked(repo: Path) -> None:
    write(repo, "README.md", "See https://old.widget.example/\n")
    write(repo, "docs-skills/widget/SKILL.md", SKILL + "\nRead https://old.widget.example/ too.\n")
    found = problems(repo)
    assert "docs/../README.md:1: old.widget.example is not where the site lives; use site_url from mkdocs.yml" in found
    assert any(p.startswith("docs/../docs-skills/widget/SKILL.md:") for p in found)


def test_a_literal_escape_is_refused_only_when_a_target_escapes(repo: Path) -> None:
    write(repo, "docs/index.md", INDEX + "\nType a literal \\$ here.\n")
    assert any("a literal '\\$' is escaped again for the template" in p for p in problems(repo))
    write(repo, "mkdocs.yml", MKDOCS.replace("        - path: template/.claude/skills\n          escape: giter8\n", ""))
    assert not any("literal '\\$'" in p for p in problems(repo))


def test_an_image_must_exist_under_docs_and_say_what_it_shows(repo: Path) -> None:
    write(repo, "docs/assets/flow.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>")
    write(repo, "docs/build/make.md", MAKE + "\n![How a widget is made](../assets/flow.svg)\n")
    assert not any("image" in p for p in problems(repo))
    write(repo, "docs/build/make.md", MAKE + "\n![](../assets/flow.svg)\n![A lost picture](../assets/gone.svg)\n![Out](../../x.svg)\n")
    found = problems(repo)
    assert any("image '../assets/flow.svg' has no alt text" in p for p in found)
    assert any("image '../assets/gone.svg' points at a file that does not exist" in p for p in found)
    assert any("image '../../x.svg' leaves docs/" in p for p in found)


def test_a_repository_with_no_skills_and_no_generators_is_fine(tmp_path: Path) -> None:
    write(tmp_path, "mkdocs.yml", "site_name: plain\nsite_url: https://plain.example/\nnav:\n  - Home: index.md\n")
    write(tmp_path, "docs/index.md", INDEX.replace("Read [Make a widget](build/make.md#the-recipe) next.", "That is all.").replace("widget", "plain"))
    assert problems(tmp_path) == []


@pytest.mark.parametrize(
    ("block", "message"),
    [
        ("extra:\n  docs:\n    colours: []\n", "unknown key 'colours'"),
        ("extra:\n  docs:\n    facets:\n      title: [a]\n", "names 'title', which every page already has"),
        ("extra:\n  docs:\n    skills:\n      targets:\n        - path: x\n          escape: sed\n", "unknown escape 'sed'"),
        ("extra:\n  docs:\n    generated:\n      routes: external\n", "generated.routes is a mapping with a `kind`"),
    ],
)
def test_settings_the_tool_cannot_use_are_refused(tmp_path: Path, block: str, message: str) -> None:
    write(tmp_path, "mkdocs.yml", "site_name: x\nnav: []\n" + block)
    with pytest.raises(ConfigError, match=message):
        project(tmp_path)


def test_an_unknown_generator_kind_or_block_is_a_problem(repo: Path) -> None:
    write(repo, "mkdocs.yml", MKDOCS.replace("kind: external", "kind: yaml"))
    with pytest.raises(ConfigError, match="unknown kind 'yaml'"):
        generate.check(load(project(repo)))
    write(repo, "mkdocs.yml", MKDOCS)
    write(repo, "docs/reference/configuration.md", CONFIGURATION.replace("generated:start routes", "generated:start nobody").replace("generated:end routes", "generated:end nobody"))
    found = problems(repo)
    assert "docs/reference/configuration.md:14: no generator named 'nobody' in mkdocs.yml's extra.docs.generated" in found


def test_prose_must_describe_every_generated_variable(repo: Path) -> None:
    write(repo, "docs/reference/configuration.md", CONFIGURATION.replace("`WIDGET_SIZE` is how big the widget is.", "Sizes vary."))
    assert "docs/reference/configuration.md:1: `WIDGET_SIZE` is in the generated table but described nowhere on the page" in problems(repo)
