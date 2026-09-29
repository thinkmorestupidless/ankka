/**
 * A server-sent event stream fed by polling: every interval, `tick` reads what the page shows and
 * sends what changed. Every event's data is one JSON document on one line — raw text would lose a
 * leading space to the protocol and split on a newline. A comment every fifteen seconds keeps
 * proxies from closing a quiet stream. Shutdown tells the browser to reconnect, which lands it on an
 * instance that is staying.
 */
import { registerStream } from "./registry.ts";

export interface EventSink {
  send(event: string, data: unknown): void;
  /** Ends the stream after sending `event`, if given. */
  end(event?: string, data?: unknown): void;
  readonly closed: boolean;
}

export interface PollingStreamOptions {
  signal: AbortSignal;
  intervalMs?: number;
  keepaliveMs?: number;
  /** Called at once and then every interval until the stream ends. */
  tick(sink: EventSink): Promise<void>;
}

const encoder = new TextEncoder();

export function pollingStream(options: PollingStreamOptions): Response {
  const interval = options.intervalMs ?? 2_000;
  const keepalive = options.keepaliveMs ?? 15_000;
  let timer: NodeJS.Timeout | undefined;
  let alive: NodeJS.Timeout | undefined;
  let unregister: () => void = () => undefined;
  let closed = false;

  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      const write = (chunk: string) => {
        if (closed) return;
        try {
          controller.enqueue(encoder.encode(chunk));
        } catch {
          stop();
        }
      };
      const stop = () => {
        if (closed) return;
        closed = true;
        clearTimeout(timer);
        clearInterval(alive);
        unregister();
        try {
          controller.close();
        } catch {
          // already closed by the client leaving
        }
      };
      const sink: EventSink = {
        send: (event, data) => write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
        end: (event, data) => {
          if (event) sink.send(event, data ?? {});
          stop();
        },
        get closed() {
          return closed;
        },
      };
      unregister = registerStream(() => sink.end("server-closing", {}));
      options.signal.addEventListener("abort", stop, { once: true });
      alive = setInterval(() => write(": keepalive\n\n"), keepalive);
      write("retry: 2000\n\n");
      const loop = async () => {
        if (closed) return;
        try {
          await options.tick(sink);
        } catch {
          // A read that failed this time is tried again next time; a stream never dies of one.
        }
        if (!closed) timer = setTimeout(loop, interval);
      };
      void loop();
    },
    cancel() {
      closed = true;
      clearTimeout(timer);
      clearInterval(alive);
      unregister();
    },
  });

  return new Response(body, {
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-store",
      connection: "keep-alive",
      "x-accel-buffering": "no",
    },
  });
}
