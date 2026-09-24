# Serialization and evolution

> How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in both languages, and how to change a stored type without breaking a journal.

Source: https://docs.ankka.cloud/build/serialization/
Everything ankka stores or sends — an entity's events and state, a view's rows, a handler's argument and
reply, a message on a topic — is encoded by a serializer, and stored with the serializer's *manifest*: a
name for the type that you choose. Records and sum types are encoded as JSON; primitives are encoded as
plain text. The encoding is the same in Scala and Python, so a journal written by a service in one
language is read by the same service in the other.

What is in a journal stays there. A change to a stored type is therefore a change to data that already
exists, and the rules for making one safely are the most important part of this page.

## Declaring a serializer

In Scala, `Codecs.serializer[A](manifest)` derives a JSON serializer for a type at compile time and names
its manifest. A type that cannot be encoded fails the build, not the first replay:

```scala
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

given Serializer[LineItem] = Codecs.serializer[LineItem]("line-item")
```

In Python, `json_codec(type, manifest)` does the same for a dataclass, a union of dataclasses, an `Enum`,
or any combination of them with lists, dicts and scalars:

```python
from ankka import json_codec

state_codec = json_codec(ShoppingCart, "shopping-cart")
event_codec = json_codec(ShoppingCartEvent, "shopping-cart-event")
```

An entity names a serializer for its state and one for its events. A view names one for its rows. A
consumer that publishes names one for its output. Handler arguments and replies need one too: in Scala,
primitives, `Done`, `Unit`, `Option` and `FiniteDuration` are provided by
`import com.thinkmorestupidless.ankka.core.Serializers.given`, and any other type needs a `given` of its
own. The Python SDK chooses the codec for a handler's argument and reply from its type annotations.

## Manifests are names you keep

A manifest is written beside every stored value, and replay finds the decoder by it. That is why ankka asks
you to name it rather than deriving it from the class name: with an explicit manifest, the class can be
renamed or moved freely, because nothing stored refers to the class. A manifest derived from a class name
would change the moment the class was renamed, and every existing event would stop decoding.

Treat a manifest like a wire name: choose it once, and never change it for a type that has been stored.

## What the JSON looks like

The encoding is defined once, in
[the protocol's encoding document](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md),
and both SDKs produce and accept exactly it.

| Value | Encoding | Example |
|---|---|---|
| a record: a case class or a dataclass | a JSON object with every field, including empty collections and absent options | `{"productId":"p1","name":"Pen","quantity":2}` |
| a case of a sum type: a Scala `enum` case or a member of a union of dataclasses | the case's object with `"type"` set to the case's simple name | `{"type":"ItemAdded","item":{"productId":"p1","name":"Pen","quantity":2}}` |
| a case with no fields | an object holding only the discriminator | `{"type":"CheckedOut"}` |
| an absent optional field | `null` | `{"note":null}` |
| a sequence | an array; empty is `[]` | `{"items":[]}` |
| a map with string keys | an object | `{"quantities":{"p1":5}}` |
| an instant | an ISO-8601 string in UTC | `"2026-09-23T10:00:00Z"` |
| a duration inside a record | an ISO-8601 duration | `"PT1.5S"` |

A handler whose argument or reply *is* a primitive, rather than containing one, is encoded as plain UTF-8
text with no JSON quoting:

| Type | Manifest | Example |
|---|---|---|
| `String` / `str` | `string` | `hello` |
| `Int` / `int` | `int` | `42` |
| `Long` | `long` | `42` |
| `Double` / `float` | `double` | `1.5` |
| `Boolean` / `bool` | `boolean` | `true` |
| `FiniteDuration` | `duration-millis` | `1500` |
| `Done` | `done` | zero bytes |

This matters at an HTTP endpoint: a route whose reply is a `String` answers `text/plain`, not a JSON
string, and a `String` body is posted raw.

## Field names are the contract

The JSON field names are the names of the fields as declared, in whichever language wrote them. A Scala
case class field `productId` and a Python dataclass field `productId` produce the same JSON; a Python
field named `product_id` would not, and a cart written by one could not be read by the other. When a
service may be read or written from both languages, or may move from one to the other, declare the fields
with the same names in both, as the shopping cart samples do.

The sum-type discriminator is the case's simple name: `ItemAdded`, not a qualified name. Renaming an event
case is therefore a stored-data change, even though renaming its enclosing type is not.

## Changing a stored type safely

Events, state, snapshots and view rows are read back long after they were written, by code that did not
write them. Reading is lenient where writing is strict, which gives a set of changes that are always safe:

| Change | Safe | Why |
|---|---|---|
| Add an optional field: `Option[A]` in Scala, `Optional[A]` in Python | yes | An old value lacks the field, which reads as absent. |
| Add a field with a default value | yes | An old value lacks the field, which reads as the default. |
| Add a new case to a sum type | yes | Old values never use it. Deploy the readers before anything writes it. |
| Remove a field | no, while old data may be read | Reading ignores the unknown field, but code that needed the value has lost it. |
| Rename a field | no | An old value has the old name, and the new required field is missing. |
| Change a field's type | no | The old value no longer decodes. |
| Rename or remove a sum-type case | no | An old value names a case the reader refuses. |
| Change a manifest | no | Replay cannot find the decoder. |

A required field missing from a stored value fails decoding, and so does a `"type"` the reader does not
know. For an event sourced entity that means the entity cannot be recovered, so a breaking change to an
event type breaks every entity that ever persisted it.

When a change that is not in the safe column is really needed, add rather than alter. Introduce a new
event case, or a new field with a default, have the event handler understand both old and new, and keep
the old case forever. Events describe what happened, and what happened does not change.

## Absent, null and defaults

A JSON `null` on an optional field reads as absent, and an absent field with a default reads as its
default. Together those have a consequence worth knowing: in Scala, an `Option` field whose default is not
`None` cannot express "none". Given `port: Option[Int] = Some(9000)`, both `{}` and `{"port": null}`
decode as `Some(9000)`. When a type needs to say "none" positively, give it a field that says so, such as
`http: Boolean = true`, rather than relying on a `null` to override a default.

## Writing your own codec

Either SDK accepts a serializer of your own: in Scala any `Serializer[A]` with a `manifest`, `toBytes` and
`fromBytes`; in Python any object satisfying the `Codec` protocol in `ankka.codec`, with a `manifest`, a
`content_type`, `encode` and `decode`. A custom codec is a contract you own. The
guarantee that another language, or the other SDK, reads the data applies only to the default encoding.
