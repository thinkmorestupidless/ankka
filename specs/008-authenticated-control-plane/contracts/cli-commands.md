# Contract: CLI commands

Existing behaviour that does not change: settings resolve flags → environment → file; exit codes
`0` ok, `1` failed, `2` misused; no credential is ever printed in either output format; the
project comes from `--project`/`ANKKA_PROJECT`/`config set project`.

## Credential resolution, every command

1. `--token` / `ANKKA_TOKEN` — presented verbatim as the bearer. No login attempted, no refresh.
2. Else the saved login for the effective `url` in `credentials.json`: the access token if more
   than 30 s from expiry, otherwise a refresh; the refreshed pair is saved.
3. Else, or if the refresh is refused: `error: not logged in to <url>; run 'ankka login'`, exit 1.

A 401 from the control plane with a saved login present is reported as
`error: <url> rejected the login; run 'ankka login'` and exits 1 — once, with no retry loop.
A 403 is `error: not permitted: <server message>`, exit 1. A 409 naming a disabled organization
is printed as the server sent it.

## `ankka login`

```
ankka login [--url …] [--no-browser]
```

1. `GET <url>/auth` → issuer, client id. Fails with exit 1 and the reason if the control plane is
   unreachable or has no `/auth` (an older control plane).
2. `GET <issuer>/.well-known/openid-configuration` with the configured trust root (`config set
   ca`), taking `device_authorization_endpoint`, `token_endpoint` and, if present,
   `revocation_endpoint`.
3. `POST device_authorization_endpoint` with `client_id`, `scope=openid offline_access`.
4. Prints:
   ```
   To log in, open  https://auth.example.test:8443/realms/ankka/device
   and enter the code  WDJB-MJHT
   ```
   using `verification_uri_complete` for the browser attempt when present, ignoring failure to
   open one. `--no-browser` skips the attempt.
5. Polls `token_endpoint` with `grant_type=urn:ietf:params:oauth:grant-type:device_code` every
   `interval` seconds (+5 on `slow_down`), until `expires_in` elapses (`error: the code expired
   before the login completed`, exit 1) or a token arrives.
6. Saves `{issuer, clientId, refreshToken, accessToken, expiresAt}` under the URL, then calls
   `GET /auth/whoami` and prints `logged in to <url> as <email or subject>`.

## `ankka logout [--all]`

Revokes the refresh token at `revocation_endpoint` if discovery named one (failure is reported
and not fatal), deletes the entry for the URL, or every entry with `--all`. Exit 0 either way when
the file ends in the intended state.

## `ankka whoami`

Prints subject, name, email (with `verified`/`unverified`), whether platform admin, and a table of
organizations with roles. `-o json` prints `Whoami`.

## `ankka organizations …`

Unchanged: `list`, `get`, `create`, `rename`, `delete`. `list` and `get` gain a `ROLE` column and
show `disabled` in a `STATE` column (`active`/`disabled`).

New:

```
ankka organizations members list <org>
ankka organizations members add <org> --email <email> [--role owner|member]     # default member
ankka organizations members remove <org> <subject>
ankka organizations members role <org> <subject> --role owner|member
ankka organizations invitations revoke <org> <email>
ankka organizations disable <org>          # platform admin
ankka organizations enable  <org>          # platform admin
ankka organizations members repair <org> --subject <subject> --role owner   # platform admin
```

`members list` prints members (`SUBJECT  ROLE  EMAIL  SINCE`) then pending invitations
(`EMAIL  ROLE  INVITED  BY`). `-o json` prints `MembersResponse`.

## `ankka services …`

Unchanged commands. `list` and `get` show `Suspended` as a lifecycle word. New:

```
ankka services history <name>     # WHEN  KIND  GEN  BY  (admin)
```

`BY` is the actor's display or subject; `(admin)` marks administrative actions; pre-feature
entries show `-`.

## `ankka config …`

`set token` remains and now means "present this bearer as given". `config get` shows whether a
saved login exists for the URL (`login: saved` / `login: none`) and never its contents.

## Exit codes and messages a script can rely on

| situation | stderr starts with | exit |
|---|---|---|
| not logged in | `error: not logged in` | 1 |
| login rejected | `error: … rejected the login` | 1 |
| forbidden | `error: not permitted` | 1 |
| disabled organization | `error: organization '…' is disabled` | 1 |
| usage | decline's help | 2 |
