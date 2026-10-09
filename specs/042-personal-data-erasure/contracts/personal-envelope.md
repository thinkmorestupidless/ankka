# Contract: the personal envelope

Held by `features/erasure/personal-fields.feature` and `features/erasure/languages.feature`; pinned
by `protocol/fixtures/personal/` (written by `PersonalFixturesSuite` in `core`, read by every SDK's
fixture test and by the sidecar's `personal.*` conformance cases).

## Grammar

```text
present:  {"subject":"<subject>","project":"<project>","data":"<base64>"}
lookup:   {"subject":"<subject>","project":"<project>","data":"<base64>","lookup":"<hex>"}
erased:   {"subject":"<subject>","project":"<project>"}
```

- Keys in this order, no whitespace, no other key. A decoder refuses an envelope with an unknown key,
  a missing `project`, or a `subject` outside `[A-Za-z0-9._\-/:]{1,253}`.
- `<base64>` is standard base64 with padding of `0x01 ‖ nonce(12 bytes) ‖ ciphertext ‖ tag(16 bytes)`,
  AES-256-GCM, 128-bit tag.
- Plaintext is the inner codec's bytes: for a record or sum type its JSON; for a primitive its text
  under the encoding's rule (`protocol/ENCODING.md`: a string is raw UTF-8, not a JSON string).
- Associated data: `UTF-8(subject) ‖ 0x00 ‖ UTF-8(project) ‖ 0x00 ‖ UTF-8(manifest)`, the manifest
  being the enclosing serializer's.
- `<hex>` is lowercase hex of `HMAC-SHA-256(lookupKey(project), plaintext)`.

## Decoding

| Envelope | Key | Result |
|---|---|---|
| erased form | any | `Erased(subject)` |
| present form | available | the value; `origin` set when `project` is not the decoder's own |
| present form | destroyed | `Erased(subject)` |
| present form | refused (another project, no grant) | `Erased(subject)` and the refusal recorded by the keyring |
| present form | unavailable (no keyring answers, nothing cached) | `Unavailable` naming the keyring; the enclosing decode fails |
| present form, tag fails | available | "personal envelope corrupt" — a decode error, never `Erased` |
| any form | no scope | `Unavailable` naming the keyring (encoding `Erased` is the one exception) |

## Fixtures

`protocol/fixtures/personal/`: `key.json` (a fixed 32-byte key and a lookup key, base64),
`envelopes.json` (ten envelopes: a string, an int, a record, a nested record, a list element, an
option, a lookup-marked string, an erased string, a foreign-project record, one with a corrupt tag),
each with `manifest`, `project`, `subject`, `plaintext` and the expected decode. Written by `core`'s
suite under `-Dankka.fixtures.regenerate=on` and refused when a row is not what the codec writes.
Every SDK reads the file and must decode each as the table says, and encode each present value to
an envelope whose decrypted plaintext equals the fixture's (the nonce is random, so bytes differ).

## Textual form

`Personal(<subject>)` in Scala (`toString`), Python (`__repr__`, `__str__`), TypeScript
(`toString`, `inspect.custom`) and Rust (`Debug`, `Display`). Nothing else.
