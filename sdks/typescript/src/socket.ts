// A socket route's open socket, as its handler holds it. The sidecar holds the socket itself — its
// access rule, its limits, its close codes — and relays each frame over `Http.HandleSocket`.

/** Thrown by `Socket.send` once the socket is closed. A handler that lets it escape has ended as a
 * handler ends when its client goes: it is not a failure. */
export class SocketClosed extends Error {
  constructor(message = "the socket is closed") {
    super(message)
    this.name = "SocketClosed"
  }
}

/** A socket route handler's hold on its socket: `for await (const text of socket)` reads frames until
 * it is closed, and `await socket.send(text)` writes one. */
export interface Socket extends AsyncIterable<string> {
  /** The next frame, or `undefined` once the socket is closed. */
  receive(): Promise<string | undefined>
  /** Sends a frame; rejects with `SocketClosed` once the socket is closed. */
  send(text: string): Promise<void>
}

/** A socket over two functions: what the servicer and the endpoint test kit each give a handler. */
export function socketOf(receive: () => Promise<string | undefined>, send: (text: string) => Promise<void>): Socket {
  return Object.freeze({
    receive,
    send,
    [Symbol.asyncIterator](): AsyncIterator<string> {
      return {
        async next(): Promise<IteratorResult<string>> {
          const text = await receive()
          return text === undefined ? { value: undefined, done: true } : { value: text, done: false }
        },
      }
    },
  })
}
