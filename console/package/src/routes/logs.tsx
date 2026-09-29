/**
 * A service's logs, with the CLI's four choices: one instance or all, the container before the last
 * restart, how many lines, how far back. With scripts running the page follows new lines as they are
 * written, until the person pauses it; without, it shows what was there when the page was asked for.
 */
import { useMemo, useState } from "react";
import { Form, useLoaderData, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { guard, pageData, useConsoleContext } from "../context.ts";
import { ControlPlaneError } from "../client/errors.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Breadcrumbs, useConsole } from "../ui/console.tsx";
import { useServiceStream } from "../ui/use-stream.ts";
import { lines } from "../stream/log-follow.ts";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `Logs of ${loaderData?.service.name ?? "a service"} · ankka` }];

export async function loader({ request, params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, name } = params as { projectId: string; name: string };
  const q = new URL(request.url).searchParams;
  const query = {
    instance: q.get("instance") || undefined,
    previous: q.get("previous") === "true",
    tail: q.get("tail") ? Number(q.get("tail")) : 200,
    since: q.get("since") ? Number(q.get("since")) : undefined,
  };
  return guard(ctx, async () => {
    const [service, project, page] = await Promise.all([ctx.client.getService(projectId, name), ctx.client.getProject(projectId), pageData(ctx)]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    let instances: { instance: string; lines: string[]; error?: string }[] = [];
    let none: string | undefined;
    try {
      const logs = await ctx.client.logs(projectId, name, query);
      instances = logs.instances.map((i) => ({ instance: i.instance, lines: lines(i.output), error: i.error }));
    } catch (e) {
      if (e instanceof ControlPlaneError && e.status === 404) none = e.reason;
      else throw e;
    }
    // Every instance the service has had output from, for the choice; the current read may be of one.
    const known = query.instance ? [query.instance, ...instances.map((i) => i.instance).filter((i) => i !== query.instance)] : instances.map((i) => i.instance);
    return { console: page, service, project, organization, instances, none, query, known };
  });
}

export default function Logs() {
  const { service, project: p, organization: o, instances, none, query, known } = useLoaderData<typeof loader>();
  const { mount } = useConsole();
  const [follow, setFollow] = useState(true);
  const initial = useMemo(() => Object.fromEntries(instances.map((i) => [i.instance, { lines: i.lines, error: i.error }])), [instances]);
  const params = new URLSearchParams();
  if (query.instance) params.set("instance", query.instance);
  if (query.previous) params.set("previous", "true");
  params.set("tail", String(query.tail));
  const { byInstance, state } = useServiceStream(p.id, service.name, service, {
    follow: follow && !query.previous,
    query: params.toString(),
    tail: query.tail,
    initial,
  });
  const shown = Object.entries(byInstance);
  return (
    <section className="ac-page">
      <Breadcrumbs
        trail={[
          { label: "Organizations", to: "" },
          { label: o.name, to: `organizations/${encodeURIComponent(o.id)}` },
          { label: p.name, to: `projects/${encodeURIComponent(p.id)}` },
          { label: service.name, to: `projects/${encodeURIComponent(p.id)}/services/${encodeURIComponent(service.name)}` },
          { label: "Logs" },
        ]}
      />
      <h1>Logs of {service.name}</h1>

      <Form method="get" className="ac-inline" aria-label="Which logs">
        <div className="ac-field">
          <label htmlFor="instance">Instance</label>
          <select id="instance" name="instance" defaultValue={query.instance ?? ""}>
            <option value="">Every instance</option>
            {known.map((i) => (
              <option key={i} value={i}>
                {i}
              </option>
            ))}
          </select>
        </div>
        <div className="ac-field">
          <label htmlFor="tail">Last lines</label>
          <input id="tail" name="tail" type="number" min={1} max={5000} defaultValue={query.tail} />
        </div>
        <div className="ac-field">
          <label htmlFor="since">Last seconds</label>
          <input id="since" name="since" type="number" min={1} defaultValue={query.since} placeholder="Any" />
        </div>
        <div className="ac-field">
          <label>
            <input type="checkbox" name="previous" value="true" defaultChecked={query.previous} /> The container before the last restart
          </label>
        </div>
        <button type="submit" className="ac-button">
          Show
        </button>
      </Form>

      {query.previous ? null : (
        <p className="ac-actions">
          {follow ? <span className={state === "live" ? "ac-live" : undefined}>{state === "live" ? "Following new lines" : "Connecting to follow new lines"}</span> : <span>Paused</span>}
          <button type="button" className="ac-button ac-button-quiet" onClick={() => setFollow((f) => !f)} hidden={state === "off" && !follow ? false : undefined}>
            {follow ? "Pause following" : "Follow new lines"}
          </button>
          <noscript>
            <a href={`${mount}projects/${encodeURIComponent(p.id)}/services/${encodeURIComponent(service.name)}/logs?${params}`}>Refresh</a>
          </noscript>
        </p>
      )}

      {none ? <p className="ac-empty">{none}. Logs exist only while an instance runs.</p> : null}
      {!none && shown.length === 0 ? <p className="ac-empty">No output yet.</p> : null}
      {shown.map(([instance, log]) => (
        <section key={instance} aria-labelledby={`log-${instance}`}>
          <h2 id={`log-${instance}`}>{instance}</h2>
          {log.error ? <p className="ac-refusal">{log.error}</p> : null}
          <pre className="ac-log" tabIndex={0} data-instance={instance}>
            {log.lines.join("\n")}
          </pre>
        </section>
      ))}
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
