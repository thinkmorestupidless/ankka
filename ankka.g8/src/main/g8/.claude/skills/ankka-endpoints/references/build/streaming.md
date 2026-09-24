# Streaming responses

> Stream an agent's reply token by token to a caller and over HTTP as server-sent events, and know what streaming changes about guardrails and sessions.

Source: https://docs.ankka.cloud/build/streaming/
An agent handler can stream its reply: the caller receives the model's text as it is generated rather
than waiting for the whole answer. Streaming is declared on the handler, consumed through the component
client, and served over HTTP as server-sent events (SSE). Only agents stream. An entity or a workflow
refuses a streaming call rather than ignoring it, because a caller waiting for tokens that never come
would wait forever.

## Declaring a streaming handler

In Scala, a streaming handler returns a `StreamEffect`, built with `thenStream()`, and is registered with
`stream` rather than `command`:

```scala
/** Streams the reply token by token, tools and all. */
def chat(question: String): StreamEffect =
  effects
    .systemMessage(WeatherAgent.SystemMessage)
    .userMessage(question)
    .tools(WeatherAgent.getWeather)
    .thenStream()
```

```scala
val chat = stream("chat")(_.chat)
```

The two registrations are separate on purpose. A `command` is called with `.call(...)` and answers once;
a `stream` is called with `.stream(...)` and answers many times. Keeping them apart means a caller cannot
await one value from a handler that produces many, or the reverse; the compiler refuses it.

In Python, the decorator is `@stream` and the handler returns an ordinary `AgentEffect`. The assistant in
[Agents](agents.md#an-agent-in-python) declares one:

```python
@stream("chat")
def chat(self, question: str) -> AgentEffect[str]:
    return self._describe(question)
```

## Consuming a stream

Through the Scala component client, `stream` returns a Pekko Streams `Source[String, NotUsed]` of text
chunks:

```scala
val tokens: Source[String, NotUsed] =
  componentClient.forAgent(SessionId("s-1")).stream(WeatherAgent.chat)("Will it rain in Lisbon?")
```

Nothing is sent until the source is run. Tokens are pushed straight from wherever the session is hosted
in the cluster to wherever the source was run, with nothing buffering the whole reply. A consumer that
falls more than 1024 chunks behind fails the stream rather than silently dropping text.

In Python, `stream` is an async iterator:

```python
async for token in client.for_agent("assistant", "s-1").call("chat").stream("Will it rain?"):
    print(token, end="")
```

A refusal — a guardrail, an error effect, a failed model call — ends the stream with an error: a
`CommandError` in both languages.

## Serving a stream over HTTP

An endpoint serves a stream with `sse`, which answers `GET` as `text/event-stream`. In Scala the handler
returns the `Source`:

```scala
sse("/{session}") { (session: String) =>
  client
    .forAgent(SessionId(session))
    .stream(WeatherAgent.chat)("What is the weather?")
}
```

`sseBody` is the `POST` form, taking a path parameter and a decoded body. In Python, an `@sse` route is an
async generator:

```python
@post("/ask/{session}")
async def ask(self, session: str, question: str) -> str:
    return await self.client.with_metadata(self.request.metadata).for_agent("assistant", session).call("ask").invoke(question, reply=str)

@sse("/chat/{session}")
async def chat(self, session: str) -> AsyncIterator[str]:
    question = next((v for k, v in self.request.query if k == "q"), "")
    async for token in self.client.with_metadata(self.request.metadata).for_agent("assistant", session).call("chat").stream(question):
        yield token
```

Read query parameters and headers while building the stream, not inside it. In Scala the handler only
builds the `Source`; the HTTP server pulls its elements later, on another thread, where the request is no
longer available. See [HTTP endpoints](http-endpoints.md).

## Every event is a JSON string

Each server-sent event's `data` field holds one chunk, encoded as a JSON string:

```text
data:"Lisbon is"

data:" mild today."
```

A client decodes each `data` field with a JSON parser. Raw text is not safe in an SSE `data` field: the
protocol strips one leading space from a field's value, and a newline inside a chunk ends the field and
splits the chunk into two events. Both corrupt text silently, and only on text a model happened to
produce, so the platform encodes every chunk rather than leave it to chance.

## Every turn streams

When the model says something before calling a tool — "let me check the forecast" — that text is
streamed immediately, before the tool runs, and the model's answer after the tool streams too. Withholding
everything until the last turn would leave a reader looking at nothing while tools run, which is what
makes a streaming interface feel broken.

## The session is held for the stream

A session handles one request at a time, and a stream is one request for as long as it lasts. A second
call on the same session waits until the stream has finished, so one conversation cannot interleave two
replies. Use separate sessions for independent conversations.

## Guardrails on a stream

**Output guardrails cannot un-send a token.** On a streaming handler they run after the reply has been
delivered. A rejection still fails the stream and still keeps the reply out of memory, but the reader has
already seen the text. Use input guardrails for anything that must never be shown, since those run before
the model is called and a rejection there sends nothing.
