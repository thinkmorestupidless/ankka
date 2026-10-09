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
- Plaintext is the value's JSON in every case: a string is a JSON string with its quotes, a number
  its JSON number, a record its object.
- Associated data: `UTF-8(subject) ‖ 0x00 ‖ UTF-8(project)`. The manifest is not bound, so a consumer
  decoding a message under a type of its own opens the envelope.
- A decoder accepts the keys in any order; every encoder writes them in the order above.
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

`protocol/fixtures/personal/`: `keys.json` (`subjectKey` and `lookupKey`, base64 of fixed bytes that
are not secrets; `ownProject`; `destroyedSubject`, the one subject whose key reads as destroyed) and
`envelopes.json` (eleven rows: a string, a number, a record, a nested record, unicode text, a
lookup-marked string with its `lookup` token, another project's value, the erased form, a destroyed
subject, a corrupt tag, an envelope relabelled to another subject), each with `name`, `project`,
`subject`, `plaintext`, `expect` (`value`, `erased` or `corrupt`) and the `envelope` itself. Written
by `core`'s `PersonalFixturesSuite` under `-Dankka.fixtures.regenerate=on`; otherwise the file's rows
must be the suite's and each envelope must decode as its row says (the nonce is random, so bytes are
not compared). Every SDK opens each row with `subjectKey` for every subject but `destroyedSubject`
and must reach the row's `expect`; it also encodes each `value` row's plaintext and opens its own
envelope again.

## Textual form

`Personal(<subject>)` in Scala (`toString`), Python (`__repr__`, `__str__`), TypeScript
(`toString`, `inspect.custom`) and Rust (`Debug`, `Display`). Nothing else.
