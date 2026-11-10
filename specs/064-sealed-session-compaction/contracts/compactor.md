# Contract: what the session compactor does, as a service's developer and operator see it

No API is added or changed. `CompactionSettings`, `Summariser`, `AgentRuntime.withCompaction`,
`SessionCompactor.compacted` and `SessionCompactor.failures` keep their signatures.

## When a session is compacted

After a turn that ends with an AI message, for a session started with a data subject or with none:

1. the session's text is over `maxHistoryBytes`, and
2. at least `minMessagesToCompact` messages lie beyond the `keepRecentMessages` most recent, and
3. the summary is shorter than the messages it replaces.

Then the oldest messages are replaced by one summary. Otherwise nothing is written.

## What is kept of a summary

| Session started with | The summary in the journal and the snapshot |
|---|---|
| no data subject | plain, as before |
| a data subject | sealed under the subject's key; erased with it |

## When nothing happens, and it is not a failure

- the data subject was erased, at any point before the summary is written;
- the session is under the size, or has too few messages beyond those kept;
- the event is not a turn's AI message (the compactor's own summary included).

## When the keyring cannot be reached

The compactor waits and retries the event, as every consumer of personal fields does. Nothing is
recorded in `SessionCompactor.failures`. The session is compacted once the keyring answers.

## A failed compaction

A failure reading the session, summarising, or writing the summary:

- adds `"<session id>: <message>"` to `SessionCompactor.failures` (unchanged);
- logs one line at `WARN` from the logger `com.thinkmorestupidless.ankka.agent.SessionCompactor`:

  ```text
  compaction of session '<session id>' failed: <kind>
  ```

  with no stack trace, where `<kind>` is the failure's class name, and for a `CommandError` its
  code in brackets. The line holds no text of the conversation, of the summary or of a model
  provider's response;
- does not stop the consumer: the next session is considered.

## What leaves the service

The summariser's model is sent the messages being replaced. For a session started with a data
subject that is the person's conversation, and it is beyond an erasure, as what the session sent to
its own model is. Nothing else leaves: no log line, trace attribute or metric carries the text.
