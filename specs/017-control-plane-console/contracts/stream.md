# Contract: update streams

Two resource routes under the mount, `text/event-stream`, authenticated by the session cookie alone.
Every `data` field is one JSON document on one line.

## `GET <mount>/stream/services/:projectId/:name`

Query: `status` (present by default), `logs` (present to follow), and when following:
`instance`, `previous`, `tail` (default 200), `since` (fixed at 4 seconds server-side; the query's
value is ignored and documented as such).

```
event: status
data: {"name":"cart","projectId":"checkout","lifecycle":"UpdateInProgress",…}   // ServiceStatus

event: logs
data: {"instance":"cart-6d9…","lines":["…","…"]}

event: logs
data: {"instance":"cart-6d9…","error":"container not found"}

: keepalive

event: session-ended
data: {"reason":"sign-in-required"}

event: server-closing
data: {}
```

Rules:

- The first `status` is sent immediately on open; afterwards only when the `ServiceStatus` differs
  structurally from the last sent.
- `logs` carries only lines not already sent (R10); an instance's read error is its own event and
  does not end the stream.
- Reads happen every 2 seconds per open stream, each with the session's current access token; a
  refresh that fails ends the stream with `session-ended`, after which the page navigates to
  sign-in with `returnTo` the current page.
- `server-closing` precedes shutdown; the browser's `EventSource` reconnects to another instance.
- A `404` from the control plane for the service sends `event: gone` and closes.

## `GET <mount>/stream/projects/:projectId`

```
event: services
data: [ … ServiceStatus … ]
```

Sent on open and whenever the listing differs from the last sent. Same session, keepalive, closing
and `gone` rules.

## Client behaviour

Pages open a stream in an effect after hydration, so a browser with scripts disabled never opens
one and shows the state from its last request (FR-004a). `status` and `services` events replace the
page's loader data in place; `logs` events append and trim to `tail`. A page with a paused logs
follow closes the stream and reopens it without `logs` on resume.
