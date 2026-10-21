# Contract: what an MCP client sees on the wire

The HTTP surface of a service with an MCP endpoint at `<path>` (default `/mcp`), at any hostname the
service answers at. Decisions R3, R7, R10, R11 in [../research.md](../research.md). Protocol
revision 2025-06-18; `2025-03-26` is also answered.

## The MCP endpoint

| Method and path | Headers read | Answer |
|---|---|---|
| `POST <path>` | `Authorization`, `MCP-Protocol-Version`, `Origin`, `Content-Type` | admitted by the ACL first (401/403/503 below); then one JSON-RPC message → `200 application/json` with one response, or `202` with no body for a notification or response |
| `GET <path>` | the same | admitted first; then `405 Method Not Allowed`, `Allow: POST` |
| `DELETE <path>` | the same | admitted first; then `405` |
| any other method | | `405` |

Before the kernel: `MCP-Protocol-Version` other than `2025-03-26` or `2025-06-18` → `400` with
`{"jsonrpc":"2.0","error":{"code":-32600,"message":"unsupported MCP-Protocol-Version: …"},"id":null}`;
`Origin` present and not the request's own host → `403`. `Mcp-Session-Id` is ignored and never
sent. No `text/event-stream` is ever produced.

### JSON-RPC

| Method | Params | Result |
|---|---|---|
| `initialize` | `protocolVersion`, `capabilities`, `clientInfo` | `{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":…,"version":…}}` |
| `notifications/initialized`, `notifications/cancelled` | | `202`, no body |
| `ping` | | `{}` |
| `tools/list` | `cursor` ignored | `{"tools":[{"name","description","inputSchema"}]}`, the tools the caller may call, no `nextCursor` |
| `tools/call` | `name`, `arguments` | `{"content":[{"type":"text","text":…}],"structuredContent":{…}?,"isError":false}` |

Errors:

| Case | Shape |
|---|---|
| body not JSON | `400`, `{"jsonrpc":"2.0","error":{"code":-32700,"message":"parse error: …"},"id":null}` |
| a batch array | `400`, `-32600 "batches are not accepted in protocol 2025-06-18"` |
| no `method` and no `result` | `400`, `-32600` |
| unknown method | `200`, `-32601 "method not found: resources/list"` |
| unknown tool, tool outside the key's scope, tool the caller's ACL refuses | `200`, `-32602 "tool not available: close_books"` |
| arguments the tool's types refuse | `200`, `-32602 "parameter 'quantity': expected an integer"` (or `"argument 'jobId' is required"`) |
| handler throws, refuses (`Rejection`, `HttpProblem`), or a referenced route answers ≥ 400 | `200`, `{"result":{"content":[{"type":"text","text":"quantity must be positive"}],"isError":true}}` |
| handler does not finish within the request timeout | the request ends as a route's does (`504`); the handler runs on |

## Admission answers (from the router, before the kernel)

| ACL outcome | Answer |
|---|---|
| no credential, endpoint admits tokens | `401`, `WWW-Authenticate: Bearer resource_metadata="<scheme>://<host>/.well-known/oauth-protected-resource<path>"` |
| no credential, endpoint admits client keys only | `401`, `WWW-Authenticate: Bearer realm="ankka"` |
| no credential, endpoint admits named workloads only | `403` (the caller was read from the certificate and did not match) |
| a client key: unknown, revoked, wrong secret | `401`, `… error="invalid_token", error_description="client key not recognised"` |
| a client key: expired | `401`, `… error_description="client key expired"` |
| a token the issuers refuse | `401`, `… error="invalid_token", error_description="<reason>"` as today |
| the index or the verifier is not ready | `503`, `Retry-After: 5` |
| a credential of a kind the ACL does not admit | `401` with the ACL's first challenge |

A client key or a token at a route whose ACL does not ask for it is refused as that route's ACL
refuses anything else; `Oidc.authenticate()` alone answers a client key `invalid_token`.

