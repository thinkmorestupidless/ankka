/** `GET <mount>/stream/projects/:projectId`: a project's service listing, sent whenever it changes. */
import type { LoaderFunctionArgs } from "react-router";
import { useConsoleContext } from "../context.ts";
import { pollingStream } from "../stream/sse.ts";
import { ended } from "./stream.service.ts";

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const projectId = params.projectId!;
  let last = "";
  return pollingStream({
    signal: request.signal,
    async tick(sink) {
      try {
        const services = await ctx.client.listServices(projectId);
        const now = JSON.stringify(services);
        if (now !== last) {
          last = now;
          sink.send("services", services);
        }
      } catch (e) {
        if (!ended(sink, e)) throw e;
      }
    },
  });
}
