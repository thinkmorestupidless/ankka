# What the bytes in a `Payload` mean

A `Payload` is `{content_type, manifest, data}`. The sidecar never reads `data`; it stores it under
`manifest` exactly as the in-process runtime does, so a journal written by a service in one
language is read by the same service in another **only if both languages' codecs agree**. This
document is that agreement. It describes what the Scala SDK's default codecs
(`core.Codecs.make`, `core.Serializers`) produce today, and every SDK's default codec produces and
accepts exactly this. `fixtures/` holds generated examples; an SDK passes every one, both ways.

A developer may supply a codec of their own for a type. Then portability is their contract.

## JSON payloads — `content_type: application/json`

Produced by `Codecs.make[A]` in Scala (jsoniter-scala with discriminator `type`, no field omitted).

| shape | encoding | example |
|---|---|---|
| a record (case class, dataclass) | a JSON object; field names as declared; **every field written**, including empty collections and absent options | `{"productId":"p1","name":"Pen","quantity":2}` |
| a sum type (Scala `enum` with cases; a union of dataclasses) | the case's object with `"type":"<CaseName>"` added — the case's *simple* name; position of `type` not significant on read | `{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}` |
| a fieldless case of a sum type | an object with only the discriminator | `{"type":"CheckedOut"}` |
| a fieldless enumeration used as a field (Scala `enum Status { case Ready, Failed }`) | the same: `{"type":"Ready"}` — *unless* its companion supplies a string codec, as `ServiceLifecycle` does; such a type is declared in its own SDK with that codec | `{"type":"Ready"}` |
| an optional value inside a record (`Option[A]`, `A \| None`) | `null` when absent, `A`'s encoding otherwise | `{"note":null}` |
| a sequence (`Seq`, `Vector`, `List`, `list`, `tuple`) | a JSON array; empty written as `[]` | `{"items":[]}` |
| a map with string keys | a JSON object | `{"labels":{"tier":"gold"}}` |
| `String` | a JSON string | |
| `Int`, `Long`, `Short`, `Byte` | a JSON number with no fraction; a `Long` beyond 2⁵³ is still a number and must round-trip without loss | `9007199254740993` |
| `Double`, `Float` | a JSON number; jsoniter writes the shortest repr that round-trips | `1.5` |
| `Boolean` | `true` / `false` | |
| `java.time.Instant` | an ISO-8601 string in UTC with a `Z` suffix and as many fractional digits as needed (none, 3, 6 or 9) | `"2026-09-23T10:00:00Z"`, `"2026-09-23T10:00:00.123456Z"` |
| `java.time.Duration` | an ISO-8601 duration string | `"PT1.5S"` |
| `LocalDate`, `LocalDateTime` | ISO-8601 strings | `"2026-09-23"` |
| a nested record | inline object | |
| a recursive type | inline, as deep as it goes | |

Reading is lenient where writing is strict: a reader accepts an absent optional field as `None`,
accepts fields in any order, and ignores an unknown field. A reader refuses a missing required
field and a `type` it does not know.

## Text payloads — `content_type: text/plain`

Produced by `core.Serializers` for top-level primitives (a handler whose input or reply *is* a
primitive). UTF-8 text, no quotes, no JSON.

| Scala type | manifest | encoding | example |
|---|---|---|---|
| `String` | `string` | the text itself | `hello` |
| `Int`, `Long`, `Short`, `Byte` | `int`, `long`, `short`, `byte` | decimal | `42` |
| `Double`, `Float` | `double`, `float` | Scala's `toString` (`1.5`, `1.0E10`); readers accept any decimal or exponent form | `1.5` |
| `Boolean` | `boolean` | `true` / `false` | `true` |
| `FiniteDuration` | `duration-millis` | decimal milliseconds | `1500` |

## Binary payloads — `content_type: application/octet-stream`

| Scala type | manifest | encoding |
|---|---|---|
| `Done` | `done` | zero bytes |
| `Unit` | `unit` | zero bytes |
| `Array[Byte]` | `bytes` | the bytes |
| `Option[A]` at top level | `option[<A's manifest>]` | zero bytes for `None`; one byte `0x01` followed by `A`'s bytes for `Some` |

## Personal fields

A field a service marks as a personal field (`Personal` in every SDK) is never held in the clear. In
a payload a `Serializer` writes, for any store, it is the **personal envelope**:

```json
{"$personal":1,"project":"brand","subject":"player/8c1f","c":"<base64>"}
```

- `$personal` is the envelope's version; a reader refuses one it does not know, as corrupt.
- `project` and `subject` are in the clear: the project whose subject key sealed the field and the
  data subject it is about. A subject is 1 to 253 characters from letters, digits, `.`, `_`, `-`,
  `/` and `:`.
- `c` is standard base64, with padding, of a 12-byte nonce followed by the AES-256-GCM ciphertext
  and its 16-byte tag. The plaintext is the field's own JSON, compact. The associated data is the
  subject, the project and the serializer's manifest, in that order, each as a 4-byte big-endian
  length followed by its UTF-8 bytes.
- Once the data subject is erased, the envelope written again has no `c`, and every envelope of
  that subject reads as erased whether it has one or not.
- An envelope whose tag does not verify, under another subject, project, manifest or key, is
  corrupt, never erased.

Where no `Serializer` is writing (an endpoint's reply or a caller's body), the field is its value
with its subject beside it, `{"subject":"player/8c1f","value":…}`, or `{"subject":"player/8c1f"}`
once erased.

`fixtures/personal/` holds the cases every SDK must write byte for byte and read: `envelopes.json`
(with the key and nonce of each, the erased form, three corrupt envelopes and an unknown version),
`subjects.json` and `edge.json`. They are written by `protocol/scripts/personal_fixtures.py`, an
implementation independent of every SDK's: `uv run --with 'cryptography>=44' python
protocol/scripts/personal_fixtures.py`, then each SDK's copy script.

## Fixtures

`fixtures/<name>.json`:

```json
{ "manifest": "shopping-cart-event", "content_type": "application/json",
  "bytes_base64": "eyJ0eXBlIjoi...", "value": { "type": "ItemAdded", "item": { ... } } }
```

`value` is the decoded value in a language-neutral form: for JSON payloads the same JSON; for text
payloads the JSON scalar (`42`, `"hello"`, `true`, `1500`); for `Done`/`Unit`, `null`; for
`Option`, `null` or the inner value; for `bytes`, a base64 string. An SDK decodes `bytes_base64`
with the codec its manifest and content type select and compares to `value`, then re-encodes the
value and compares the bytes. A fixture with no matching codec is a failure, never a skip.

The fixtures are generated from the Scala codecs by `EncodingFixturesSuite` in `modules/core`,
which fails when regeneration changes a committed file, so this document, the fixtures and the
codecs cannot drift apart.
