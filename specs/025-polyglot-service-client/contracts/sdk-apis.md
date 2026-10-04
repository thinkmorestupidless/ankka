# Contract: calling another service in Python and TypeScript

The shape of the Scala API in each language's idiom. Decisions are in
[research.md](../research.md) (R11, R12).

## Python

```python
class Services:                       # self.services, where a component has one
    def __call__(self, name: str, *, project: str | None = None) -> ServiceClient: ...

class ServiceClient:
    target: str                                                   # "project/name"; "name" alone in the SDK
    async def get(self, path, returns: type[R], *, headers=None) -> R: ...
    async def get_text(self, path, *, headers=None) -> str: ...
    async def post(self, path, body, returns: type[R], *, headers=None) -> R: ...
    async def put(self, path, body, returns: type[R], *, headers=None) -> R: ...
    async def delete(self, path, *, headers=None) -> None: ...
    async def request(self, method, path, *, body: bytes | None = None,
                      content_type: str | None = None, headers=None) -> ServiceResponse: ...

@dataclass(frozen=True)
class ServiceResponse:
    status: int
    content_type: str
    body: bytes
    headers: tuple[tuple[str, str], ...]
    @property
    def text(self) -> str: ...
```

Bodies and answers of the typed helpers are JSON through the SDK's codec, as an endpoint's are.

Errors, all subclasses of `ServiceError(Exception)`: `ServiceUnresolvable(service, reason)`,
`ServiceIdentityMismatch(service, detail)`, `ServiceUnanswered(service, reason)`,
`ServiceCallFailed(service, status, body)`. A refusal by the runtime is `CommandError`, as for
every client call.

Offered by a `HasServices` mixin, as `HasSecrets` is: `Endpoint`, `Consumer`, `GraphConsumer`,
`TimedAction`, `Agent`, `AutonomousAgent`; and by `Workflow`, where it raises
`CommandError(BAD_REQUEST)` naming "a step" outside one. `CommandContext` (an entity's) and `View`
have none.

The metadata sent is the running handler's, without the developer forwarding it: the scoped
client's for every kind that has one, and the current request's for an endpoint.

`ScriptedServices` is the unit-test double: `component.services = ScriptedServices()`, then
`scripted.answer("merchant", lambda request: ServiceResponse(…))`, `scripted.unresolvable(…)`,
`scripted.unanswered(…)`, `scripted.requests`. A call with no script raises, naming the service.

Exports from `ankka`: `Services`, `ServiceClient`, `ServiceResponse`, `ScriptedServices`, and the
five errors.

## TypeScript

```ts
class Services {
  service(name: string): ServiceClient
  service(project: string, name: string): ServiceClient
}

class ServiceClient {
  readonly target: string
  get<R>(path: string, returns: Schema<R>, options?: { headers?: Headers }): Promise<R>
  getText(path: string, options?: { headers?: Headers }): Promise<string>
  post<B, R>(path: string, body: B, schemas: { body: Schema<B>; returns: Schema<R> }, options?): Promise<R>
  put<B, R>(path: string, body: B, schemas: { body: Schema<B>; returns: Schema<R> }, options?): Promise<R>
  delete(path: string, options?: { headers?: Headers }): Promise<void>
  request(method: string, path: string, options?: {
    body?: Uint8Array; contentType?: string; headers?: Headers
  }): Promise<ServiceResponse>
}

type Headers = ReadonlyArray<readonly [string, string]>
interface ServiceResponse {
  readonly status: number; readonly contentType: string
  readonly body: Uint8Array; readonly headers: Headers; readonly text: string
}
```

Errors, all extending `ServiceError`: `ServiceUnresolvable`, `ServiceIdentityMismatch`,
`ServiceUnanswered`, `ServiceCallFailed` (with `status` and `body`).

`get services()` on the endpoint, consumer, graph consumer, timed action, agent and autonomous
agent classes, and on the workflow, where it throws the `BAD_REQUEST` `CommandError` outside a
step. The two entity classes and the view have no such member, so it does not type-check there.
The metadata sent is the bound client's, which each server binding already scopes to the handler.

`ScriptedServices` and `noServices()` are the doubles, beside `InMemorySecrets` and `noSecrets()`;
a kind's `services` has a setter for a test, as its `secrets` has.

Exports from `ankka`: `Services`, `ServiceClient`, `ScriptedServices`, `noServices`, the five
error classes and the two types.

## Both

- **A runtime that is too old.** `UNIMPLEMENTED` from `Request` becomes a `CommandError`
  (`INTERNAL`) saying the runtime beside the process cannot call another service, which needs
  protocol 1.7, and naming the version the SDK speaks.
- **A reply with no case set** is a fault (`CommandError`, `INTERNAL`), never an empty answer.
- **A body over 4,000,000 bytes** is refused by the SDK before anything is sent, naming the limit.
- **The integration test kit** is unchanged. It starts one service, and its `env` already reaches
  the sidecar's container: `env={"JAVA_OPTS": "-Dankka.local-services.merchant=http://host.docker.internal:9100"}`
  is how a test tells the sidecar where another service is, which is the setting a Scala service
  reads. One integration test in each SDK calls a stand-in on the host that way.
- **Rust** gains nothing. Its copy of `protocol/` is refreshed, because the three copies are held
  identical to the canonical one, and its `PROTOCOL_VERSION` stays as it is until the import is
  added.
