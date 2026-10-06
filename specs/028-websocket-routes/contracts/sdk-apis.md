# Contract: the Python and TypeScript APIs

The Rust crate gains nothing: a module cannot hold a socket.

## Python (`ankka.endpoint`)

```python
    @socket("/stream/{room}")
    async def stream(self, room: str, socket: Socket) -> None:
        who = self.request.principal.subject
        async for text in socket:          # ends when the socket is closed
            await socket.send(f"{who} in {room}: {text}")
```

```python
socket = _route("GET", socket=True)        # @socket(template, *, acl=None)

class Socket:
    def __aiter__(self) -> AsyncIterator[str]: ...
    async def receive(self) -> str | None: ...      # None once closed
    async def send(self, text: str) -> None: ...    # raises SocketClosed once closed

class SocketClosed(Exception): ...
```

- The parameter annotated `Socket` is the socket; the others bind to path parameters by name, as
  for every route. A socket route takes no body.
- `self.request` answers for the opening request for the handler's whole run.
- Returning closes "finished"; raising closes "failed"; `SocketClosed` escaping is a return.
- `EndpointTestKit.socket(path, *, principal=None, caller=None, query=None, headers=None)`
  returns a double with `send`, `receive` and `close`, run against the handler with no runtime.

## TypeScript (`ankka`)

```ts
  static readonly routes = {
    stream: socket("/stream/{room}", async (ep: NoticesEndpoint, req, socket) => {
      for await (const text of socket) {    // ends when the socket is closed
        await socket.send(`${req.principal!.subject} in ${req.params.room}: ${text}`)
      }
    }),
  }
```

```ts
export function socket<Ep, PS = Params>(
  template: string,
  run: (self: Ep, request: RequestContext<PS>, socket: Socket) => Promise<void>,
  options?: RouteOptions,          // acl, params: as for every route
): RouteRef<Ep>;

export interface Socket extends AsyncIterable<string> {
  receive(): Promise<string | undefined>;   // undefined once closed
  send(text: string): Promise<void>;        // rejects with SocketClosed once closed
}

export class SocketClosed extends Error {}
```

- Erasable syntax only, as the rest of the package: no parameter properties, no enums.
- `EndpointTestKit.socket(route, init?)` returns the same kind of double.

## Both

- Discovery sets `Route.socket`; `PROTOCOL_VERSION` is `"1.9"`.
- Discovery fails for a service with a socket route when the runtime states a protocol below 1.9.
- The servicer reads the request stream only as the handler asks for frames.
- The conformance example gains the routes [protocol.md](protocol.md) lists, and the shopping
  cart example gains `/carts/{cartId}/watch`, inside a docs region.
