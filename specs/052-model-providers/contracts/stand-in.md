# Contract: the loopback stand-in (`ModelStandIn`)

A server in the agent module's main scope that the offline suites point providers at. It speaks
the three shapes from one script so a test states what the model says once.

## Starting

```scala
val standIn = ModelStandIn.start()        // 127.0.0.1:0, a virtual-thread executor
standIn.address                           // "http://127.0.0.1:<port>"
standIn.close()
```

Paths served: `POST /v1/messages` (Anthropic Messages), `POST /v1/responses` (OpenAI Responses),
`POST /v1/chat/completions` (OpenAI Chat Completions). Any other path answers 404. A request with
`"stream": true` is answered as SSE in that shape's event vocabulary; the Responses shape emits
`response.output_text.delta` events and a terminal `response.completed`; Chat Completions emits
chunks and, when `stream_options.include_usage` was asked, a final usage chunk; Messages emits the
`message_start … message_stop` sequence.

## Scripting

```scala
standIn.answer(ModelResponse("Paris"))                                   // consumed in order
standIn.answer(ModelResponse("", Vector(ToolCall("x", "lookup", args)), StopReason.ToolUse))
standIn.answer(ModelResponse("", stopReason = StopReason.Refusal, refusalReason = Some("outside policy")))
standIn.answer(ModelResponse("done", usage = TokenUsage(10, 5, 3, 0)))
standIn.reportNoUsage()                   // the next answers omit `usage`
standIn.rejectSetting("temperature")      // 400 naming the field when the request carries it
standIn.failNext(status = 429, body = "rate limited")
standIn.hang()                            // never answers until released
standIn.requireReasoningReplay()          // Responses: a function_call_output not preceded by the
                                          // reasoning item this server issued is a 400
```

With nothing scripted the server answers 418, so a test that forgot a reply fails loudly, as
`TestModelProvider` does.

## Rendering per shape

| ankka value | Messages | Responses | Chat Completions |
|---|---|---|---|
| text | `text` block | `message` item, `output_text` part | `choices[0].message.content` |
| tool call | `tool_use` block (`id`, `name`, `input`) | `function_call` item (`id: fc_…`, `call_id`, `name`, `arguments` string) preceded by a `reasoning` item (`id: rs_…`, `encrypted_content`) | `tool_calls[]` (`id`, `function.name`, `function.arguments` string) |
| refusal | `stop_reason: refusal`, `stop_details.explanation` | `refusal` content part | `message.refusal`, `finish_reason: stop` |
| length stop | `stop_reason: max_tokens` | `status: incomplete`, `incomplete_details.reason: max_output_tokens` | `finish_reason: length` |
| usage | `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens` | `input_tokens`, `input_tokens_details.cached_tokens`, `output_tokens` | `prompt_tokens`, `prompt_tokens_details.cached_tokens`, `completion_tokens` |

## Recording

```scala
standIn.requests: Vector[Received]        // every request, in order
final case class Received(path: String, headers: Map[String, String], body: Json)
```

Tests read the body as ankka's `Json` and assert on the shape: the model name, the `messages` or
`input` array, the tools, which settings were sent (`reasoning`, `reasoning_effort`,
`temperature`, `max_output_tokens`, `max_completion_tokens`, `thinking`, `output_config`), the
`Authorization` header (the key, or the placeholder `none`), and for the Responses shape whether a
`reasoning` item precedes each `function_call` item whose output follows.

## What it does not do

It does not interpret prompts. It never decides what a model would say; the script does. It is not
a conformance target and is not published.
