"""Writes protocol/fixtures/personal/: the personal envelope every SDK must write byte for byte.

Run from the repository root:

    uv run --with 'cryptography>=44' python protocol/scripts/personal_fixtures.py

The keys and nonces are fixed, so the output is the same on every run; a change to it is a change
to the wire, which every SDK must follow. The rules it encodes are in protocol/ENCODING.md
("Personal fields") and specs/042-personal-data-erasure/data-model.md.
"""

import base64
import json
import pathlib
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

OUT = pathlib.Path(__file__).resolve().parent.parent / "fixtures" / "personal"


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def associated_data(subject: str, project: str, manifest: str) -> bytes:
    """Each part as a 4-byte big-endian length and its UTF-8 bytes: subject, project, manifest."""
    out = b""
    for part in (subject, project, manifest):
        raw = part.encode("utf-8")
        out += struct.pack(">I", len(raw)) + raw
    return out


def seal(key: bytes, nonce: bytes, subject: str, project: str, manifest: str, plaintext: str) -> dict:
    sealed = AESGCM(key).encrypt(nonce, plaintext.encode("utf-8"), associated_data(subject, project, manifest))
    return {"$personal": 1, "project": project, "subject": subject, "c": b64(nonce + sealed)}


KEY_A = bytes(range(32))
KEY_B = bytes(range(32, 64))
NONCE_1 = bytes.fromhex("000102030405060708090a0b")
NONCE_2 = bytes.fromhex("0c0d0e0f1011121314151617")
NONCE_3 = bytes.fromhex("18191a1b1c1d1e1f20212223")

CASES = [
    ("a string", KEY_A, NONCE_1, "brand", "player/8c1f", "player-registered", '"ada@example.com"'),
    ("a date", KEY_A, NONCE_2, "brand", "player/8c1f", "player-registered", '"1990-04-01"'),
    ("a record", KEY_B, NONCE_3, "payments", "cardholder/77a0", "deposit-made",
     '{"line1":"1 High St","city":"Leeds"}'),
    ("unicode and every allowed subject character", KEY_A, NONCE_1, "brand", "a.b_c-d/e:f9",
     "note-taken", '"Zoë — naïve 東京"'),
]


def envelopes() -> dict:
    cases = []
    for name, key, nonce, project, subject, manifest, plaintext in CASES:
        cases.append({
            "name": name,
            "key": b64(key),
            "nonce": b64(nonce),
            "project": project,
            "subject": subject,
            "manifest": manifest,
            "plaintext": plaintext,
            "envelope": seal(key, nonce, subject, project, manifest, plaintext),
        })
    first = cases[0]
    spliced = dict(first["envelope"], subject="player/41d2")
    other_manifest = first["manifest"] + "-other"
    return {
        "about": "Each case: the field's own JSON (plaintext) sealed under key with nonce, associated data "
                 "subject, project, manifest each length-prefixed (4-byte big-endian). Writers must produce "
                 "envelope exactly, keys in this order, compact, standard base64 with padding.",
        "cases": cases,
        "erased": [
            {"name": "a data subject alone", "project": "brand", "subject": "player/8c1f",
             "envelope": {"$personal": 1, "project": "brand", "subject": "player/8c1f"}},
        ],
        "corrupt": [
            {"name": "an envelope copied to another data subject", "key": first["key"], "manifest": first["manifest"],
             "envelope": spliced},
            {"name": "an envelope read under another manifest", "key": first["key"], "manifest": other_manifest,
             "envelope": first["envelope"]},
            {"name": "an envelope sealed under another key", "key": b64(KEY_B), "manifest": first["manifest"],
             "envelope": first["envelope"]},
        ],
        "unknown-version": {"$personal": 2, "project": "brand", "subject": "player/8c1f", "c": "AAAA"},
    }


MESSAGE = ("a data subject is 1 to 253 characters from letters, digits, '.', '_', '-', '/' and ':'")


def subjects() -> dict:
    return {
        "message": MESSAGE,
        "valid": ["player/8c1f", "a", "x" * 253, "a.b_c-d/e:f9", "cardholder/77a0", "ORG:Team/1"],
        "invalid": ["", "x" * 254, "player 8c1f", "player/8c1f?", "zoë", "a\nb", "a@b"],
    }


def edge() -> dict:
    return {
        "about": "A personal field where no serializer is encoding (an endpoint's reply or a caller's body).",
        "cases": [
            {"name": "present", "subject": "player/8c1f", "value": "ada@example.com",
             "edge": {"subject": "player/8c1f", "value": "ada@example.com"}},
            {"name": "present record", "subject": "cardholder/77a0", "value": {"line1": "1 High St", "city": "Leeds"},
             "edge": {"subject": "cardholder/77a0", "value": {"line1": "1 High St", "city": "Leeds"}}},
            {"name": "erased", "subject": "player/8c1f", "edge": {"subject": "player/8c1f"}},
        ],
    }


def write(name: str, value: dict) -> None:
    (OUT / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    write("envelopes.json", envelopes())
    write("subjects.json", subjects())
    write("edge.json", edge())
