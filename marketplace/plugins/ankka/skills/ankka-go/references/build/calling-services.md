# Calling other services

> Call another service's routes as your own service — from an endpoint, a workflow step, a consumer, a timed action or an agent's tool, in any of the six languages — so that service's access rules can admit yours by name.

Source: https://docs.ankka.cloud/build/calling-services/
A service calls another service of the platform by its name, and the call is made **as the calling
service**. On the platform the call presents the calling service's certificate, so the service called
knows who is calling and its access rules can admit exactly the services that may call it — by name, and
nobody else. A route that only a payment adapter should reach admits that adapter and refuses the
internet, every other service, and every other caller.

The call is addressed by name, never by an address: a service of the same project by its name, a service
of another project by project and name. A service of another project admits the caller only on a route
its project has granted the calling service, and only where that route's access rule admits granted
callers; see [Cross-project access](../platform/cross-project-access.md).

## Who may call another service

Every component whose handlers already run ordinary sequential code may make a call: an endpoint, a
workflow's step, a consumer, a timed action, an agent's tool and an autonomous agent's tool, in Scala,
Java, Kotlin, Python, TypeScript, Rust and Go.

An **entity** and a **view** may not. Their contexts have no client for other services at all: in Scala
reaching for one does not compile, in Java and Kotlin an entity and a view have no `services()` method,
in TypeScript the property does not exist on the type, and in Python the attribute does not exist. In Go `ankka.CallService(ctx, …)` compiles anywhere a context is at hand. An entity handles one command at a time for each id, so a call made inside
a handler would hold every other command for that entity behind another service. A Python, TypeScript or Go
process that tries anyway is refused by the runtime, which reads which handler is calling and refuses an
entity's.

A workflow's command handlers share their context with its steps, so a workflow's client answers only
inside a step; a call from a command handler is refused with a message saying so.

