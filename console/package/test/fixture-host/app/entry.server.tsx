// The whole page, rendered before it is sent: the fixture's requests come from a test, not a crawler,
// so there is no bot detection to do and nothing to stream.
import { PassThrough } from "node:stream";
import { createReadableStreamFromReadable } from "@react-router/node";
import { renderToPipeableStream } from "react-dom/server";
import { ServerRouter, type EntryContext } from "react-router";

export default function handleRequest(request: Request, status: number, headers: Headers, context: EntryContext) {
  return new Promise<Response>((resolve, reject) => {
    const { pipe } = renderToPipeableStream(<ServerRouter context={context} url={request.url} />, {
      onAllReady() {
        const body = new PassThrough();
        headers.set("content-type", "text/html");
        resolve(new Response(createReadableStreamFromReadable(body), { status, headers }));
        pipe(body);
      },
      onShellError: reject,
    });
  });
}
