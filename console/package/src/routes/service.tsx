/**
 * A service: everything the platform reports about it, kept current while the page is open, its
 * attributed history, and the operations the CLI offers. After an operation the page shows the
 * state the control plane reports, never what the console expected.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, guard, pageData, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { Breadcrumbs, ConsoleForm, ConsoleLink, Submit, useConsole, when } from "../ui/console.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { Lifecycle } from "../ui/status.tsx";
import { useServiceStream } from "../ui/use-stream.ts";
import { HostActions, loadPanels, Panels } from "../extensions/render.tsx";
import type { Operation } from "../extensions/types.ts";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `${loaderData?.service.name ?? "Service"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, name } = params as { projectId: string; name: string };
  return guard(ctx, async () => {
    const [service, history, project, page] = await Promise.all([
      ctx.client.getService(projectId, name),
      ctx.client.history(projectId, name),
      ctx.client.getProject(projectId),
      pageData(ctx),
    ]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    return { console: page, service, history: [...history].reverse(), project, organization, panels: await loadPanels(ctx, "service", service) };
  });
}

const serviceOperations = ["pause", "resume", "restart", "expose", "unexpose"] as const;

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const { projectId, name } = params as { projectId: string; name: string };
  const form = await request.formData();
  const intent = text(form, "intent");
  return act(ctx, intent, form, async () => {
    if (intent === "delete") {
      await ctx.client.deleteService(projectId, name);
      return redirect(ctx.href(`projects/${encodeURIComponent(projectId)}`));
    }
    if (!(serviceOperations as readonly string[]).includes(intent)) throw new Response(`unknown operation '${intent}'`, { status: 400 });
    await ctx.client.serviceOperation(projectId, name, intent as (typeof serviceOperations)[number]);
    return redirect(ctx.href(`projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name)}`));
  });
}

const history = {
  applied: "Applied",
  restarted: "Restarted",
  paused: "Paused",
  resumed: "Resumed",
  exposed: "Exposed",
  unexposed: "Unexposed",
  deleted: "Deleted",
} as Record<string, string>;

function Operation({ intent, label, operation, danger, entity }: { intent: string; label: string; operation: Operation; danger?: boolean; entity?: unknown }) {
  const { shows } = useConsole();
  return (
    <>
      {shows(operation) ? (
        <ConsoleForm intent={intent}>
          <Submit intent={intent} danger={danger}>
            {label}
          </Submit>
        </ConsoleForm>
      ) : null}
      <HostActions operation={operation} entity={entity} />
    </>
  );
}

export default function Service() {
  const data = useLoaderData<typeof loader>();
  const { project: p, organization: o, panels } = data;
  const { status: s, state } = useServiceStream(p.id, data.service.name, data.service);
  const deleteRefused = useRefusal("delete") !== undefined;
  const path = `projects/${encodeURIComponent(p.id)}/services/${encodeURIComponent(s.name)}`;
  return (
    <section className="ac-page">
      <Breadcrumbs
        trail={[
          { label: "Organizations", to: "" },
          { label: o.name, to: `organizations/${encodeURIComponent(o.id)}` },
          { label: p.name, to: `projects/${encodeURIComponent(p.id)}` },
          { label: s.name },
        ]}
      />
      <h1>{s.name}</h1>
      <p aria-live="polite" className="ac-state-line">
        <Lifecycle lifecycle={s.lifecycle} confirmed={s.confirmed} />
        {state === "live" ? <span className="ac-live"> Updating as the platform reports</span> : null}
      </p>
      {s.detail ? <p className="ac-notice">{s.detail}</p> : null}

      <dl className="ac-facts">
        <dt>Instances</dt>
        <dd>
          {s.readyInstances} ready of {s.desiredInstances}
        </dd>
        <dt>Image</dt>
        <dd>{s.image}</dd>
        <dt>Generation</dt>
        <dd>{s.generation}</dd>
        <dt>Address</dt>
        <dd>{s.hostname ? <a href={s.hostname}>{s.hostname}</a> : s.exposed ? "Exposed; the platform has no address for it yet" : "Not exposed"}</dd>
        <dt>Database</dt>
        <dd>{s.database ?? "Nothing reported yet"}</dd>
        <dt>Runs as</dt>
        <dd>{s.hosting === "process" ? `A process beside the platform's sidecar${s.protocol ? `, protocol ${s.protocol}` : ""}` : "Embedded in the platform's runtime"}</dd>
        <dt>Stopped by</dt>
        <dd>{s.suspended ? "Its organization is disabled" : s.paused ? "Its members paused it" : "Nobody"}</dd>
        <dt>Report</dt>
        <dd>{s.confirmed ? "Confirmed by the cluster" : "The last known state; the cluster has not confirmed it"}</dd>
      </dl>

      <div className="ac-actions" aria-label="Operations">
        {s.paused ? <Operation intent="resume" label="Resume" operation="service.resume" entity={s} /> : <Operation intent="pause" label="Pause" operation="service.pause" entity={s} />}
        <Operation intent="restart" label="Restart" operation="service.restart" entity={s} />
        {s.exposed ? <Operation intent="unexpose" label="Unexpose" operation="service.unexpose" entity={s} /> : <Operation intent="expose" label="Expose" operation="service.expose" entity={s} />}
        <ConsoleLink to={`${path}/logs`} className="ac-button ac-button-quiet">
          Logs
        </ConsoleLink>
        <ConsoleLink to={`projects/${encodeURIComponent(p.id)}/services/apply?name=${encodeURIComponent(s.name)}`} className="ac-button ac-button-quiet">
          Apply a new descriptor
        </ConsoleLink>
      </div>
      {serviceOperations.map((op) => (
        <Refused key={op} intent={op} />
      ))}

      <Panels kind="service" entity={s} loaded={panels} />

      <h2>History</h2>
      {data.history.length === 0 ? (
        <p className="ac-empty">Nothing recorded yet.</p>
      ) : (
        <div className="ac-table-wrap">
          <table className="ac-table">
            <thead>
              <tr>
                <th scope="col">What</th>
                <th scope="col" className="ac-num">
                  Generation
                </th>
                <th scope="col">Who</th>
                <th scope="col">When</th>
              </tr>
            </thead>
            <tbody>
              {data.history.map((h, i) => (
                <tr key={i}>
                  <td>{history[h.kind] ?? h.kind}</td>
                  <td className="ac-num">{h.generation}</td>
                  <td>
                    {h.actor ? (h.actor.display ?? h.actor.subject) : "—"}
                    {h.actor?.administrative ? " (as administrator)" : ""}
                  </td>
                  <td>{when(h.at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <details className="ac-more" open={deleteRefused || undefined}>
        <summary>Delete</summary>
        <div className="ac-danger-zone">
          <p>Deleting {s.name} stops it and removes it from the project. Its database is kept: applying a descriptor with this name again brings the service back with its data.</p>
          <Operation intent="delete" label="Delete service" operation="service.delete" danger entity={s} />
          <Refused intent="delete" />
        </div>
      </details>
    </section>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