A service written in Rust is a WebAssembly module the runtime loads, and the rule is the same with one
difference in how it is kept. A handler's context answers `None` for the client in an entity, a view and
a workflow's command. A module that makes the call from one of those anyway is stopped by the runtime:
the call does not return, the handler's caller is answered with a fault naming the handler, and an
entity keeps the state it had. See the [Rust SDK](../reference/rust-sdk.md#calling-other-services).

## Calling from an endpoint

An endpoint that calls another service and answers what it answered, in Scala; the tabs below show the
same in each other language, through `services().of(name)` in Java and Kotlin:

```scala
// Calls another service in this project, as this service: `/callers/whoami` unless `path` says
// otherwise. The answer is what that service answered, and it saw this one as the caller.
get("/call/{service}") { (service: String) =>
  val path = query.optional[String]("path").getOrElse("/callers/whoami")
  try services(service).getText(path)
  catch case e: ServiceUnresolvable => throw HttpProblem(503, e.getMessage)
}
```

**Python**

```python
class CallingEndpoint(Endpoint):
    """Calls another service of this project, as this service: the answer is what that service
    answered, and the service it called saw this one as the caller."""

    prefix = "/calling"
    acl = Acl.allow_callers(Callers.self_)

    @get("/call/{service}")
    async def call(self, service: str) -> str:
        path = self.request.query_param("path") or "/callers/whoami"
        try:
            return await self.services(service).get_text(path)
        except ServiceUnresolvable as unresolvable:
            raise HttpProblem(503, str(unresolvable)) from unresolvable
```

**TypeScript**

```ts
/**
 * Calls another service of this project, as this service: the answer is what that service answered,
 * and the service it called saw this one as the caller.
 */
export class CallingEndpoint extends Endpoint {
  static readonly prefix = "/calling"
  static readonly acl = Acl.allowCallers(Callers.self)

  static readonly routes = {
    call: get("/call/{service}", s.string, async (ep: CallingEndpoint, req) => {
      const path = req.query.get("path") ?? "/callers/whoami"
      try {
        return await ep.services.service(req.params.service).getText(path)
      } catch (e) {
        if (e instanceof ServiceUnresolvable) throw new HttpProblem(503, e.message)
        throw e
      }
    }),
  }
}
```

**Java**

```java
public class Neighbour extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private ServiceClient scalaCart() {
    return services().of("scala-cart");
  }

  /** The Scala cart's entity, read into the Java cart's record. */
  @Get("/neighbour/carts/{cartId}")
  public Cart cart(String cartId) {
    return scalaCart().get("/carts/" + cartId, Cart.class);
  }

  @Post("/neighbour/carts/{cartId}/items")
  public Done addItem(String cartId, @Body LineItem item) {
    scalaCart().post("/carts/" + cartId + "/items", item, Void.class);
    return Done.done();
  }

  /** Who the Scala cart saw calling. */
  @Get("/neighbour/whoami")
  public String whoami() {
    return scalaCart().getText("/callers/whoami");
  }
}
```

**Kotlin**

```kotlin
// A call to another service, as the case asks for it: `service`, `method` and `path` from the
// query, the body and every `X-Conformance-*` header sent on, and two headers no handler may
// send, added to show they never arrive.
@Post("/conformance/service-call")
fun serviceCall(@Body body: String): ServiceCallRecord {
    val service = request().requiredQuery("service")
    val method = request().requiredQuery("method")
    val path = request().requiredQuery("path")
    val headers = buildMap {
        for ((name, value) in request().headers()) if (name.lowercase().startsWith("x-conformance-")) put(name, value)
        put("X-Ankka-Caller", "ankka://elsewhere/impostor")
        put("Host", "elsewhere")
    }
    val client = services().of(service)
    return try {
        if (request().queryOrNull("mode") == "typed") {
            ServiceCallRecord("response", 200, body = client.getText(path, headers))
        } else {
            val bytes = if (body.isEmpty()) null else body.toByteArray()
            val contentType = if (body.isEmpty()) null else request().headerOrNull("Content-Type")
            val answer = client.request(method, path, bytes, contentType, headers)
            val named = answer.headers.firstOrNull { it.key.equals("x-answer", ignoreCase = true) }?.value ?: ""
            ServiceCallRecord("response", answer.status, answer.contentType, answer.text(), named)
        }
    } catch (e: ServiceCallFailed) {
        ServiceCallRecord("failed", e.status(), body = e.body())
    } catch (e: ServiceUnresolvable) {
        ServiceCallRecord("unresolvable", message = e.message.orEmpty())
    } catch (e: ServiceIdentityMismatch) {
        ServiceCallRecord("mismatch", message = e.message.orEmpty())
    } catch (e: ServiceUnanswered) {
        ServiceCallRecord("unanswered", message = e.message.orEmpty())
    } catch (e: CommandException) {
        ServiceCallRecord("refused", message = e.problem().detail)
    }
}
```

**Go**

The Go reference's route makes the call a request names, and records what came back:

```go
// serviceCall makes the call the case asks for: service, method and path from the query, the body
// and every X-Conformance-* header sent on, and two headers no handler may send, the caller's and
// the host, added to show they never arrive.
func serviceCall(ctx context.Context, req *ankka.Request, body string) (ServiceCallRecord, error) {
	q := req.Query
	headers := http.Header{}
	for name, values := range req.Headers {
		if strings.HasPrefix(strings.ToLower(name), "x-conformance-") {
			headers[name] = values
		}
	}
	headers.Set("X-Ankka-Caller", "ankka://elsewhere/impostor")
	headers.Set("Host", "elsewhere")
	call := ankka.ServiceRequest{Method: q.Get("method"), Path: q.Get("path"), Headers: headers}
	typed := q.Get("mode") == "typed"
	if typed {
		call.Method = http.MethodGet
	} else if body != "" {
		call.Body, call.ContentType = []byte(body), req.ContentType
	}
	res, err := ankka.CallService(ctx, q.Get("service"), call)
	var failed *ankka.ServiceError
	switch {
	case errors.As(err, &failed):
		outcome := map[ankka.ServiceFailureReason]string{
			ankka.ServiceUnresolvable: "unresolvable", ankka.ServiceIdentityMismatch: "mismatch", ankka.ServiceUnanswered: "unanswered",
		}[failed.Reason]
		return ServiceCallRecord{Outcome: outcome, Message: failed.Error()}, nil
	case err != nil:
		if r, ok := ankka.AsRefusal(err); ok {
			return ServiceCallRecord{Outcome: "refused", Message: r.Message}, nil
		}
		return ServiceCallRecord{}, err
	}
	// The typed client answers a body for a success and fails on any other status.
	if typed {
		if res.Status < 200 || res.Status > 299 {
			return ServiceCallRecord{Outcome: "failed", Status: res.Status, Body: string(res.Body)}, nil
		}
		return ServiceCallRecord{Outcome: "response", Status: 200, Body: string(res.Body)}, nil
	}
	return ServiceCallRecord{
		Outcome: "response", Status: res.Status, ContentType: res.ContentType, Body: string(res.Body),
		Answer: res.Headers.Get("X-Answer"),
	}, nil
}
```

## Calling from a workflow's step

A step asks another service to do something and goes on with the answer. The context's `services` is
the same client an endpoint has:

```scala
/**
 * Asks the PSP gateway service to start a payout, from a step: a step may call another service, as
 * this service, and goes on with the answer.
 */
final class PayoutWorkflow(context: WorkflowContext) extends Workflow[PayoutState]:

  def emptyState: PayoutState = PayoutState("", "")

  def start(amount: Int): Effect[Done] =
    effects
      .updateState(emptyState)
      .transitionTo(PayoutWorkflow.initiatePayout.withInput(amount))
      .thenReply(Done)

  def initiatePayout(amount: Int): StepEffect =
    val answer = context.services("psp-gateway").getText(s"/payouts?amount=$amount")
    stepEffects.updateState(currentState.copy(answer = answer)).thenEnd
```

**Python**

```python
class PayoutWorkflow(Workflow[PayoutState]):
    """Asks the PSP gateway service to start a payout, from a step, and goes on with the answer."""

    component_id = "payout"
    state_codec = json_codec(PayoutState, "payout-state")

    def empty_state(self) -> PayoutState:
        return PayoutState("")

    @command("start")
    def start(self, amount: int) -> WorkflowEffect[PayoutState, Done]:
        return self.effects.update_state(self.state).then_transition_to("initiate-payout", amount).then_reply(lambda _: DONE)

    @step("initiate-payout")
    async def initiate_payout(self, amount: int) -> WorkflowStepEffect[PayoutState]:
        answer = await self.services("psp-gateway").get_text(f"/payouts?amount={amount}")
        return self.step_effects.update_state(PayoutState(answer)).then_end()


def test_a_workflows_step_calls_another_service_and_goes_on_with_the_answer() -> None:
    kit = WorkflowTestKit.of(PayoutWorkflow, "pay-1")
    kit.workflow.services = ScriptedServices().answer("psp-gateway", lambda _: ScriptedServices.text("started"))
    kit.call("start", 25)
    kit.run_until_end()
    assert kit.state.answer == "started"
```

**TypeScript**

```ts
class PayoutWorkflow extends Workflow<string> {
  static readonly componentId = "payout"
  emptyState(): string {
    return ""
  }

  /** A step asks the PSP gateway service to start a payout, and goes on with the answer. */
  async initiatePayout(amount: number): Promise<string> {
    return this.services.service("psp-gateway").getText(`/payouts?amount=${amount}`)
  }
}

test("a workflow's step calls another service and goes on with the answer", async () => {
  const workflow = new PayoutWorkflow()
  const scripted = new ScriptedServices().answer("psp-gateway", (request) => ScriptedServices.text(`started ${request.path}`))
  workflow.services = scripted
  workflow._enterStep()
  assert.equal(await workflow.initiatePayout(25), "started /payouts?amount=25")
  assert.deepEqual(scripted.requests.map((r) => r.path), ["/payouts?amount=25"])
})
```

**Go**

A unit kit answers the call with `testkit.AnswerService`; a call to a service it was not told to
answer fails as unresolvable, naming it.

```go
type payout struct {
	Amount    int    `json:"amount"`
	Reference string `json:"reference"`
}

// A step asks the PSP gateway service to start a payout, and goes on with the answer.
var payoutFlow = ankka.Workflow("payout", func(string) payout { return payout{} },
	ankka.WorkflowCommand("start", func(_ context.Context, p payout, amount int) (ankka.WorkflowEffect[payout, string], error) {
		p.Amount = amount
		return ankka.WorkflowUpdate[payout, string](p).ThenTransitionTo("initiate-payout", nil).ThenReplyValue("started"), nil
	}),
	ankka.Step("initiate-payout", func(ctx context.Context, p payout, _ ankka.Unit) (ankka.StepEffect[payout], error) {
		res, err := ankka.CallService(ctx, "psp-gateway", ankka.ServiceRequest{
			Method: "GET", Path: fmt.Sprintf("/payouts?amount=%d", p.Amount)})
		if err != nil {
			return ankka.StepEffect[payout]{}, err // the engine retries a step that failed
		}
		p.Reference = string(res.Body)
		return ankka.StepUpdate(p).ThenEnd(), nil
	}),
)

func TestAWorkflowsStepCallsAnotherServiceAndGoesOnWithTheAnswer(t *testing.T) {
	var asked []string
	k := testkit.Workflow(payoutFlow, testkit.AnswerService("psp-gateway",
		func(r ankka.ServiceRequest) (*ankka.ServiceResponse, error) {
			asked = append(asked, r.Path)
			return &ankka.ServiceResponse{Status: 200, ContentType: "text/plain", Body: []byte("started " + r.Path)}, nil
		}))
	defer k.Close()
	ctx := context.Background()
	k.Command(ctx, "start", 25)
	if p, err := k.RunStep(ctx); err != nil || !p.Ended || k.State().Reference != "started /payouts?amount=25" {
		t.Fatalf("the workflow stood %+v, %v, at %+v", p, err, k.State())
	}
	if len(asked) != 1 || asked[0] != "/payouts?amount=25" {
		t.Fatalf("the gateway was asked %v", asked)
	}
}
```

A consumer, a timed action and an agent's tool call the same way, through their context's `services`
(Scala), `services()` (Java and Kotlin), the component's own `services` (Python, TypeScript), or
`ankka.CallService` with the handler's context (Go). A consumer makes its call before the
change it is handling is done with, so a call that fails has the change delivered again.

## What a call answers

| Method | Answers |
|---|---|
| `get(path)` and `post`/`put(path, body)` | the JSON body decoded as the type asked for; any status outside 2xx raises `ServiceCallFailed` |
| `getText(path)` (`get_text` in Python) | the body as text, what a route returning a string sends |
| `delete(path)` | nothing; any 2xx succeeds |
| `request(method, path, …)` | the answer, whatever its status: status, content type, body and headers |

Go has no typed helpers: `ankka.CallService(ctx, service, ankka.ServiceRequest{…})` answers an
`*ankka.ServiceResponse` with the status, content type, body and headers, whatever the status.

A call that got no answer the typed helpers accept ends in one of four errors, with the same names in
every language:

| Error | Means | Was anything sent |
|---|---|---|
| `ServiceUnresolvable` | no service was found under the name; the error says what was tried | no |
| `ServiceIdentityMismatch` | what answered for the name is not the service asked for | no |
| `ServiceUnanswered` | the connection was refused or broke, or no answer came in time | perhaps |
| `ServiceCallFailed` | the service answered with a status outside 2xx, to a typed helper | yes, and it answered |

In Go the first three are an `*ankka.ServiceError` whose `Reason` is `ankka.ServiceUnresolvable`,
`ankka.ServiceIdentityMismatch` or `ankka.ServiceUnanswered`; a call the runtime would not make is an
`*ankka.RefusalError`.

A refusal by the service called — a `403` from its access rule, a `404` — is an answer, not a failure:
`request` returns it, and a typed helper raises `ServiceCallFailed` carrying its status and body.

There are no retries and no redirects. Whether a call is safe to repeat is the caller's to know, and a
consumer's redelivery is often the retry it needs. A request that may change something (`POST`, `PUT`,
`DELETE`, `PATCH`) is sent at most once. A `GET` or a `HEAD` whose connection closes before any part of
an answer arrives may be sent a second time by the JDK's HTTP client underneath, which HTTP allows for a
method that changes nothing.

