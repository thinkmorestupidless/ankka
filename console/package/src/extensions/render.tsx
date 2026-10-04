/**
 * Where a host's extensions appear. Panels are loaded on the server with the page (a failure is
 * that panel's, not the page's) and rendered inside their own error boundary; actions sit beside the
 * operation they were declared for.
 */
import { Component, type ErrorInfo, type ReactNode } from "react";
import type { ConsoleContext } from "../context.ts";
import type { Operation, Panel, PanelEntities, PanelKind } from "./types.ts";
import { useConsole } from "../ui/console.tsx";

export type LoadedPanels = Record<string, { ok: true; data: unknown } | { ok: false; error: string }>;

/** Runs every registered panel's `load` for this entity; used by the package's own loaders. */
export async function loadPanels<K extends PanelKind>(ctx: ConsoleContext, kind: K, entity: PanelEntities[K]): Promise<LoadedPanels> {
  const panels = (ctx.extensions.panels?.[kind] ?? []) as Panel<PanelEntities[K]>[];
  const settled = await Promise.allSettled(
    panels.map((p) => (p.load ? p.load({ request: ctx.request, accessToken: () => ctx.accessToken() }, entity) : Promise.resolve(undefined))),
  );
  const out: LoadedPanels = {};
  panels.forEach((p, i) => {
    const r = settled[i];
    out[p.id] = r.status === "fulfilled" ? { ok: true, data: r.value } : { ok: false, error: r.reason instanceof Error ? r.reason.message : String(r.reason) };
  });
  return out;
}

class PanelBoundary extends Component<{ title: string; children: ReactNode }, { error: string | null }> {
  override state = { error: null as string | null };

  static getDerivedStateFromError(error: unknown) {
    return { error: error instanceof Error ? error.message : String(error) };
  }

  override componentDidCatch(_error: unknown, _info: ErrorInfo) {
    // Contained: the page around the panel keeps working.
  }

  override render() {
    if (this.state.error !== null) {
      return <p className="ac-panel-failed">{this.props.title} could not be shown: {this.state.error}</p>;
    }
    return this.props.children;
  }
}

export function Panels<K extends PanelKind>({ kind, entity, loaded }: { kind: K; entity: PanelEntities[K]; loaded?: LoadedPanels }) {
  const { extensions } = useConsole();
  const panels = (extensions.panels?.[kind] ?? []) as Panel<PanelEntities[K]>[];
  if (panels.length === 0) return null;
  return (
    <>
      {panels.map((p) => {
        const result = loaded?.[p.id];
        return (
          <section key={p.id} className="ac-panel" aria-labelledby={`panel-${p.id}`} data-panel={p.id}>
            <h2 id={`panel-${p.id}`}>{p.title}</h2>
            {result && !result.ok ? (
              <p className="ac-panel-failed">
                {p.title} could not be loaded: {result.error}
              </p>
            ) : (
              <PanelBoundary title={p.title}>
                <p.Component entity={entity} data={result?.ok ? result.data : undefined} />
              </PanelBoundary>
            )}
          </section>
        );
      })}
    </>
  );
}

/** Whether the host added anything beside an operation, so a page knows to make room for it. */
export function useHostActions(operation: Operation): boolean {
  const { extensions } = useConsole();
  return (extensions.actions?.[operation]?.length ?? 0) > 0;
}

export function HostActions({ operation, entity }: { operation: Operation; entity?: unknown }) {
  const { extensions } = useConsole();
  const actions = extensions.actions?.[operation] ?? [];
  return (
    <>
      {actions.map((a) => (
        <a key={a.id} className="ac-button ac-button-quiet" href={(a.href as (e: unknown) => string)(entity)} data-action={a.id}>
          {a.label}
        </a>
      ))}
    </>
  );
}
