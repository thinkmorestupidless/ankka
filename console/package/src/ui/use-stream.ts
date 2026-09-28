/**
 * The browser's side of the update streams. A stream opens only after hydration, in an effect, so a
 * page with scripts off opens none and shows the state as of its last request. `EventSource`
 * reconnects on its own when an instance closes the stream, and every stream starts by sending the
 * current state, so nothing has to be carried across.
 */
import { useEffect, useRef, useState } from "react";
import { useConsole } from "./console.tsx";
import type { ServiceStatus } from "../client/schemas.ts";

export type StreamState = "connecting" | "live" | "gone" | "off";

function useEvents(path: string | null, handlers: Record<string, (data: unknown) => void>): StreamState {
  const { href } = useConsole();
  const [state, setState] = useState<StreamState>(path ? "connecting" : "off");
  const ref = useRef(handlers);
  ref.current = handlers;

  useEffect(() => {
    if (!path) {
      setState("off");
      return;
    }
    const source = new EventSource(href(path));
    setState("connecting");
    source.onopen = () => setState("live");
    const on = (name: string, f: (data: unknown) => void) =>
      source.addEventListener(name, (e) => f(JSON.parse((e as MessageEvent<string>).data)));
    for (const name of ["status", "services", "logs"]) on(name, (d) => ref.current[name]?.(d));
    on("session-ended", () => {
      source.close();
      window.location.assign(href(`auth/sign-in?returnTo=${encodeURIComponent(window.location.pathname + window.location.search)}`));
    });
    on("gone", () => {
      source.close();
      setState("gone");
    });
    on("server-closing", () => setState("connecting"));
    source.onerror = () => setState(source.readyState === EventSource.CLOSED ? "off" : "connecting");
    return () => source.close();
  }, [path, href]);

  return state;
}

export function useProjectStream(projectId: string, initial: ServiceStatus[]) {
  const [services, setServices] = useState(initial);
  useEffect(() => setServices(initial), [initial]);
  const state = useEvents(`stream/projects/${encodeURIComponent(projectId)}`, {
    services: (d) => setServices(d as ServiceStatus[]),
  });
  return { services, state };
}

export interface LogsState {
  byInstance: Record<string, { lines: string[]; error?: string }>;
}

export function useServiceStream(
  projectId: string,
  name: string,
  initial: ServiceStatus,
  logs?: { follow: boolean; query: string; tail: number; initial: LogsState["byInstance"] },
) {
  const [status, setStatus] = useState(initial);
  useEffect(() => setStatus(initial), [initial]);
  const [byInstance, setByInstance] = useState(logs?.initial ?? {});
  useEffect(() => setByInstance(logs?.initial ?? {}), [logs?.initial]);
  const base = `stream/services/${encodeURIComponent(projectId)}/${encodeURIComponent(name)}`;
  const path = logs?.follow ? `${base}?logs&${logs.query}` : base;
  const state = useEvents(path, {
    status: (d) => setStatus(d as ServiceStatus),
    logs: (d) => {
      const e = d as { instance: string; lines?: string[]; error?: string; reset?: boolean };
      setByInstance((prev) => {
        const kept = e.reset ? [] : (prev[e.instance]?.lines ?? []);
        const next = e.error ? { lines: kept, error: e.error } : { lines: [...kept, ...(e.lines ?? [])].slice(-(logs?.tail ?? 200)) };
        return { ...prev, [e.instance]: next };
      });
    },
  });
  return { status, byInstance, state };
}
