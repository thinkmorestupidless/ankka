/**
 * A deployed service's topology: what it is made of and what calls what, merged by the control plane
 * from every running instance. The page says how many instances answered, names any that did not, and
 * marks the parts not every instance has. With scripts running it follows new calls as they are counted.
 */
import { useMemo, useState } from "react";
import { useLoaderData, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { applyPrimary, guard, pageData, projectShell, useConsoleContext } from "../context.ts";
import { ControlPlaneError } from "../client/errors.ts";
import type { ServiceTopology } from "../client/schemas.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Page, ServiceSections } from "../ui/shell.tsx";
import { useTopologyStream } from "../ui/use-stream.ts";
import { observedLine, view, edgeMark, totals } from "../ui/topology/layout.ts";
import { Graph } from "../ui/topology/Graph.tsx";
import { Table, duration } from "../ui/topology/Table.tsx";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `Topology of ${loaderData?.service.name ?? "a service"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, name } = params as { projectId: string; name: string };
  return guard(ctx, async () => {
    const [service, project, services, page] = await Promise.all([ctx.client.getService(projectId, name), ctx.client.getProject(projectId), ctx.client.listServices(projectId), pageData(ctx)]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    let topology: ServiceTopology | null = null;
    let none: string | undefined;
    try {
      topology = await ctx.client.topology(projectId, name);
    } catch (e) {
      // No running instance is nothing to show yet, not an error.
      if (e instanceof ControlPlaneError && e.status === 404) none = e.reason;
      else throw e;
    }
    const shell = projectShell(
      page,
      organization,
      project,
      services,
      [{ label: name, to: `projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name)}` }, { label: "Topology" }],
      applyPrimary(projectId, name),
      name,
    );
    return { console: shell, service, project, organization, topology, none };
  });
}

export default function ServiceTopologyPage() {
  const { service, project: p, topology: initial, none } = useLoaderData<typeof loader>();
  const { topology, state } = useTopologyStream(p.id, service.name, initial);
  const [showPlatform, setShowPlatform] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const shown = useMemo(() => (topology ? view(topology, { showPlatform }) : null), [topology, showPlatform]);
  const differs = useMemo(() => new Map((topology?.differences ?? []).map((d) => [d.node, d.presentOn])), [topology]);
  const chosen = shown?.nodes.find((n) => n.id === selected) ?? null;

  return (
    <Page>
      <div className="ac-head">
        <h1>Topology of {service.name}</h1>
        <ServiceSections projectId={p.id} name={service.name} current="topology" />
      </div>

      {none ? <p className="ac-empty">{none}. A topology exists only while an instance runs.</p> : null}

      {topology && shown ? (
        <>
          <p className="ac-actions">
            <span data-instances>
              {topology.contributing} of {topology.running} instance{topology.running === 1 ? "" : "s"} answered
            </span>
            {topology.partial ? <span className="ac-status ac-status-trouble ac-topology-partial">Partial</span> : null}
            <span className={state === "live" ? "ac-live" : undefined}>{state === "live" ? "Following new calls" : state === "connecting" ? "Connecting to follow new calls" : null}</span>
          </p>

          {topology.partial ? (
            <div className="ac-notice" role="status">
              <p>These instances did not contribute, so the counts below are of the others only:</p>
              <ul>
                {topology.instances
                  .filter((i) => i.status !== "ok")
                  .map((i) => (
                    <li key={i.pod} data-missing={i.pod}>
                      {i.pod}: {i.status}
                      {i.problem ? ` (${i.problem})` : ""}
                    </li>
                  ))}
              </ul>
            </div>
          ) : null}

          {topology.differences.length > 0 ? (
            <div className="ac-notice">
              <p>Not on every instance:</p>
              <ul>
                {topology.differences.map((d) => (
                  <li key={d.node} data-difference={d.node}>
                    {d.node}, on {d.presentOn.join(", ")}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <p className="ac-hint">{observedLine(topology.window, Date.now())} Calls are observed, not complete.</p>

          <label className="ac-field">
            <span>
              <input type="checkbox" checked={showPlatform} onChange={(e) => setShowPlatform(e.target.checked)} /> Show the platform's own components
              {shown.hiddenPlatform > 0 && !showPlatform ? ` (${shown.hiddenPlatform} left out)` : ""}
            </span>
          </label>

          {shown.nodes.length === 0 ? (
            <p className="ac-empty">This service registers no components.</p>
          ) : (
            <div className="ac-topology">
              <div className="ac-topology-picture">
                <Graph shown={shown} differs={new Set(differs.keys())} selected={selected} onSelect={setSelected} />
              </div>
              {chosen ? (
                <aside className="ac-card ac-topology-detail" aria-label={`About ${chosen.label}`}>
                  <h2>{chosen.label}</h2>
                  <p>{chosen.kind}</p>
                  <h3>Calls</h3>
                  <ul>
                    {shown.calls
                      .filter((c) => c.from === chosen.id || c.to === chosen.id)
                      .flatMap((c) =>
                        c.pairs.map((pair) => (
                          <li key={`${c.from}>${c.to}:${pair.caller}>${pair.callee}`}>
                            {c.from === chosen.id ? `to ${c.to}` : `from ${c.from}`}: {pair.caller} → {pair.callee}
                            {pair.streaming ? " (stream)" : ""}, handled {totals([pair]).handled}, unanswered {totals([pair]).unanswered}
                            {edgeMark([pair]) ? ", going wrong" : ""}, {duration(pair)} (approximate)
                          </li>
                        )),
                      )}
                  </ul>
                </aside>
              ) : null}
            </div>
          )}

          <section className="ac-card" aria-labelledby="as-text">
            <h2 id="as-text">As text</h2>
            <Table shown={shown} differs={differs} />
          </section>
        </>
      ) : null}
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
