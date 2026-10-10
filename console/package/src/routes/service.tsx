/**
 * A service: everything the platform reports about it, kept current while the page is open, its
 * attributed history, and the operations the CLI offers. After an operation the page shows the
 * state the control plane reports, never what the console expected.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { act, applyPrimary, guard, pageData, projectShell, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, Field, Submit, useConsole } from "../ui/console.tsx";
import { Select } from "../ui/primitives/select.tsx";
import type { CustomHostname, DnsRecord, ServiceStatus } from "../client/schemas.ts";
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
const storageOperations = ["storage-credential", "storage-settings"] as const;

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
    const self = ctx.href(`projects/${encodeURIComponent(projectId)}/services/${encodeURIComponent(name)}`);
    if (intent === "storage-credential") {
      await ctx.client.reissueStorageCredential(projectId, name);
      return redirect(self);
    }
    if (intent === "storage-settings") {
      await ctx.client.reapplyStorageSettings(projectId, name);
      return redirect(self);
    }
    if (intent === "storage-move") {
      await ctx.client.moveStorage(projectId, name, text(form, "writePauseBound") || undefined);
      return redirect(self);
    }
    if (intent === "hostname-add") {
      await ctx.client.addHostname(projectId, name, text(form, "hostname").trim());
      return redirect(self);
    }
    if (intent === "hostname-remove") {
      await ctx.client.removeHostname(projectId, name, text(form, "hostname"));
      return redirect(self);
    }
    if (!(serviceOperations as readonly string[]).includes(intent)) throw new Response(`unknown operation '${intent}'`, { status: 400 });
    await ctx.client.serviceOperation(projectId, name, intent as (typeof serviceOperations)[number]);
    return redirect(self);
  });
}

/** The store a bucket is in, as the operator reported it. */
const stores: Record<string, string> = { garage: "Garage", gcs: "Google Cloud Storage" };

/**
 * What members can do about a bucket the platform made: issue its credential again, reapply the
 * installation's settings to one in Google Cloud Storage, or move one from Garage there. Whether the
 * installation can move at all is the control plane's to say, and its refusal is shown here.
 */
function Storage({ s }: { s: ServiceStatus }) {
  const { shows } = useConsole();
  const moveRefusal = useRefusal("storage-move");
  if (!s.bucket) return null;
  return (
    <section className="ac-ops" aria-labelledby="storage-title">
      <SectionTitle>
        <span id="storage-title">Object storage</span>
      </SectionTitle>
      <Operation intent="storage-credential" label="Issue credential again" operation="service.storage-credential" entity={s} />
      {s.objectStore === "gcs" ? (
        <Operation intent="storage-settings" label="Reapply bucket settings" operation="service.storage-settings" entity={s} />
      ) : shows("service.storage-move") ? (
        <details className="ac-more" open={moveRefusal !== undefined || undefined}>
          <summary>Move to Google Cloud Storage</summary>
          <p>
            Every object is copied while the service goes on writing; then its writes are paused, what changed is copied, every object is checked on
            both sides, and the service is replaced onto its new bucket. The bucket in Garage is kept.
          </p>
          <ConsoleForm intent="storage-move" className="ac-form">
            <Field
              label="Write pause bound"
              name="writePauseBound"
              placeholder="10m"
              autoComplete="off"
              defaultValue={moveRefusal?.values.writePauseBound}
              hint="How long writes may be paused, from 1m to 24h. A pause that reaches it fails the move and gives the writes back."
            />
            <Refused intent="storage-move" />
            <div>
              <Submit intent="storage-move">Move bucket</Submit>
            </div>
          </ConsoleForm>
        </details>
      ) : null}
      <HostActions operation="service.storage-move" entity={s} />
      {storageOperations.map((op) => (
        <Refused key={op} intent={op} />
      ))}
    </section>
  );
}

