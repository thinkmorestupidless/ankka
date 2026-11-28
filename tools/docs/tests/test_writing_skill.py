"""The skill for writing documentation: one test per scenario of
`features/documentation/writing-documentation.feature`, named by the scenario, against ankka's own tree."""

from __future__ import annotations

import re
from pathlib import Path

import yaml

from ankka_docs.pages import FRONTMATTER, headings, load, project
from ankka_docs.render import page_markdown

ANKKA = Path(__file__).resolve().parents[3]
TARGETS = [ANKKA / "marketplace/plugins/ankka/skills/ankka-docs", ANKKA / "ankka.g8/src/main/g8/.claude/skills/ankka-docs"]
RULES = "reference/documentation-rules.md"
GUIDE = "build/documentation.md"


def frontmatter(text: str) -> dict:
    match = FRONTMATTER.match(text)
    assert match
    return yaml.safe_load(match.group(1))


def test_a_coding_agent_holding_the_platforms_skills_is_told_the_rules_a_page_follows() -> None:
    for target in TARGETS:
        rendered = (target / "SKILL.md").read_text()
        description = frontmatter(rendered)["description"]
        for words in ("document a repository", "write or fix a page", "limitations", "write a skill", "set a repository's documentation up"):
            assert words in description, words
        assert (target / "references" / RULES).is_file()
    root = (ANKKA / "tools/docs/skill/ankka/SKILL.md").read_text()
    assert "`ankka-docs`" in root, "the skill an agent starts from names it"


def test_the_rules_the_skill_gives_are_the_rules_the_documentation_publishes() -> None:
    current = project(ANKKA)
    tree = load(current)
    published = page_markdown(tree.pages[RULES], current.site_url)
    plain = (TARGETS[0] / "references" / RULES).read_text()
    assert plain == published
    assert (TARGETS[1] / "references" / RULES).read_text() == published.replace("$", "\\$")


def test_the_skill_says_how_a_repository_is_documented_in_order() -> None:
    tree = load(project(ANKKA))
    found = headings(tree.pages[GUIDE].body)
    order = [
        "Set the documentation up",
        "Read before writing",
        "Take every sample from tested code",
        "Show replies the running service gave",
        "Write the limitations from what the code does",
        "Break a sample and watch the check fail",
    ]
    positions = [found.index(h) for h in order]
    assert positions == sorted(positions), found
    body = tree.pages[GUIDE].body
    assert "living features" in body and "contract" in body
    for target in TARGETS:
        assert (target / "references" / GUIDE).is_file()
        assert "references/build/documentation.md" in (target / "SKILL.md").read_text()


def test_the_documentation_says_how_a_repositorys_documentation_is_set_up_and_no_longer_by_copying() -> None:
    tree = load(project(ANKKA))
    guide = tree.pages[GUIDE].body
    assert "docs init" in guide
    assert "Any developer of an ankka service" in guide
    assert tree.pages[GUIDE].kind == "guide"
    assert any(e.path == GUIDE for e in tree.nav)
    for path in (GUIDE, RULES, "contributing/documentation.md"):
        text = tree.pages[path].raw
        assert "@main" not in text, path
        assert not re.search(r"\bcop(y|ying|ied)\s+(it\s+|them\s+)?(from\s+)?(another|a sibling|a neighbouring|the sibling)", text, re.IGNORECASE), path


def test_a_developer_of_a_service_made_from_the_template_is_told_how_its_documentation_is_set_up() -> None:
    readme = (ANKKA / "ankka.g8/src/main/g8/README.md").read_text()
    assert "docs init" in readme
    assert "https://docs.ankka.cloud/build/documentation/" in readme
    assert "ankka@v$ankka_version$" in readme, "the tool at the release the service was made with"


def test_the_documentations_limitations_say_what_a_set_up_does_not_do() -> None:
    tree = load(project(ANKKA))
    limitations = tree.pages["reference/limitations.md"].body
    section = limitations[limitations.index("## Documentation") :]
    section = section[: section.index("\n## ", 1)]
    assert "GitHub Pages only" in section
    assert "kept: {job: false}" in section
