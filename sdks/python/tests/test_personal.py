"""Personal fields against ``protocol/fixtures/personal``: the envelopes the Scala codec wrote open
here, an envelope written here opens there (the same key, cipher, associated data and lookup token),
and the rules on a subject, an erased one and a corrupt envelope hold."""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest

from ankka import personal as personal_module
from ankka.codec import EncodingError, from_json_value, json_codec, to_json_value
from ankka.personal import (
    DataSubjectError,
    Erased,
    FixedKeys,
    Personal,
    PersonalFieldError,
    Present,
    allowing_lookup,
    personal,
)

FIXTURES = Path(__file__).resolve().parent.parent / "proto" / "fixtures" / "personal"
if not FIXTURES.is_dir():
    FIXTURES = Path(__file__).resolve().parents[3] / "protocol" / "fixtures" / "personal"

KEYS = json.loads((FIXTURES / "keys.json").read_text())
ROWS = json.loads((FIXTURES / "envelopes.json").read_text())


@pytest.fixture(autouse=True)
def keys() -> Any:
    source = FixedKeys(
        KEYS["ownProject"],
        base64.b64decode(KEYS["subjectKey"]),
        base64.b64decode(KEYS["lookupKey"]),
        destroyed={KEYS["destroyedSubject"]},
    )
    # The fixtures put another project's subject under the same key, as a grant would hand it over.
    source.other_projects["payments"] = base64.b64decode(KEYS["subjectKey"])
    personal_module.install(source)
    yield source
    personal_module.install(None)


@dataclass(frozen=True)
class Address:
    street: str
    city: str


@dataclass(frozen=True)
class Profile:
    email: Personal[str]
    currency: str


@pytest.mark.parametrize("row", [r for r in ROWS if r["expect"] == "value"], ids=lambda r: r["name"])
def test_a_scala_envelope_opens_to_its_value(row: dict[str, Any]) -> None:
    read = from_json_value(row["envelope"], Personal[Any])
    assert isinstance(read, Present)
    assert read.value == json.loads(row["plaintext"])
    assert read.subject == row["subject"] and read.project == row["project"]


@pytest.mark.parametrize("row", [r for r in ROWS if r["expect"] == "erased"], ids=lambda r: r["name"])
def test_an_erased_or_destroyed_envelope_reads_as_erased(row: dict[str, Any]) -> None:
    read = from_json_value(row["envelope"], Personal[Any])
    assert read.is_erased and read.value is None and read.subject == row["subject"]


@pytest.mark.parametrize("row", [r for r in ROWS if r["expect"] == "corrupt"], ids=lambda r: r["name"])
def test_a_corrupt_envelope_is_refused(row: dict[str, Any]) -> None:
    with pytest.raises(EncodingError, match="corrupt"):
        from_json_value(row["envelope"], Personal[Any])


def test_an_envelope_written_here_opens_and_carries_the_scala_lookup_token() -> None:
    row = next(r for r in ROWS if r["name"] == "lookup")
    with allowing_lookup():
        written = to_json_value(personal("player/8c1f", "ada@example.com", lookup=True))
    assert written["subject"] == "player/8c1f" and written["project"] == "brand"
    assert written["lookup"] == row["lookup"]
    assert from_json_value(written, Personal[str]).value == "ada@example.com"


def test_no_lookup_token_outside_a_view_row() -> None:
    assert "lookup" not in to_json_value(personal("player/8c1f", "ada@example.com", lookup=True))


def test_a_record_round_trips_and_its_plaintext_never_reaches_the_bytes() -> None:
    codec = json_codec(Profile, "profile")
    data = codec.encode(Profile(personal("player/8c1f", "ada@example.com"), "GBP"))
    assert b"ada@example.com" not in data and b'"currency":"GBP"' in data
    assert codec.decode(data).email.value == "ada@example.com"


def test_a_nested_value_round_trips() -> None:
    written = to_json_value(personal("player/8c1f", Address("St James's Square", "London")), Personal[Address])
    assert from_json_value(written, Personal[Address]).value == Address("St James's Square", "London")


def test_a_fresh_value_for_an_erased_subject_is_refused(keys: FixedKeys) -> None:
    keys.erase("player/8c1f")
    with pytest.raises(PersonalFieldError, match="erased"):
        personal("player/8c1f", "ada@example.com")


def test_a_stored_value_of_an_erased_subject_is_written_erased(keys: FixedKeys) -> None:
    stored = from_json_value(to_json_value(personal("player/8c1f", "ada@example.com")), Personal[str])
    keys.erase("player/8c1f")
    assert stored.value is None
    assert to_json_value(stored) == {"subject": "player/8c1f", "project": "brand"}


def test_erased_encodes_without_data() -> None:
    assert to_json_value(Erased("player/8c1f")) == {"subject": "player/8c1f", "project": "brand"}


def test_a_subject_is_checked() -> None:
    for bad in ("", "a" * 254, "player 8c1f", "pläyer"):
        with pytest.raises(DataSubjectError):
            personal(bad, "x")


def test_a_value_is_never_printed() -> None:
    assert "ada@example.com" not in repr(personal("player/8c1f", "ada@example.com"))


def test_no_keyring_refuses_a_present_value() -> None:
    personal_module.install(None)
    with pytest.raises(PersonalFieldError, match="no keyring"):
        to_json_value(personal("player/8c1f", "ada@example.com"))


def test_an_erasure_handler_is_declared_and_refused_by_an_older_sidecar() -> None:
    from ankka.erasure import Done, ErasureContext
    from ankka.server import DiscoveryServicer
    from ankka.service import Ankka

    async def handler(ctx: ErasureContext) -> Done:
        return Done()

    registry = Ankka.service().on_erasure(handler).validate()
    assert registry.spec().erasure_handler and registry.spec().protocol_version == "1.15"
    refusal = DiscoveryServicer(registry).refusal("1.14")
    assert refusal is not None and "Run a sidecar speaking 1.15 or later" in refusal
    assert DiscoveryServicer(registry).refusal("1.15") is None
    assert not Ankka.service().validate().spec().erasure_handler


def test_a_second_erasure_handler_is_refused() -> None:
    from ankka.erasure import Done, ErasureContext
    from ankka.event_sourced_entity import RegistrationError
    from ankka.service import Ankka

    async def handler(ctx: ErasureContext) -> Done:
        return Done()

    with pytest.raises(RegistrationError, match="twice"):
        Ankka.service().on_erasure(handler).on_erasure(handler).validate()
