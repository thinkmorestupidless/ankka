# Data model: an OpenAPI document

Nothing here is stored. These are the values the feature passes between a route's declaration, the
build of a document and a test's check.

## Shape (`core.shape.Shape`)

The JSON form of a value.

| Case | Holds | Written as |
|---|---|---|
| `Scalar` | `kind` (string, integer, number, boolean), `format` (optional: `date-time`, `date`, `duration`, `uuid`, `int64`) | `{"type": kind, "format": format}` |
| `Obj` | `properties: Vector[(String, Shape)]` in declaration order, `required: Vector[String]` | an object schema |
| `Arr` | `items: Shape` | an array schema |
| `Dict` | `values: Shape` | `{"type":"object","additionalProperties": values}` |
| `OneOf` | `cases: Vector[(String, Shape)]`, each case's name and its `Obj` | `oneOf`, each with a required `type` constant |
| `Nullable` | `of: Shape` | `of` with `null` added to its type, or `anyOf` with `null` for a reference |
| `Ref` | `name: TypeName` | `{"$ref": "#/components/schemas/<name>"}` |
| `Raw` | `json: String`, checked to be a JSON object when the document is built | as given |

`TypeName` is a type's full name and its simple name. `Named(name, shape)` is a `Ref`'s target; a
`ShapeOf[A]` for a named type answers its `Ref` and the `Named` definitions it reaches, itself
included, so a self-reference terminates.

Rules: `required` holds only names in `properties`; a `OneOf` has at least one case; a `Raw` that
is not a JSON object is a problem of the document's build, naming the route.

## RouteShape (`http.openapi.RouteShape`)

What one route knows of itself. A field of `Route` and `StreamRoute`, with a default of "nothing
known" so every other place that builds a route is unchanged.

| Field | From |
|---|---|
| `pathParameters: Vector[(String, String)]` | the template's names and each handler parameter's `FromPath.name` |
| `body: Option[BodyShape]` | `None` for a route with no body; else the content type and `Option[Shape]` (`ShapeOf.Maybe` of the body type; none for text; never counted for a stream) |
| `answer: AnswerShape` | the status and content type the `ToResponse` gives for every value, or "decided by the handler"; `Option[Shape]`; whether it is a stream, an event stream or nothing |
| `description: Description` | the scopes around the route, outermost first, folded |

## Description (`http.openapi.Description`)

| Field | Meaning | May a scope holding several routes give it? |
|---|---|---|
| `summary: Option[String]` | one line | no |
| `body: Option[Shape]` | the body's shape, where the type gives none | no |
| `answers: Vector[(Int, Option[Shape])]` | an answer by status | no |
| `refusals: Vector[(Int, String, Option[Shape])]` | a status, a reason, a shape of its own | yes |
| `refusalShape: Option[Shape]` | the shape of every refusal that gives none | yes |
| `query: Vector[QueryParameter]` | `name`, `type` (a `FromPath` name), `required` | yes |
| `tags: Vector[String]` | route tags | yes |

Folding adds: lists append, a later scope's single value replaces nothing (two summaries, two body
shapes, the same query parameter twice, the same status twice are contradictions).

Contradictions, each a problem naming the route (FR-015): a described body or answer shape where
the type gives a different one; a described body on a route with no body; a query parameter named
twice, or named as one of the path's parameters; a single-route field in a scope with more than one route; a `Raw` that is not a JSON object.

## Document (`http.openapi.Document`)

| Part | Holds |
|---|---|
| `info` | the service's name, the version given or `0.0.0` |
| `operations` | one per route: method, whole path, path and query parameters, body, answers by status and `default`, tags, summary, security, what it lacks |
| `schemas` | every `Named` the operations reach, by its document name |
| `problems` | when it could not be built |

Built by `OpenApi.build(name, version, endpoints, Selection(tags, prefixes, strict))`. An operation
is **incomplete** when its body has no shape, or an answer with a body has none, streams excepted.
`Selection` keeps an operation that carries any of the tags, when tags are given, and is under any
of the prefixes, when prefixes are given; then only the schemas the kept operations reach; a
selection that keeps nothing is a problem.

## Violation (`http.openapi.DocumentCheck`, the test kits)

| Kind | Holds |
|---|---|
| answer not named | route, the status answered, the statuses the operation names |
| answer body refused | route, status, the validator's messages with their paths |
| accepted body refused | route, the status it was answered with, the validator's messages |
| query parameter not declared | route, the name |

Recorded for the length of a test by the kit's check; never changes an answer. A request body is
"accepted" when the answer's status is below 400.
