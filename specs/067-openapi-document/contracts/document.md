# Contract: the document

OpenAPI 3.1.0, JSON, UTF-8, two-space indentation, one final newline.

## Order

Fixed, so the same code gives the same bytes (FR-004):

1. Top level: `openapi`, `info`, `paths`, `components`.
2. `info`: `title` (the service's name), `version`.
3. `paths`: by path, in code-point order.
4. Within a path: `get`, `post`, `put`, `patch`, `delete`.
5. Within an operation: `operationId`, `summary`, `tags`, `parameters`, `requestBody`,
   `responses`, `security`, `x-ankka-admission`, `x-ankka-incomplete`. A key with nothing to say is
   left out.
6. `parameters`: path parameters in the path's order, then query parameters by name.
7. `responses`: by status, numerically, then `default`.
8. `components`: `schemas` by name, then `securitySchemes`.
9. Within a schema: `type`, `format`, `properties` (declaration order), `required` (declaration
   order), `items`, `additionalProperties`, `oneOf`, `const`, `$ref`. A `Raw` shape is written with
   its own keys sorted.

## An operation

```json
"/players/{playerId}/messages": {
  "get": {
    "operationId": "get_players_playerId_messages",
    "summary": "A player's messages",
    "tags": ["player"],
    "parameters": [
      {"name": "playerId", "in": "path", "required": true, "schema": {"type": "string"}},
      {"name": "page", "in": "query", "required": false, "schema": {"type": "integer"}}
    ],
    "responses": {
      "200": {"description": "", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Messages"}}}},
      "404": {"description": "not found", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AnkkaProblem"}}}},
      "default": {"description": "refused", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AnkkaProblem"}}}}
    },
    "security": [{"bearer": []}]
  }
}
```

- `operationId` is the method and the path with every character outside `[A-Za-z0-9]` made `_`,
  repeats collapsed: stable, and unique because method and path are.
- A path parameter's type: `string`, `integer` (`int`, `long`, the latter with `format: int64`),
  `boolean`, `string` with `format: uuid`.
- A text body or answer is `text/plain` with `{"type": "string"}`. A stream of bytes is its
  content type with no schema. An event stream is `text/event-stream` with no schema. Nothing is
  `204` with no content.
- An answer whose status the handler decides (`Respond`) is under `default` unless described.
- `default` is the endpoint's refusal shape, else `AnkkaProblem`.

## Extensions

| Key | On | Value |
|---|---|---|
| `x-ankka-incomplete` | an operation | what it lacks: `"request body"`, `"answer <status>"` |
| `x-ankka-admission` | an operation | `"not-open"`: its ACL is neither open nor tokens |

## Security

| ACL | Operation |
|---|---|
| allows all | `"security": []` |
| admits tokens (alone or among others) | `"security": [{"bearer": []}]`, with `components.securitySchemes.bearer` = `{"type": "http", "scheme": "bearer"}` |
| anything else | no `security`; `"x-ankka-admission": "not-open"` |

No issuer is named.

## `AnkkaProblem`

```json
"AnkkaProblem": {
  "type": "object",
  "properties": {
    "status": {"type": "integer"},
    "error": {"type": "string"},
    "details": {"type": "object", "additionalProperties": {"type": "string"}}
  },
  "required": ["status", "error"]
}
```

Present only when an operation refers to it. A service's own type named `AnkkaProblem` is a
problem of the build.

## Left out

Socket routes; an MCP endpoint's three routes and its tools; gRPC; every path under `/_ankka`,
this document's own included.

## Served

`GET /_ankka/openapi.json`, `200`, `application/json`, the bytes above for the whole service. Only
when the service stated an ACL for it; otherwise the path is answered as it was before this
feature.
