# Contract: what `JevProvider` sends and accepts

The provider's API as documented at `docs.typesafe.ai` on 2026-09-30, and the adapter's behaviour
against it. `JevProviderSuite` holds the adapter to this file against a local stand-in;
`JevProviderLiveSuite` holds this file to the real endpoint when a key is present.

## Request

```
POST {baseUrl}/v1/systemone
Authorization: Bearer {key}
Content-Type: application/json
```

```json
{
  "model": "jev-1.13.0",
  "state": "I was charged twice for order A-104. Please refund one of them today.",
  "questions": {
    "route": {
      "type": "choice",
      "instructions": "Which team should handle this ticket?",
      "criteria": {
        "billing": "Payments, invoicing, refunds",
        "technical": "Bugs, outages, integrations",
        "sales": "Pricing, upgrades, new accounts"
      }
    },
    "frustration": {
      "type": "score",
      "instructions": "How frustrated is the customer?",
      "criteria": ["Calm, stating facts", "Frustrated but civil", "Angry, strong language", "Abusive"]
    },
    "refund": {
      "type": "noul",
      "instructions": "The customer asks for a refund"
    }
  }
}
```

| ankka | wire |
|---|---|
| `JudgmentState.Text(s)` | `"state": "<s>"` |
| `JudgmentState.Structured(json)` | `"state": <json>` — the value itself, not a string holding it |
| question id | the key under `questions` and under `answers` |
| `ChoiceQuestion` | `type: "choice"`, `criteria` an object of option key → description, in declaration order |
| `ScoreQuestion` | `type: "score"`, `criteria` an array of level descriptions, in order |
| `YesNoQuestion` | `type: "noul"`; `criteria: {"true": yes, "false": no}` only when described |
| the adapter's model | `model` |

One HTTP request per attempt; its timeout is what remains of `JudgmentRequest.timeout`.

## Response (200)

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "route": {"type": "choice", "choice": "billing",
              "probabilities": {"billing": 0.88, "technical": 0.08, "sales": 0.04}, "confidence": 0.82},
    "frustration": {"type": "score", "score": 1.43, "confidence": 0.35,
                    "legend": {"0": "Calm, stating facts", "1": "Frustrated but civil", "2": "Angry, strong language", "3": "Abusive"},
                    "probabilities": {"0": 0.0, "1": 0.57, "2": 0.43, "3": 0.0}},
    "refund": {"type": "noul", "noul": 0.95}
  },
  "usage": {"input_tokens": 296, "output_tokens": 20}
}
```

| wire | ankka |
|---|---|
| `model` | `Judgment.model` |
| `usage.input_tokens`, `usage.output_tokens` | `Judgment.usage` |
| choice: `choice`, `probabilities`, `confidence` | `Stored.Choice(key, probabilities, confidence)` |
| score: `score`, `probabilities` keyed `"0"…"n-1"`, `confidence` | `Stored.Score(score, probabilities as a vector in level order, confidence)`; `legend` is ignored |
| noul: `noul` | `Stored.YesNo(probability)` |

The adapter fails the judgment with `JudgmentFailed("jev", …)` when the body is not JSON, when
`model`, `answers` or `usage` is missing, or when an answer it must convert lacks a field it needs
or a score's probability keys are not the level numbers. Anything it *can* convert it hands to the
platform, whose verification (`data-model.md` §3) refuses the rest — a missing answer, another
kind, an option not offered — naming the question. Fields it does not know are ignored.

## Statuses

| Status | Meaning | Adapter |
|---|---|---|
| 200 | answered | convert |
| 401 | key missing or invalid | fail at once: `jev: 401: <body>` |
| 422 | the request is invalid | fail at once: `jev: 422: <body>` |
| 408, 429, 5xx (529 is "overloaded") | transient | retry |
| connection refused, reset, DNS failure | transient | retry |
| any other status | not retried | fail: `jev: <status>: <body>` |
| no response in the time remaining | | fail with `timedOut = true`: `jev did not answer within <timeout>` |

## Retrying

- The wait before attempt *n+1* is the response's `retry-after-ms` header, else its `Retry-After`
  header in seconds, else 250 ms × 2ⁿ⁻¹.
- A retry is made only if its wait ends before the deadline. Otherwise the last failure is
  reported — for a 429, `jev: 429: <body>`, not a timeout, because that is what happened.
- There is no limit on attempts other than the deadline.

## What never leaves the adapter

The key appears in the `Authorization` header and nowhere else: not in `toString`, not in any
`JudgmentFailed` message or cause, not in a log line. An error message is built from the status
and the response body only. The state is sent to the provider and is not logged or put in an
error.

## Not part of this contract

Rate limits (the provider's published figures disagree and are stated to change; the adapter
reacts to 429), token limits (the provider answers 422), the shape of an error body (reported
verbatim), and any address that reshapes the request — another path, another envelope — which is
another adapter.