/** A service's bucket, that it has a store of its own, or none — and a phrase while it waits or failed. */
function objectStorage(s: { objectStorage?: string; bucket?: string }): string {
  if (s.objectStorage && /waiting|failed/.test(s.objectStorage)) return s.objectStorage;
  if (s.bucket) return s.bucket;
  if (s.objectStorage === "supplied") return "Its own";
  return "None";
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

function record(r: DnsRecord): string {
  return `${r.kind} ${r.name} → ${r.value}`;
}

function hostnameStands(h: CustomHostname): string {
  return h.reason ? `${h.state}: ${h.reason}` : h.state;
}

/**
 * The names a service answers at: the one the platform derives, then each custom hostname with where it
 * stands and the record its owner creates, and the proof record the project's names need. Adding and
 * removing are forms; the control plane's refusal is shown as it says it.
 */
function Hostnames({ service: s }: { service: ServiceStatus }) {
  return (
    <section className="ac-card" aria-labelledby="hostnames" data-hostnames>
      <h2 id="hostnames">Hostnames</h2>
      <dl className="ac-facts">
        <dt>Address</dt>
        <dd>{s.hostname ? <a href={s.hostname}>{s.hostname}</a> : s.exposed ? "Exposed; the platform has no address for it yet" : "Not exposed"}</dd>
        {s.customHostnames.map((h) => (
          <div key={h.hostname} data-custom-hostname={h.hostname} data-state={h.state}>
            <dt>{h.hostname}</dt>
            <dd>
              <span>{hostnameStands(h)}</span>
              {h.record ? (
                <span className="ac-hint" data-record>
                  {" "}
                  Create {record(h.record)}
                </span>
              ) : null}
              {h.note ? <span className="ac-hint"> {h.note}</span> : null}
            </dd>
          </div>
        ))}
        {s.proofRecord ? (
          <>
            <dt>Proof record</dt>
            <dd>
              <code data-proof-record>
                {s.proofRecord.kind} {s.proofRecord.name} "{s.proofRecord.value}"
              </code>
            </dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}

/** Adding and removing a custom hostname, in the inspector with the service's other operations. */
function HostnameOperations({ service: s }: { service: ServiceStatus }) {
  const { shows } = useConsole();
  const addRefusal = useRefusal("hostname-add");
  if (!shows("service-hostname.add") && !shows("service-hostname.remove")) return null;
  return (
    <section className="ac-form" aria-labelledby="hostname-operations" data-hostname-operations>
      <SectionTitle>
        <span id="hostname-operations">Custom hostnames</span>
      </SectionTitle>
      {shows("service-hostname.add") ? (
        <ConsoleForm intent="hostname-add" className="ac-inline">
          <Field label="Add a custom hostname" name="hostname" required autoComplete="off" placeholder="app.example.com" defaultValue={addRefusal?.values.hostname} />
          <Submit intent="hostname-add" primary>
            Add
          </Submit>
        </ConsoleForm>
      ) : null}
      <Refused intent="hostname-add" />
      {shows("service-hostname.remove") && s.customHostnames.length > 0 ? (
        <ConsoleForm intent="hostname-remove" className="ac-inline">
          <Select label="Remove a custom hostname" id="hostname-remove" name="hostname">
            {s.customHostnames.map((h) => (
              <option key={h.hostname} value={h.hostname}>
                {h.hostname}
              </option>
            ))}
          </Select>
          <Submit intent="hostname-remove" danger>
            Remove
          </Submit>
        </ConsoleForm>
      ) : null}
      <Refused intent="hostname-remove" />
    </section>
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
      <HostnameOperations service={s} />
      {serviceOperations.map((op) => (
        <Refused key={op} intent={op} />
      ))}
      <Storage s={s} />
      <details className="ac-more" open={deleteRefused || undefined}>
        <summary>Delete</summary>
        <div className="ac-danger-zone">
          <p>Deleting {s.name} stops it and removes it from the project. Its database is kept, and so is its bucket if it has one: applying a descriptor with this name again brings the service back with its data.</p>
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

      <Hostnames service={s} />

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
          <dt>Database</dt>
          <dd>{s.hosting === "web" ? "None" : (s.database ?? "Nothing reported yet")}</dd>
          <dt>Object storage</dt>
          <dd>
            {objectStorage(s)}
            {s.bucketAddress ? <span className="ac-hint" data-bucket-address> {s.bucketAddress}</span> : null}
          </dd>
          {s.objectStore ? (
            <>
              <dt>Object store</dt>
              <dd data-object-store={s.objectStore}>{stores[s.objectStore] ?? s.objectStore}</dd>
            </>
          ) : null}
          {s.bucketLocation ? (
            <>
              <dt>Bucket location</dt>
              <dd>{s.bucketLocation}</dd>
            </>
          ) : null}
          {s.softDeleteDays !== undefined && s.softDeleteDays !== null ? (
            <>
              <dt>Deleted objects</dt>
              <dd>Recoverable for {s.softDeleteDays} days</dd>
            </>
          ) : null}
          {s.storageMove ? (
            <>
              <dt>Bucket move</dt>
              <dd data-storage-move>{s.storageMove}</dd>
            </>
          ) : null}
          {s.broker ? (
            <>
              <dt>Broker</dt>
              <dd>{s.broker}</dd>
            </>
          ) : null}
          {s.undeclaredTopics && s.undeclaredTopics.length > 0 ? (
            <>
              <dt>Undeclared topics</dt>
              <dd>
                <ul className="ac-topics">
                  {s.undeclaredTopics.map((t) => (
                    <li key={t} data-topic={t}>
                      <code>{t}</code>
                    </li>
                  ))}
                </ul>
              </dd>
            </>
          ) : null}
          {s.topicSources && s.topicSources.length > 0 ? (
            <>
              <dt>Topic sources</dt>
              <dd>
                <div className="ac-table-wrap">
                  <table className="ac-table" data-topic-sources>
                    <thead>
                      <tr>
                        <th scope="col">Component</th>
                        <th scope="col">Topic</th>
                        <th scope="col">Group</th>
                        <th scope="col" className="ac-num">
                          Version
                        </th>
                        <th scope="col" className="ac-num">
                          Lag
                        </th>
                        <th scope="col">Failing</th>
                      </tr>
                    </thead>
                    <tbody>
                      {s.topicSources.map((t) => (
                        <tr key={t.component} data-topic-source={t.component} data-failing={t.failing ? "yes" : "no"}>
                          <td>{t.component}</td>
                          <td>
                            <code>{t.broker ? `${t.topic}@${t.broker}` : t.topic}</code>
                            {t.contract ? ` as ${t.contract}` : ""}
                          </td>
                          <td>
                            <code>{t.group}</code>
                          </td>
                          <td className="ac-num">{t.version}</td>
                          <td className="ac-num">{t.lag ?? "—"}</td>
                          <td>{t.failing ? <span className="ac-notice">{t.failing}</span> : "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </dd>
            </>
          ) : null}
          {s.topicChecks && s.topicChecks.length > 0 ? (
            <>
              <dt>Topic checks</dt>
              <dd>
                <ul className="ac-topics">
                  {s.topicChecks.map((c) => (
                    <li key={`${c.component}/${c.direction}`} data-check={c.state}>
                      {c.component} {c.direction}: {c.state}
                      {c.state === "mismatch" ? ` (${c.stated ?? "none"})` : ""}
                    </li>
                  ))}
                </ul>
              </dd>
            </>
          ) : null}
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
