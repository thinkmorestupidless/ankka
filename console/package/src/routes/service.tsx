/**
 * A service: everything the platform reports about it, kept current while the page is open, its
 * attributed history, and the operations the CLI offers. After an operation the page shows the
 * state the control plane reports, never what the console expected.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, applyPrimary, guard, pageData, projectShell, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Submit, useConsole } from "../ui/console.tsx";
import { Page, SectionTitle, ServiceSections } from "../ui/shell.tsx";
import { Shape } from "../ui/shape.tsx";
import { runsAs } from "../ui/hosting.ts";
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
    const [service, project, services, page] = await Promise.all([
      ctx.client.getService(projectId, name),
      ctx.client.getProject(projectId),
      ctx.client.listServices(projectId),
      pageData(ctx),
    ]);
    const organization = await ctx.client.getOrganization(project.organizationId);
    return {
      console: projectShell(page, organization, project, services, [{ label: name }], applyPrimary(projectId, name), name),
      service,
      project,
      organization,
      panels: await loadPanels(ctx, "service", service),
    };
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
  const { project: p, panels } = data;
  const { status: s, state } = useServiceStream(p.id, data.service.name, data.service);
  const deleteRefused = useRefusal("delete") !== undefined;
  const inspector = (
    <>
      <section className="ac-ops" aria-labelledby="operations-title">
        <SectionTitle>
          <span id="operations-title">Operations</span>
        </SectionTitle>
        {s.paused ? <Operation intent="resume" label="Resume" operation="service.resume" entity={s} /> : <Operation intent="pause" label="Pause" operation="service.pause" entity={s} />}
        <Operation intent="restart" label="Restart" operation="service.restart" entity={s} />
        {s.exposed ? <Operation intent="unexpose" label="Unexpose" operation="service.unexpose" entity={s} /> : <Operation intent="expose" label="Expose" operation="service.expose" entity={s} />}
      </section>
      {serviceOperations.map((op) => (
        <Refused key={op} intent={op} />
      ))}
      <details className="ac-more" open={deleteRefused || undefined}>
        <summary>Delete</summary>
        <div className="ac-danger-zone">
          <p>Deleting {s.name} stops it and removes it from the project. Its database is kept: applying a descriptor with this name again brings the service back with its data.</p>
          <Operation intent="delete" label="Delete service" operation="service.delete" danger entity={s} />
          <Refused intent="delete" />
        </div>
      </details>
    </>
  );
  return (
    <Page inspector={inspector}>
      <div className="ac-head">
        <h1>{s.name}</h1>
        <p aria-live="polite" className="ac-actions ac-state-line">
          <span className="ac-pill">
            <Lifecycle lifecycle={s.lifecycle} confirmed={s.confirmed} />
          </span>
          {state === "live" ? <span className="ac-live"> Updating as the platform reports</span> : null}
        </p>
        <ServiceSections projectId={p.id} name={s.name} current="overview" />
      </div>
      {s.detail ? <p className="ac-notice">{s.detail}</p> : null}

      <Shape service={s} />

      <section className="ac-card" aria-labelledby="reported">
        <h2 id="reported">Reported by the cluster</h2>
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
          <dd>{s.hosting === "web" ? "None" : (s.database ?? "Nothing reported yet")}</dd>
          <dt>Runs as</dt>
          <dd>{runsAs(s)}</dd>
          {s.hosting === "web" ? (
            <>
              <dt>Admits</dt>
              <dd>{["The internet", ...s.callers.map((c) => (c === "*" ? `Every service in ${p.name}` : c))].join(", ")}</dd>
              <dt>Mounts</dt>
              <dd>
                {s.mounts.length === 0 ? (
                  "None"
                ) : (
                  <ul className="ac-mounts">
                    {s.mounts.map((m) => (
                      <li key={m.path} data-mount={m.path}>
                        <code>{m.path}</code> → {m.service}
                        {m.state && m.state !== "ok" ? ` (${m.state})` : ""}
                      </li>
                    ))}
                  </ul>
                )}
              </dd>
            </>
          ) : null}
          <dt>Stopped by</dt>
          <dd>{s.suspended ? "Its organization is disabled" : s.paused ? "Its members paused it" : "Nobody"}</dd>
          <dt>Report</dt>
          <dd>{s.confirmed ? "Confirmed by the cluster" : "The last known state; the cluster has not confirmed it"}</dd>
        </dl>
      </section>

      <Panels kind="service" entity={s} loaded={panels} />
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
