# Contract: control plane routes the console consumes

The parity table SC-002 checks: every route in `reference/control-plane-api.md` under organizations,
projects and services, the page that reads it and the operation that posts to it. The console adds
no route to the control plane. Bodies are the wire types in `data-model.md`.

| Method | Route | Page / operation | Notes |
|---|---|---|---|
| `GET` | `/auth` | startup | issuer and audience |
| `GET` | `/auth/whoami` | front page, layout | an administrator's list is empty; the listing below is what they see |
| `GET` | `/organizations` | front page | every organization for an administrator |
| `GET` | `/organizations/{id}` | organization page | `OrganizationDetail` |
| `POST` | `/organizations/{id}` | `organization.create` | then redirect to the organization page, read by id (FR-021) |
| `PUT` | `/organizations/{id}/name` | `organization.rename` | |
| `DELETE` | `/organizations/{id}` | `organization.delete` | `409` shown verbatim when not empty |
| `GET` | `/organizations/{id}/members` | members page | |
| `POST` | `/organizations/{id}/members` | `member.invite` | |
| `DELETE` | `/organizations/{id}/members/{subject}` | `member.remove` | |
| `PUT` | `/organizations/{id}/members/{subject}/role` | `member.role` | |
| `DELETE` | `/organizations/{id}/invitations/{email}` | `invitation.withdraw` | |
| `POST` | `/organizations/{id}/members/{subject}/repair` | `member.repair` | administrators |
| `GET` | `/organizations/{id}/tokens` | tokens page | |
| `POST` | `/organizations/{id}/tokens` | `token.create` | the secret is rendered on the action's own response, never after |
| `DELETE` | `/organizations/{id}/tokens/{tokenId}` | `token.revoke` | |
| `POST` | `/organizations/{id}/disable` | `organization.disable` | administrators |
| `POST` | `/organizations/{id}/enable` | `organization.enable` | administrators |
| `PUT` | `/organizations/{id}/quota` | `organization.quota.set` | administrators |
| `DELETE` | `/organizations/{id}/quota` | `organization.quota.clear` | administrators |
| `GET` | `/projects` | organization page | filtered by organization client-side, as the CLI does |
| `GET` | `/projects/{id}` | project page | |
| `POST` | `/projects/{id}` | `project.create` | then redirect to the project page |
| `PUT` | `/projects/{id}/name` | `project.rename` | |
| `DELETE` | `/projects/{id}` | `project.delete` | |
| `PUT` | `/projects/{id}/registry` | `registry.set` | the password is posted once and never rendered |
| `DELETE` | `/projects/{id}/registry` | `registry.clear` | |
| `GET` | `/services/{projectId}` | project page, listing stream | |
| `GET` | `/services/{projectId}/{name}` | service page, service stream | |
| `PUT` | `/services/{projectId}/{name}` | `service.apply` | body forwarded as the text the person gave; `400` problems shown beside it |
| `POST` | `/services/{projectId}/{name}/pause` | `service.pause` | |
| `POST` | `/services/{projectId}/{name}/resume` | `service.resume` | |
| `POST` | `/services/{projectId}/{name}/restart` | `service.restart` | |
| `POST` | `/services/{projectId}/{name}/expose` | `service.expose` | |
| `POST` | `/services/{projectId}/{name}/unexpose` | `service.unexpose` | |
| `GET` | `/services/{projectId}/{name}/logs` | logs page, service stream | `instance`, `previous`, `tail`, `since` |
| `GET` | `/services/{projectId}/{name}/history` | service page | |
| `DELETE` | `/services/{projectId}/{name}` | `service.delete` | then redirect to the project page |

## Status handling, everywhere

| Status | The console |
|---|---|
| `401` | The token was refused: refresh once; if still `401`, end the session and send to sign in with `returnTo`. |
| `403` | Show the body's `error` beside the thing refused. The control is still rendered unless the host hid it. |
| `404` | For a page's subject: "you no longer have access" and a link to the front page. For a listing item: drop it. |
| `409` | Show the body's `error` beside the form, with the form's values kept. |
| `400` | Show the body's `error`; for `service.apply`, beside the descriptor. |
| `503`, `504` | "The control plane did not answer"; the request may be retried; no session change. |

The parity test reads the route table from `docs/reference/control-plane-api.md` (the generated
block) and asserts each row has a client method and, for a mutation, an operation name; and the
Playwright suite's fake control plane records which routes were exercised.
