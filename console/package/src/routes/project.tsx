/**
 * A project: its services, kept current while the page is open, its registry credential, its
 * project secrets — by name and entry, never a value — and the topics it declares on the broker.
 */
import { data, redirect, useActionData, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
import { ControlPlaneError } from "../client/errors.ts";
import { act, guard, pageData, projectShell, text, useConsoleContext } from "../context.ts";
import { ConsoleErrorBoundary } from "../ui/errors.tsx";
import { ConsoleForm, ConsoleLink, Field, Submit, useConsole, when } from "../ui/console.tsx";
import { Page, SectionTitle } from "../ui/shell.tsx";
import { Refused, useRefusal } from "../ui/refused.tsx";
import { Lifecycle } from "../ui/status.tsx";
import { useProjectStream } from "../ui/use-stream.ts";
import { HostActions, loadPanels, Panels } from "../extensions/render.tsx";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `${loaderData?.project.name ?? "Project"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.projectId!;
  return guard(ctx, async () => {
    const [project, services, secrets, topics, brokers, page, backups, rehearsals, restores, history, setting] = await Promise.all([
      ctx.client.getProject(id),
      ctx.client.listServices(id),
      ctx.client.listProjectSecrets(id),
      ctx.client.listTopics(id),
      ctx.client.listBrokers(id),
      pageData(ctx),
      // The database's backups are the project's, read as the person; a control plane that cannot
      // say leaves the section saying so rather than failing the page.
      ctx.client.projectStatus(id).catch(() => undefined),
      ctx.client.listRehearsals(id).catch(() => []),
      ctx.client.listRestores(id).catch(() => []),
      ctx.client.projectHistory(id).catch(() => []),
      ctx.client.projectDatabase(id).catch(() => undefined),
    ]);
    // A verified restore says what the broker holds past its moment, asked as it is read.
    const reports = await Promise.all(
      restores.filter((r) => r.phase === "Verified" || r.phase === "InUse").map((r) => ctx.client.getRestore(id, r.name).catch(() => r)),
    );
    const organization = await ctx.client.getOrganization(project.organizationId);
    const primary = { label: "Apply a descriptor", to: `projects/${encodeURIComponent(id)}/services/apply`, operation: "service.apply" as const };
    return {
      console: projectShell(page, organization, project, services, [], primary),
      project,
      organization,
      services,
      secrets,
      topics,
      brokers,
      backups,
      rehearsals,
      restores: restores.map((r) => reports.find((d) => d.name === r.name) ?? r),
      history,
      setting,
      panels: await loadPanels(ctx, "project", project),
    };
  });
}

export async function action({ request, params, context }: ActionFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.projectId!;
  const form = await request.formData();
  const intent = text(form, "intent");
  const self = ctx.href(`projects/${encodeURIComponent(id)}`);
  return act(ctx, intent, form, async () => {
    switch (intent) {
      case "rename":
        await ctx.client.renameProject(id, text(form, "name"));
        return redirect(self);
      case "restore": {
        const restore = await ctx.client.restoreProject(id, new Date(text(form, "moment")).toISOString(), text(form, "line") || undefined);
        return redirect(`${self}#restore-${restore.name}`);
      }
      case "rehearse":
        await ctx.client.rehearseProject(id, text(form, "moment") ? new Date(text(form, "moment")).toISOString() : undefined);
        return redirect(`${self}#rehearsals`);
      case "reissue":
        await ctx.client.reissueBackupCredential(id);
        return redirect(`${self}#database`);
      case "database":
        await ctx.client.setProjectDatabase(id, {
          replicas: Number(text(form, "replicas") || "0"),
          synchronous: form.get("synchronous") === "on",
          retentionDays: text(form, "retentionDays") ? Number(text(form, "retentionDays")) : undefined,
          rehearse: text(form, "rehearse") || undefined,
        });
        return redirect(`${self}#database`);
      case "switch":
        await ctx.client.switchService(id, text(form, "service"), text(form, "cluster"));
        return redirect(`${self}#database`);
      case "delete": {
        const project = await ctx.client.getProject(id);
        await ctx.client.deleteProject(id);
        return redirect(ctx.href(`organizations/${encodeURIComponent(project.organizationId)}`));
      }
      case "registry-set":
        // The password crosses once, to the control plane, and is never shown again by anything.
        await ctx.client.setRegistry(id, { server: text(form, "server"), username: text(form, "username"), password: String(form.get("password") ?? "") });
        return redirect(self);
      case "registry-clear":
        await ctx.client.clearRegistry(id);
        return redirect(self);
      case "secret-set":
        // The value crosses once, to the control plane, and is never shown again by anything.
        await ctx.client.setProjectSecret(id, text(form, "secretName"), { [text(form, "secretEntry")]: String(form.get("secretValue") ?? "") });
        return redirect(self);
      case "secret-unset":
        await ctx.client.unsetProjectSecretEntry(id, text(form, "secretName"), text(form, "secretEntry"));
        return redirect(self);
      case "topic-set": {
        // A contract is a name and a schema document, both or neither; the document is read here so
        // that a file that is not JSON is refused beside the form rather than by the platform.
        const contractName = text(form, "topicContract");
        const schemaText = String(form.get("topicSchema") ?? "").trim();
        let contract: { name: string; schema: unknown } | undefined;
        if (contractName || schemaText) {
          if (!contractName) throw new ControlPlaneError(400, "a contract is a name and a schema document: give the contract's name");
          if (!schemaText) throw new ControlPlaneError(400, "a contract is a name and a schema document: give the schema");
          let schema: unknown;
          try {
            schema = JSON.parse(schemaText);
          } catch (e) {
            throw new ControlPlaneError(400, `the schema is not valid JSON: ${e instanceof Error ? e.message : String(e)}`);
          }
          contract = { name: contractName, schema };
        }
        await ctx.client.declareTopic(id, text(form, "topicName"), {
          partitions: Number(text(form, "topicPartitions")),
          compacted: form.get("topicCompacted") === "on",
          contract,
        });
        return redirect(self);
      }
      case "topic-schema":
        // The document a member builds against, shown beside the topic rather than sent anywhere.
        return data({ intent, topic: text(form, "topicName"), schema: await ctx.client.topicSchema(id, text(form, "topicName")) });
      case "topic-unset":
        await ctx.client.removeTopic(id, text(form, "topicName"));
        return redirect(self);
      case "broker-set":
        await ctx.client.declareBroker(id, text(form, "brokerName"), { bootstrap: text(form, "brokerBootstrap"), shape: text(form, "brokerShape"), secret: text(form, "brokerSecret") });
        return redirect(self);
      case "broker-unset":
        await ctx.client.removeBroker(id, text(form, "brokerName"));
        return redirect(self);
      case "location-set":
        await ctx.client.setProjectLocation(id, text(form, "location"));
        return redirect(self);
      case "location-clear":
        await ctx.client.clearProjectLocation(id);
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

