/**
 * `GET <mount>/stream/services/:projectId/:name`: a service page's live updates. Every two seconds
 * the service is read as the signed-in person and `status` is sent when it changed; with `logs` in
 * the query, the chosen instances' recent output is read too and only new lines are sent.
 */
import type { LoaderFunctionArgs } from "react-router";
import { useConsoleContext } from "../context.ts";
import { ControlPlaneError, SignInRequired } from "../client/errors.ts";
import { pollingStream, type EventSink } from "../stream/sse.ts";
import { LogFollower, lines } from "../stream/log-follow.ts";

export function ended(sink: EventSink, e: unknown): boolean {
  if (e instanceof SignInRequired) {
    sink.end("session-ended", { reason: "sign-in-required" });
    return true;
  }
  if (e instanceof ControlPlaneError && e.status === 404) {
    sink.end("gone", { reason: e.reason });
    return true;
  }
  return false;
}

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const projectId = params.projectId!;
  const name = params.name!;
  const query = new URL(request.url).searchParams;
  const follow = query.has("logs");
  const topology = query.has("topology");
  let lastTopology = "";
  const tail = Math.min(Math.max(Number(query.get("tail") ?? 200) || 200, 1), 5_000);
  const logQuery = { instance: query.get("instance") || undefined, previous: query.get("previous") === "true", platform: query.get("platform") === "true", tail };
  const follower = new LogFollower(tail);
  const primed = new Set<string>();
  let last = "";

  return pollingStream({
    signal: request.signal,
    async tick(sink) {
      try {
        const status = await ctx.client.getService(projectId, name);
        const now = JSON.stringify(status);
        if (now !== last) {
          last = now;
          sink.send("status", status);
        }
      } catch (e) {
        if (ended(sink, e)) return;
        throw e;
      }
      if (topology) {
        try {
          const now = await ctx.client.topology(projectId, name);
          const json = JSON.stringify(now);
          if (json !== lastTopology) {
            lastTopology = json;
            sink.send("topology", now);
          }
        } catch (e) {
          if (e instanceof SignInRequired) return void ended(sink, e);
          // No running instance has no topology; its status says why.
        }
      }
      if (!follow) return;
      try {
        const logs = await ctx.client.logs(projectId, name, logQuery);
        for (const instance of logs.instances) {
          if (instance.error) {
            sink.send("logs", { instance: instance.instance, error: instance.error });
            continue;
          }
          const fresh = follower.next(instance.instance, lines(instance.output));
          // The first window replaces what the page rendered, so nothing written between the page's
          // own read and this one is lost.
          const reset = !primed.has(instance.instance);
          primed.add(instance.instance);
          if (fresh.length > 0 || reset) sink.send("logs", { instance: instance.instance, lines: fresh, reset });
        }
      } catch (e) {
        if (e instanceof SignInRequired) return void ended(sink, e);
        // A service with no running instance has no logs; its status says why.
      }
    },
  });
}