## Resource metadata (when the endpoint admits tokens)

`GET /.well-known/oauth-protected-resource<path>` → `200 application/json`:

```json
{"resource": "https://cart.shop.ankka.example/mcp",
 "authorization_servers": ["https://auth.example.com/realms/customers"],
 "bearer_methods_supported": ["header"]}
```

`resource` is built from the request's `Host`; `authorization_servers` lists each configured issuer's
URL, or, when the endpoint is brokered, the one URL `<scheme>://<host>/_ankka/sign-in<path>`. An
endpoint that admits no token has no such route (`404`, the service's ordinary one).

## The sign-in broker (when the endpoint is brokered)

Issuer `<scheme>://<host>/_ankka/sign-in<path>`.

| Route | Request | Answer |
|---|---|---|
| `GET /.well-known/oauth-authorization-server/_ankka/sign-in<path>` | | `{"issuer","authorization_endpoint","token_endpoint","registration_endpoint","jwks_uri","response_types_supported":["code"],"grant_types_supported":["authorization_code","refresh_token"],"code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"],"scopes_supported":[]}` |
| `POST /_ankka/sign-in<path>/register` | `{"redirect_uris":[…],"client_name"?,"token_endpoint_auth_method"?:"none"}` | `201`, `{"client_id","client_id_issued_at","redirect_uris","client_name","token_endpoint_auth_method":"none"}`; `400 invalid_redirect_uri` for a URI that is neither `https` nor loopback `http` |
| `GET /_ankka/sign-in<path>/authorize?response_type=code&client_id&redirect_uri&code_challenge&code_challenge_method=S256&state&resource&scope?` | | `303` to the service's issuer's `authorization_endpoint` (the broker's client id, `redirect_uri=…/callback`, `scope=openid`, `state`, `nonce`); `400` naming the parameter for a missing or unregistered one, as a page, never a redirect to an unregistered URI |
| `GET /_ankka/sign-in<path>/callback?code&state` | | exchanges the code at the issuer, verifies the `id_token`; `303` to the client's `redirect_uri` with `code` and the client's `state`; on the issuer's `error`, `303` to the client with `error=access_denied` |
| `POST /_ankka/sign-in<path>/token` (form) | `grant_type=authorization_code&code&code_verifier&client_id&redirect_uri&resource` or `grant_type=refresh_token&refresh_token&client_id` | `200`, `Cache-Control: no-store`, `{"access_token","token_type":"Bearer","expires_in":3600,"refresh_token"}`; `400 invalid_grant` for a used, expired or mismatched code or verifier; `400 invalid_client` for an unknown client; `429 slow_down` with `Retry-After` from a per-node `TokenBucket` as the control plane's token route has |
| `GET /_ankka/sign-in<path>/jwks.json` | | `{"keys":[…]}`, the service's broker public keys |

All of these are served by the service on its HTTP port, so they are reachable at the derived
hostname and every custom hostname, through the gateway, and `/_ankka/sign-in` is reserved as
`/_ankka/health` is. A service that has not opted in has none of them (`404`).

## The access token the broker mints

```
{"alg":"RS256","kid":"20261019T…","typ":"JWT"}
{"iss":"https://cart.shop.ankka.example/_ankka/sign-in/mcp","sub":"ada","aud":"https://cart.shop.ankka.example/mcp",
 "iat":…,"exp":…,"jti":"…","typ":"Bearer","sign_in_issuer":"customers","name":"Ada","email":"ada@example.com"}
```

Accepted at the endpoint whose path `aud` names, signed by this service's broker keys; refused
everywhere else, including every other service.

## What is on no wire

- A client key's secret: in no log line, span, report, listing or descriptor; the key's id is on
  the span and in the listing.
- A person's password: the broker never receives one; the issuer's login page is the issuer's.
- A token passed on: a tool's handler receives a `Principal`, never the bearer; a call it makes to
  another service carries the caller's certificate and the trace, as today.