export default function Project() {
  const { project: p, organization: o, services: initial, secrets, topics, brokers, backups, rehearsals, restores, history, setting, panels } = useLoaderData<typeof loader>();
  const databaseRefusal = useRefusal("database");
  const { services, state } = useProjectStream(p.id, initial);
  const { shows } = useConsole();
  const renameRefusal = useRefusal("rename");
  const deleteRefusal = useRefusal("delete");
  const registryRefusal = useRefusal("registry-set");
  const secretRefusal = useRefusal("secret-set");
  const topicRefusal = useRefusal("topic-set");
  const brokerRefusal = useRefusal("broker-set");
  const locationRefusal = useRefusal("location-set");
  const shown = useActionData() as { intent?: string; topic?: string; schema?: unknown } | undefined;
  const shownSchema = shown && shown.intent === "topic-schema" && typeof shown.topic === "string" ? shown : undefined;
  const path = `projects/${encodeURIComponent(p.id)}`;
  const inspector = (
    <>
      <div className="ac-ops">
        <HostActions operation="service.apply" entity={p} />
      </div>
      <section className="ac-form" aria-labelledby="registry-title">
        <SectionTitle>
          <span id="registry-title">Registry</span>
        </SectionTitle>
        {shows("registry.set") ? (
          <details className="ac-more" open={registryRefusal !== undefined || undefined}>
            <summary>{p.registry ? "Replace the registry credential" : "Set a registry credential"}</summary>
            <ConsoleForm intent="registry-set" className="ac-form">
              <Field label="Registry server" name="server" required placeholder="ghcr.io" defaultValue={registryRefusal?.values.server ?? p.registry?.server} />
              <Field label="Username" name="username" required autoComplete="off" defaultValue={registryRefusal?.values.username ?? p.registry?.username} />
              <Field label="Password or token" name="password" type="password" required autoComplete="new-password" hint="Sent once to the platform, which keeps it only in the cluster. It is never shown again." />
              <Refused intent="registry-set" />
              <div>
                <Submit intent="registry-set">Save credential</Submit>
              </div>
            </ConsoleForm>
          </details>
        ) : null}
        {p.registry && shows("registry.clear") ? (
          <ConsoleForm intent="registry-clear">
            <Submit intent="registry-clear">Clear credential</Submit>
            <Refused intent="registry-clear" />
          </ConsoleForm>
        ) : null}
      </section>
      {shows("project-secret.set") ? (
        <section className="ac-form" aria-labelledby="secret-set-title">
          <SectionTitle>
            <span id="secret-set-title">Project secrets</span>
          </SectionTitle>
          <details className="ac-more" open={secretRefusal !== undefined || undefined}>
            <summary>Set a project secret entry</summary>
            <ConsoleForm intent="secret-set" className="ac-form">
              <Field label="Project secret" name="secretName" required placeholder="checkout" defaultValue={secretRefusal?.values.secretName} />
              <Field label="Entry" name="secretEntry" required placeholder="STRIPE_KEY" autoComplete="off" defaultValue={secretRefusal?.values.secretEntry} />
              <Field label="Value" name="secretValue" type="password" required autoComplete="new-password" hint="Sent once to the platform, which keeps it only in the cluster. It is never shown again." />
              <Refused intent="secret-set" />
              <div>
                <Submit intent="secret-set">Save entry</Submit>
              </div>
            </ConsoleForm>
          </details>
        </section>
      ) : null}
      {shows("project-topic.set") ? (
        <section className="ac-form" aria-labelledby="topic-set-title">
          <SectionTitle>
            <span id="topic-set-title">Topics</span>
          </SectionTitle>
          <details className="ac-more" open={topicRefusal !== undefined || undefined}>
            <summary>Declare a topic</summary>
            <ConsoleForm intent="topic-set" className="ac-form">
              <Field label="Topic" name="topicName" required placeholder="transactions" defaultValue={topicRefusal?.values.topicName} />
              <Field label="Partitions" name="topicPartitions" type="number" required defaultValue={topicRefusal?.values.topicPartitions ?? "3"} hint="A topic can be given more partitions later, never fewer." />
              <div className="ac-field">
                <label htmlFor="topicCompacted">
                  <input id="topicCompacted" name="topicCompacted" type="checkbox" defaultChecked={topicRefusal?.values.topicCompacted === "on"} /> Compacted
                </label>
                <p className="ac-hint" id="topicCompacted-hint">
                  The broker keeps the last message under each key, as a graph's delta topic needs.
                </p>
              </div>
              <Field label="Contract" name="topicContract" placeholder="order.v1" autoComplete="off" defaultValue={topicRefusal?.values.topicContract} hint="What the topic carries, which every side must state. Optional, with its schema." />
              <div className="ac-field">
                <label htmlFor="topicSchema">Schema</label>
                <textarea id="topicSchema" name="topicSchema" spellCheck={false} placeholder='{"type": "object"}' defaultValue={topicRefusal?.values.topicSchema} aria-describedby="topicSchema-hint" />
                <p className="ac-hint" id="topicSchema-hint">
                  The contract's JSON Schema document, held by the project for members to build against.
                </p>
              </div>
              <Refused intent="topic-set" />
              <div>
                <Submit intent="topic-set">Declare topic</Submit>
              </div>
            </ConsoleForm>
          </details>
        </section>
      ) : null}
      {shows("project-broker.set") ? (
        <section className="ac-form" aria-labelledby="broker-set-title">
          <SectionTitle>
            <span id="broker-set-title">Brokers</span>
          </SectionTitle>
          <details className="ac-more" open={brokerRefusal !== undefined || undefined}>
            <summary>Declare a broker</summary>
            <ConsoleForm intent="broker-set" className="ac-form">
              <Field label="Broker" name="brokerName" required placeholder="legacy" defaultValue={brokerRefusal?.values.brokerName} hint="The name a component gives for a topic that lives there." />
              <Field label="Bootstrap" name="brokerBootstrap" required placeholder="kafka.legacy:9094" autoComplete="off" defaultValue={brokerRefusal?.values.brokerBootstrap} hint="host:port, several separated by commas." />
              <div className="ac-field">
                <label htmlFor="brokerShape">Shape</label>
                <select id="brokerShape" name="brokerShape" defaultValue={brokerRefusal?.values.brokerShape ?? "sasl"} aria-describedby="brokerShape-hint">
                  <option value="sasl">sasl</option>
                  <option value="certificate">certificate</option>
                </select>
                <p className="ac-hint" id="brokerShape-hint">
                  sasl: the secret holds ca.crt, username and password. certificate: ca.crt, tls.crt and tls.key. Both over TLS.
                </p>
              </div>
              <Field label="Credential secret" name="brokerSecret" required placeholder="legacy-credential" autoComplete="off" list="broker-secrets" defaultValue={brokerRefusal?.values.brokerSecret} hint="The project secret holding the credential, mounted for the platform's program alone." />
              <datalist id="broker-secrets">
                {secrets.map((s) => (
                  <option key={s.name} value={s.name} />
                ))}
              </datalist>
              <Refused intent="broker-set" />
              <div>
                <Submit intent="broker-set">Declare broker</Submit>
              </div>
            </ConsoleForm>
          </details>
        </section>
      ) : null}
      {shows("project-location.set") || shows("project-location.clear") ? (
        <section className="ac-form" aria-labelledby="location-title">
          <SectionTitle>
            <span id="location-title">Bucket location</span>
          </SectionTitle>
          {shows("project-location.set") ? (
            <details className="ac-more" open={locationRefusal !== undefined || undefined}>
              <summary>Choose where new buckets are made</summary>
              <ConsoleForm intent="location-set" className="ac-form">
                <Field
                  label="Location"
                  name="location"
                  required
                  placeholder="europe-west6"
                  autoComplete="off"
                  defaultValue={locationRefusal?.values.location}
                  hint="Where the project's new buckets in Google Cloud Storage are made, in the installation's own words. A bucket's location is fixed when it is made, so this moves no bucket."
                />
                <Refused intent="location-set" />
                <div>
                  <Submit intent="location-set">Set location</Submit>
                </div>
              </ConsoleForm>
            </details>
          ) : null}
          {shows("project-location.clear") ? (
            <ConsoleForm intent="location-clear">
              <Submit intent="location-clear">Use the installation's location</Submit>
              <Refused intent="location-clear" />
            </ConsoleForm>
          ) : null}
        </section>
      ) : null}
      {shows("project.rename") || shows("project.delete") ? (
        <details className="ac-more" open={renameRefusal !== undefined || deleteRefusal !== undefined || undefined}>
          <summary>Rename or delete</summary>
          {shows("project.rename") ? (
            <ConsoleForm intent="rename" className="ac-inline">
              <Field label="New name" name="name" required defaultValue={renameRefusal?.values.name ?? p.name} />
              <Submit intent="rename">Rename</Submit>
            </ConsoleForm>
          ) : null}
          <Refused intent="rename" />
          {shows("project.delete") ? (
            <div className="ac-danger-zone">
              <p>Deleting {p.name} is permanent, and its id can never be used again. It must have no services.</p>
              <ConsoleForm intent="delete">
                <Submit intent="delete" danger>
                  Delete project
                </Submit>
              </ConsoleForm>
              <Refused intent="delete" />
            </div>
          ) : null}
        </details>
      ) : null}
    </>
  );
  return (
    <Page inspector={inspector}>
      <h1>{p.name}</h1>
      <dl className="ac-card ac-facts">
        <dt>Id</dt>
        <dd>{p.id}</dd>
        <dt>Organization</dt>
        <dd>{o.name}</dd>
        <dt>Registry</dt>
        <dd>
          {p.registry ? (
            <>
              Private images are pulled from <strong>{p.registry.server}</strong> as <strong>{p.registry.username}</strong>, set {when(p.registry.setAt)}
              {p.registry.setBy ? ` by ${p.registry.setBy}` : ""}.
            </>
          ) : (
            "Services in this project pull only public images. Set a credential to pull private ones."
          )}
        </dd>
      </dl>

      <section className="ac-card" aria-labelledby="services">
        <h2 id="services">Services</h2>
        {state === "live" ? <p className="ac-live">Updating as the platform reports</p> : null}
        {services.length === 0 ? (
          <p className="ac-empty">No services yet. Apply a descriptor to deploy one.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="services">
              <thead>
                <tr>
                  <th scope="col">Service</th>
                  <th scope="col">State</th>
                  <th scope="col" className="ac-num">
                    Instances
                  </th>
                  <th scope="col">Image</th>
                  <th scope="col" className="ac-num">
                    Generation
                  </th>
                  <th scope="col">Address</th>
                </tr>
              </thead>
              <tbody>
                {services.map((s) => (
                  <tr key={s.name} data-service={s.name}>
                    <td>
                      <ConsoleLink to={`${path}/services/${encodeURIComponent(s.name)}`}>{s.name}</ConsoleLink>
                    </td>
                    <td>
                      <Lifecycle lifecycle={s.lifecycle} confirmed={s.confirmed} />
                    </td>
                    <td className="ac-num">
                      {s.readyInstances} of {s.desiredInstances}
                    </td>
                    <td>{s.image}</td>
                    <td className="ac-num">{s.generation}</td>
                    <td>{s.hostname ? <a href={s.hostname}>{s.hostname.replace(/^https:\/\//, "")}</a> : s.exposed ? "Exposed, no address yet" : "Not exposed"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="ac-card" aria-labelledby="secrets">
        <h2 id="secrets">Project secrets</h2>
        {secrets.length === 0 ? (
          <p className="ac-empty">No project secrets. A descriptor's variable can take an entry of one by secretKeyRef.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="secrets">
              <thead>
                <tr>
                  <th scope="col">Secret</th>
                  <th scope="col">Entry</th>
                  <th scope="col">Set</th>
                  <th scope="col">
                    <span className="ac-visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {secrets.flatMap((s) =>
                  s.entries.map((entry) => (
                    <tr key={`${s.name}/${entry}`} data-secret={s.name} data-entry={entry}>
                      <td>{s.name}</td>
                      <td>{entry}</td>
                      <td>
                        {when(s.setAt)}
                        {s.setBy ? ` by ${s.setBy}` : ""}
                      </td>
                      <td>
                        {shows("project-secret.unset") ? (
                          <ConsoleForm intent="secret-unset" className="ac-inline">
                            <input type="hidden" name="secretName" value={s.name} />
                            <input type="hidden" name="secretEntry" value={entry} />
                            <Submit intent="secret-unset">{`Remove ${entry}`}</Submit>
                          </ConsoleForm>
                        ) : null}
                      </td>
                    </tr>
                  )),
                )}
              </tbody>
            </table>
          </div>
        )}
        <Refused intent="secret-unset" />
      </section>

      <section className="ac-card" aria-labelledby="topics">
        <h2 id="topics">Topics</h2>
        {topics.length === 0 ? (
          <p className="ac-empty">No topics declared. Every service of the project uses a declared topic by its name.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="topics">
              <thead>
                <tr>
                  <th scope="col">Topic</th>
                  <th scope="col" className="ac-num">
                    Partitions
                  </th>
                  <th scope="col">Compacted</th>
                  <th scope="col">Contract</th>
                  <th scope="col">Broker</th>
                  <th scope="col">Checks</th>
                  <th scope="col">
                    <span className="ac-visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {topics.map((t) => (
                  <tr key={t.name} data-topic={t.name}>
                    <td>
                      <code>{t.name}</code>
                    </td>
                    <td className="ac-num">{t.partitions}</td>
                    <td data-compacted={t.compacted ? "yes" : "no"}>{t.compacted ? "yes" : "no"}</td>
                    <td>
                      {t.contract ? (
                        <>
                          <code>{t.contract.name}</code> <span className="ac-hint">{t.contract.fingerprint.slice(0, 15)}</span>
                        </>
                      ) : (
                        "none"
                      )}
                    </td>
                    <td>
                      {t.phase ?? "Nothing reported yet"}
                      {t.detail ? ` — ${t.detail}` : ""}
                    </td>
                    <td>
                      {t.checks.length === 0 ? (
                        t.contract ? "no side seen yet" : "—"
                      ) : (
                        <ul className="ac-topics">
                          {t.checks.map((c) => (
                            <li key={`${c.service}/${c.component}/${c.direction}`} data-check={c.state}>
                              {c.service} {c.direction}: {c.state}
                              {c.state === "mismatch" ? ` (${c.stated ?? "none"})` : ""}
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td>
                      {t.contract ? (
                        <ConsoleForm intent="topic-schema" className="ac-inline">
                          <input type="hidden" name="topicName" value={t.name} />
                          <Submit intent="topic-schema">{`Show schema of ${t.name}`}</Submit>
                        </ConsoleForm>
                      ) : null}
                      {shows("project-topic.unset") ? (
                        <ConsoleForm intent="topic-unset" className="ac-inline">
                          <input type="hidden" name="topicName" value={t.name} />
                          <Submit intent="topic-unset">{`Stop declaring ${t.name}`}</Submit>
                        </ConsoleForm>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {shownSchema ? (
          <div className="ac-card" data-schema={shownSchema.topic}>
            <h3>
              Schema of <code>{shownSchema.topic}</code>
            </h3>
            <pre>{JSON.stringify(shownSchema.schema, null, 2)}</pre>
          </div>
        ) : null}
        <Refused intent="topic-unset" />
        <Refused intent="topic-schema" />
      </section>

      <section className="ac-card" aria-labelledby="brokers">
        <h2 id="brokers">Brokers</h2>
        {brokers.length === 0 ? (
          <p className="ac-empty">No brokers declared beside the installation's. A component may name one for a topic that lives elsewhere.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="brokers">
              <thead>
                <tr>
                  <th scope="col">Broker</th>
                  <th scope="col">Bootstrap</th>
                  <th scope="col">Shape</th>
                  <th scope="col">Secret</th>
                  <th scope="col">Declared</th>
                  <th scope="col">
                    <span className="ac-visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {brokers.map((b) => (
                  <tr key={b.name} data-broker={b.name}>
                    <td>
                      <code>{b.name}</code>
                    </td>
                    <td>{b.bootstrap}</td>
                    <td>{b.shape}</td>
                    <td>{b.secret}</td>
                    <td>{b.declaredAt ? when(b.declaredAt) : "—"}</td>
                    <td>
                      {shows("project-broker.unset") ? (
                        <ConsoleForm intent="broker-unset" className="ac-inline">
                          <input type="hidden" name="brokerName" value={b.name} />
                          <Submit intent="broker-unset">{`Stop declaring ${b.name}`}</Submit>
                        </ConsoleForm>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Refused intent="broker-unset" />
      </section>

      <section className="ac-card" aria-labelledby="database-title" id="database">
        <h2 id="database-title">Database</h2>
        {!backups ? (
          <p className="ac-empty">The control plane could not say how the project's database is backed up.</p>
        ) : (
          <>
            <p data-backed-up={backups.backedUp ? "yes" : "no"}>
              {backups.backedUp ? "Backed up." : "Not backed up."}
              {backups.detail ? ` ${backups.detail}` : ""}
            </p>
            {backups.database ? (
              <p>
                {backups.database.readyInstances} of {backups.database.instances} instances ready
                {backups.database.primary ? `, primary ${backups.database.primary}` : ""}
                {backups.database.synchronous ? ", synchronous" : ""}
                {backups.database.writesWaitingOn ? ` — ${backups.database.writesWaitingOn}` : ""}
              </p>
            ) : null}
            {backups.lines.length > 0 ? (
              <div className="ac-table-wrap">
                <table className="ac-table" aria-label="Lines of history">
                  <thead>
                    <tr>
                      <th scope="col">Line</th>
                      <th scope="col">Backups</th>
                      <th scope="col">Last base backup</th>
                      <th scope="col">Restorable from</th>
                      <th scope="col">To</th>
                    </tr>
                  </thead>
                  <tbody>
                    {backups.lines.map((l) => (
                      <tr key={l.line} data-line={l.line}>
                        <td>
                          <code>{l.line}</code>
                        </td>
                        <td>
                          {l.phase}
                          {l.failing ? ` — ${l.failing}` : ""}
                        </td>
                        <td>{l.lastBaseBackup ? when(l.lastBaseBackup) : "—"}</td>
                        <td>{l.firstRestorable ? when(l.firstRestorable) : "—"}</td>
                        <td>{l.lastRestorable ? when(l.lastRestorable) : "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : null}
            {backups.clusters.length > 0 ? (
              <ul className="ac-topics" aria-label="Database clusters">
                {backups.clusters.map((c) => (
                  <li key={c.name} data-cluster={c.name} data-phase={c.phase}>
                    <code>{c.name}</code> ({c.phase}
                    {c.leftAt ? `, left ${when(c.leftAt)}` : ""}
                    {c.since ? `, since ${when(c.since)}` : ""}): {c.services.length === 0 ? "no service" : c.services.join(", ")}
                  </li>
                ))}
              </ul>
            ) : null}
            {restores.map((r) => (
              <div key={r.name} className="ac-card" id={`restore-${r.name}`} data-restore={r.name} data-phase={r.phase}>
                <h3>
                  Restore <code>{r.name}</code> of {r.line} at {when(r.moment)}: {r.phase}
                </h3>
                {r.detail ? <p>{r.detail}</p> : null}
                {r.services.length > 0 ? (
                  <ul className="ac-topics">
                    {r.services.map((v) => (
                      <li key={v.name} data-verified={v.name}>
                        {v.name}: {v.present ? `${v.journalRows} journal rows, highest sequence ${v.highestSequence}` : "no database at the moment"}
                        {v.changedSecrets.length > 0 ? `; secrets changed since: ${v.changedSecrets.join(", ")}` : ""}
                        {v.present && (r.phase === "Verified" || r.phase === "InUse") && shows("service.switch") ? (
                          <ConsoleForm intent="switch" className="ac-inline">
                            <input type="hidden" name="service" value={v.name} />
                            <input type="hidden" name="cluster" value={r.name} />
                            <Submit intent="switch">{`Switch ${v.name} to ${r.name}`}</Submit>
                          </ConsoleForm>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                ) : null}
                {r.broker.length > 0 ? (
                  <ul className="ac-topics" aria-label={`What ${r.name} cannot take back`}>
                    {r.broker.map((d) => (
                      <li key={`${d.topic}/${d.group ?? ""}`} data-diverged={d.topic}>
                        <code>{d.topic}</code>
                        {d.group ? ` read by ${d.group}: ${d.read ?? 0} of` : ":"} {d.after} messages newer than the moment
                      </li>
                    ))}
                  </ul>
                ) : null}
                {r.note ? <p className="ac-hint">{r.note}</p> : null}
              </div>
            ))}
            <Refused intent="switch" />
          </>
        )}
        {setting ? (
          <p data-setting={`${setting.replicas}`}>
            Asks for {setting.replicas === 0 ? "a primary alone" : `${setting.replicas} replica${setting.replicas === 1 ? "" : "s"}`}
            {setting.synchronous ? ", synchronous" : ""}
            {setting.retentionDays ? `, backups kept ${setting.retentionDays} days` : ""}
            {setting.rehearse ? `, rehearsed ${setting.rehearse}` : ""}.
          </p>
        ) : null}
        {shows("project.database") ? (
          <details className="ac-more" open={databaseRefusal !== undefined || undefined}>
            <summary>Replicas, retention and rehearsals</summary>
            <ConsoleForm intent="database" className="ac-form">
              <Field label="Replicas" name="replicas" type="number" defaultValue={databaseRefusal?.values.replicas ?? String(setting?.replicas ?? 0)} hint="Beside the primary, 0 to 4; one takes its place if it is lost." />
              <div className="ac-field">
                <label htmlFor="synchronous">
                  <input id="synchronous" name="synchronous" type="checkbox" defaultChecked={setting?.synchronous} /> Synchronous
                </label>
                <p className="ac-hint">Every write waits until a replica holds it, so none is lost with the primary.</p>
              </div>
              <Field label="Keep backups for (days)" name="retentionDays" type="number" defaultValue={databaseRefusal?.values.retentionDays ?? (setting?.retentionDays ? String(setting.retentionDays) : "")} />
              <div className="ac-field">
                <label htmlFor="rehearse">Rehearse a restore</label>
                <select id="rehearse" name="rehearse" defaultValue={setting?.rehearse ?? ""}>
                  <option value="">never</option>
                  <option value="daily">daily</option>
                  <option value="weekly">weekly</option>
                </select>
              </div>
              <Refused intent="database" />
              <div>
                <Submit intent="database">Save</Submit>
              </div>
            </ConsoleForm>
          </details>
        ) : null}
        {shows("project.backup-credential") ? (
          <ConsoleForm intent="reissue" className="ac-inline">
            <Submit intent="reissue">Issue the backup credential again</Submit>
            <Refused intent="reissue" />
          </ConsoleForm>
        ) : null}
        {shows("project.restore") ? (
          <details className="ac-more">
            <summary>Restore to a moment</summary>
            <ConsoleForm intent="restore" className="ac-form">
              <Field label="Moment" name="moment" type="datetime-local" required hint="A new cluster beside the current one; nothing in the current database changes. Owners only." />
              <Field label="Line" name="line" placeholder="ankka-db" hint="Only when the project's services are on more than one cluster." />
              <Refused intent="restore" />
              <div>
                <Submit intent="restore">Restore</Submit>
              </div>
            </ConsoleForm>
          </details>
        ) : null}
      </section>

      <section className="ac-card" aria-labelledby="rehearsals-title" id="rehearsals">
        <h2 id="rehearsals-title">Rehearsals</h2>
        {rehearsals.length === 0 ? (
          <p className="ac-empty">No rehearsals yet. A rehearsal restores into a namespace of its own, checks it, times it and removes it.</p>
        ) : (
          <ul className="ac-topics">
            {rehearsals.map((r) => (
              <li key={r.name} data-rehearsal={r.name} data-outcome={r.outcome}>
                <code>{r.name}</code> to {when(r.moment)}: {r.outcome}
                {r.elapsedSeconds !== undefined ? `, in ${r.elapsedSeconds}s` : ""}
                {r.requestedBy ? `, asked by ${r.requestedBy}` : ", on the schedule"}
                {r.detail ? ` — ${r.detail}` : ""}
              </li>
            ))}
          </ul>
        )}
        {shows("project.rehearse") ? (
          <ConsoleForm intent="rehearse" className="ac-inline">
            <Submit intent="rehearse">Rehearse a restore</Submit>
          </ConsoleForm>
        ) : null}
        <Refused intent="rehearse" />
      </section>

      <section className="ac-card" aria-labelledby="database-history">
        <h2 id="database-history">Database history</h2>
        {history.length === 0 ? (
          <p className="ac-empty">Nothing has been done to the project's database yet.</p>
        ) : (
          <ul className="ac-topics">
            {history.map((h, i) => (
              <li key={i} data-history={h.kind}>
                {h.kind}
                {h.by ? ` by ${h.by}` : ""}
                {h.at ? `, ${when(h.at)}` : ""}
                {h.detail ? `: ${h.detail}` : ""}
              </li>
            ))}
          </ul>
        )}
      </section>

      <Panels kind="project" entity={p} loaded={panels} />
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
