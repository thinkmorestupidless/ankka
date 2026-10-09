# Contract: grants

Held by `features/cross-project/{route-grants,topic-grants,acceptance,listing}.feature`.

## The granting project

```text
POST   /projects/{id}/grants                    {"grantee": "service:payments/merchant",
                                                 "target": {"kind": "route", "service": "wallet",
                                                            "method": "POST", "path": "/v1/wallets/{player}/{currency}/deposits"}}
                                                → 200 GrantDetail (the existing one when the pair is live)
GET    /projects/{id}/grants                    → [GrantDetail]   state, effect, who and when
GET    /projects/{id}/grants/received           → [ReceivedGrantDetail]   what this project's services hold or are offered
DELETE /projects/{id}/grants/{grantId}          withdraw while pending, revoke while accepted → 204
```

Targets on the wire: `{"kind":"route","service","method","path"}`, `{"kind":"method","service",
"method":"WalletService/Deposit"}`, `{"kind":"topic","topic","right":"consume"|"produce",
"decrypt":false}`, `{"kind":"erasure"}`.

Who: `POST` and `DELETE` need an owner of the project's organization (`requireOwner`); `GET` a
member. A deploy token is a member and is refused the writes. A non-member is told there is no
project.

`effect` on a `GrantDetail`: `in effect`, or `pending`, `declined`, `withdrawn`, `revoked`,
`relinquished`, `lapsed`, `route not seen`, `route not grantable`, `rollout needed`, `broker not
exposed`.

## The grantee organization

```text
GET  /organizations/{id}/grants                          → [ReceivedGrantDetail]   offered to or held by its machines and its projects' services
POST /organizations/{id}/grants/{grantId}/accept         pending → accepted      → 204
POST /organizations/{id}/grants/{grantId}/decline        pending → declined      → 204
POST /organizations/{id}/grants/{grantId}/relinquish     accepted → relinquished → 204
```

Who: `POST` needs an owner of the grantee organization; the grant must be offered to or held by
that organization (a machine registered on it, or a service of a project it owns), else 404. A
deploy token is refused. A member lists.

## CLI

```bash
ankka projects grants make  service:payments/merchant route wallet POST /v1/wallets/{player}/{currency}/deposits -p spinvibe
ankka projects grants make  service:payments/merchant method wallet WalletService/Deposit -p spinvibe
ankka projects grants make  machine:affiliates/network topic affiliates.attribution consume -p spinvibe
ankka projects grants make  service:payments/merchant topic casino.players consume --decrypt -p spinvibe
ankka projects grants make  service:payments/merchant erasure -p spinvibe
ankka projects grants list -p spinvibe              # ID, GRANTEE, TARGET, STATE, EFFECT, GRANTED BY, AT
ankka projects grants received -p payments          # ID, FROM, TARGET, STATE, RETENTION
ankka projects grants withdraw <id> -p spinvibe     # a pending one
ankka projects grants revoke <id> -p spinvibe       # an accepted one
ankka organizations grants list affiliates
ankka organizations grants accept|decline|relinquish affiliates <id>
```

## Refusals (400 unless said)

- a grantee or target outside `GrantRules` (naming the rule);
- a topic the project has not declared: 404, `project 'spinvibe' has not declared the topic
  'casino.sessions'`;
- a grantee project id that was never created: 404 (an endpoint check);
- a grantee service of the project itself: `a project's own services need no grant`;
- `decrypt` on a produce grant;
- a transition outside the lifecycle: 409 naming the state (`grant … is accepted; withdraw applies
  to a pending grant`).

## The record

Every change is an event on the granting project with the owner's attribution; the grantee side's
copy (`GrantRecorded`) is derived by the `GrantMirror` consumer. `ankka projects history` on either
project, and the organization's history, show each change with who made it. Nothing in either holds
a credential; `EventCompatibilitySuite` asserts no event has a `secret` or `token` field.