A body is sent whole and an answer read whole; neither is a stream. Through the runtime beside a Python,
TypeScript or Go process, each is at most 4,000,000 bytes, and a larger one is refused naming the limit.

## How long a call waits

A call waits for its answer as long as the calling service's `ankka.service-client.timeout` says, thirty
seconds unless it is set; connecting has five seconds of its own. There is no per-call timeout. A Scala
service sets the key in its `application.conf`; any service, in any language, sets
`ANKKA_SERVICE_CLIENT_TIMEOUT` in its descriptor's environment, which goes to the runtime that makes the
call and never to a Python, TypeScript or Go process. A Java or Kotlin service is in the runtime's own JVM,
so it reads either.

## What a call carries

Who is calling is the certificate's to say, never a header's. Before a request leaves, the client removes
every header whose name starts `X-Ankka-`, `Forwarded`, every `X-Forwarded-*`, `Host`, `Content-Length`,
`Expect` and the hop-by-hop headers, whoever set them; the content type is the one the call gives. Every
other header is sent as given.

A call made from a handler is part of that handler's work: the service's traces show it as a span under
the handler's span, and its topology counts it from that handler. A call made outside any handler is
counted from the unknown caller. The trace does not continue into the service called, which starts a
trace of its own.

A Python, TypeScript or Go process never holds a certificate or a key. It asks the runtime beside it to make
the call, and the runtime makes it with the service's certificate. A Rust module asks the runtime that
loaded it in the same way, and the runtime attributes the call to the handler it was running, whatever the
module says.

## On a developer's machine

There is no certificate on a developer's machine, and every caller is the local machine. A call reaches
the named service at the address `ankka.local-services.<name>` gives, if there is one, and otherwise at the
address the service announced to the local console when it started.

The runtime beside a Python, TypeScript or Go process runs in a container, where the local console's
announcements cannot be read, so it is told where another service is the same way, as a JVM option:
`JAVA_OPTS=-Dankka.local-services.carts=http://host.docker.internal:9000` in its environment. Each
language's test kit passes environment variables to that container (`testkit.Env(...)` in Go), which is how a test does it.

## Testing a component that calls another service

A unit test gives the component a scripted set of services, which answer as the test says and record
every request: `ScriptedServices` in Scala, Python, TypeScript and Rust, `testkit.AnswerService` in Go,
and a service played on loopback in Java and Kotlin. A call to a service nothing is scripted for fails the
test, naming the service, rather than answering something nobody chose.

- **Scala:** `ScriptedServices()` is a `ServiceClients`; `ConsumerTestKit` takes one as `services`. A test
  of a whole service starts `ScriptedService.start()` — another service played on loopback — and gives its
  address to `AnkkaTestKit.start(…, localServices = Map("psp-gateway" -> scripted.address))`.
- **Python:** `component.services = ScriptedServices().answer("psp-gateway", lambda request: …)`.
- **TypeScript:** `component.services = new ScriptedServices().answer("psp-gateway", (request) => …)`.
- **Java and Kotlin:** a test of a whole service names another service to play on loopback,
  `AnkkaTestKit.start(service, Settings.defaults().withScriptedService("psp-gateway"))`, and scripts and
  reads it through `kit.service("psp-gateway")`; `withLocalService(name, address)` points a name at a
  service of the test's own. There is no unit double for a single component yet.
- **Go:** a component kit takes `testkit.AnswerService("psp-gateway", answer)`, the answer a call to that
  service gets; a call to a service no `AnswerService` names fails as unresolvable, naming it.
