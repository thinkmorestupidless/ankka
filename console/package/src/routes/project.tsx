/**
 * A project: its services, kept current while the page is open, its registry credential, its
 * project secrets — by name and entry, never a value — the topics it declares on the broker, the
 * grants it makes to other projects' services and to machines, and what other projects grant it.
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
import { targetText } from "../ui/grants.ts";
import type { GrantTarget } from "../client/schemas.ts";
import { HostActions, loadPanels, Panels } from "../extensions/render.tsx";

export const meta: MetaFunction<typeof loader> = ({ loaderData }) => [{ title: `${loaderData?.project.name ?? "Project"} · ankka` }];

export async function loader({ params, context }: LoaderFunctionArgs) {
  const ctx = useConsoleContext(context);
  const id = params.projectId!;
  return guard(ctx, async () => {
    const [project, services, secrets, topics, brokers, grants, received, page] = await Promise.all([
      ctx.client.getProject(id),
      ctx.client.listServices(id),
      ctx.client.listProjectSecrets(id),
      ctx.client.listTopics(id),
      ctx.client.listBrokers(id),
      ctx.client.grants(id),
      ctx.client.receivedGrants(id),
      pageData(ctx),
    ]);
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
      grants,
      received,
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
      case "grant-make":
        await ctx.client.makeGrant(id, { grantee: text(form, "grantee"), target: grantTarget(form) });
        return redirect(self);
      case "grant-end":
        // A pending grant is withdrawn and an accepted one revoked: the control plane decides which.
        await ctx.client.endGrant(id, text(form, "grantId"));
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

/**
 * The target the form describes, with only its kind's fields: the control plane refuses a field
 * another kind would take ("a topic target has no service"), so an empty or foreign one is left out.
 */
function grantTarget(form: FormData): GrantTarget {
  const kind = text(form, "grantKind");
  const given = (name: string) => text(form, name) || undefined;
  // Absent fields are left out of the request body, as `JSON.stringify` drops `undefined`.
  const none = { service: undefined, method: undefined, path: undefined, topic: undefined, right: undefined, decrypt: false };
  switch (kind) {
    case "route":
      return { ...none, kind, service: given("grantService"), method: given("grantMethod")?.toUpperCase(), path: given("grantPath") };
    case "method":
      return { ...none, kind, service: given("grantService"), method: given("grantMethod") };
    case "topic":
      return { ...none, kind, topic: given("grantTopic"), right: given("grantRight"), decrypt: form.get("grantDecrypt") === "on" };
    default:
      return { ...none, kind };
  }
}

/** A live grant is ended by withdrawing it while it waits for an answer, by revoking it once accepted. */
const endVerb = (state: string) => (state === "pending" ? "Withdraw" : "Revoke");

export default function Project() {
  const { project: p, organization: o, services: initial, secrets, topics, brokers, grants, received, panels } = useLoaderData<typeof loader>();
  const { services, state } = useProjectStream(p.id, initial);
  const { shows } = useConsole();
  const renameRefusal = useRefusal("rename");
  const deleteRefusal = useRefusal("delete");
  const registryRefusal = useRefusal("registry-set");
  const secretRefusal = useRefusal("secret-set");
  const topicRefusal = useRefusal("topic-set");
  const brokerRefusal = useRefusal("broker-set");
  const grantRefusal = useRefusal("grant-make");
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
      {shows("grant.make") ? (
        <section className="ac-form" aria-labelledby="grant-make-title">
          <SectionTitle>
            <span id="grant-make-title">Grants</span>
          </SectionTitle>
          <details className="ac-more" open={grantRefusal !== undefined || undefined}>
            <summary>Make a grant</summary>
            <ConsoleForm intent="grant-make" className="ac-form">
              <Field
                label="Grantee"
                name="grantee"
                required
                placeholder="service:payments/merchant"
                autoComplete="off"
                defaultValue={grantRefusal?.values.grantee}
                hint="service:<project>/<service>, or machine:<organization>/<name>. Another organization's grantee must accept first."
              />
              <div className="ac-field">
                <label htmlFor="grantKind">Grant kind</label>
                <select id="grantKind" name="grantKind" defaultValue={grantRefusal?.values.grantKind ?? "route"} aria-describedby="grantKind-hint">
                  <option value="route">route</option>
                  <option value="method">method</option>
                  <option value="topic">topic</option>
                  <option value="erasure">erasure</option>
                </select>
                <p className="ac-hint" id="grantKind-hint">
                  route: a service, an HTTP method and a path. method: a service and a gRPC method. topic: a declared topic and a right. erasure: nothing more.
                </p>
              </div>
              <Field label="Granted service" name="grantService" placeholder="wallet" autoComplete="off" defaultValue={grantRefusal?.values.grantService} />
              <Field
                label="Granted method"
                name="grantMethod"
                placeholder="POST"
                autoComplete="off"
                defaultValue={grantRefusal?.values.grantMethod}
                hint="An HTTP method for a route; Service/Method for a gRPC method."
              />
              <Field label="Granted path" name="grantPath" placeholder="/v1/wallets/{player}" autoComplete="off" defaultValue={grantRefusal?.values.grantPath} />
              <Field label="Granted topic" name="grantTopic" list="grant-topics" autoComplete="off" defaultValue={grantRefusal?.values.grantTopic} />
              <datalist id="grant-topics">
                {topics.map((t) => (
                  <option key={t.name} value={t.name} />
                ))}
              </datalist>
              <div className="ac-field">
                <label htmlFor="grantRight">Granted right</label>
                <select id="grantRight" name="grantRight" defaultValue={grantRefusal?.values.grantRight ?? "consume"}>
                  <option value="consume">consume</option>
                  <option value="produce">produce</option>
                </select>
              </div>
              <div className="ac-field">
                <label htmlFor="grantDecrypt">
                  <input id="grantDecrypt" name="grantDecrypt" type="checkbox" defaultChecked={grantRefusal?.values.grantDecrypt === "on"} /> Allow decryption
                </label>
                <p className="ac-hint" id="grantDecrypt-hint">
                  Only with consume: the grantee may read what the topic's producers encrypted.
                </p>
              </div>
              <Refused intent="grant-make" />
              <div>
                <Submit intent="grant-make">Make grant</Submit>
              </div>
            </ConsoleForm>
          </details>
          <div className="ac-ops">
            <HostActions operation="grant.make" entity={p} />
          </div>
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

      <section className="ac-card" aria-labelledby="grants">
        <h2 id="grants">Grants</h2>
        {grants.length === 0 ? (
          <p className="ac-empty">No grants. A grant lets a service of another project, or a machine, reach one route, method or topic of this project.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="grants">
              <thead>
                <tr>
                  <th scope="col">Grantee</th>
                  <th scope="col">Target</th>
                  <th scope="col">State</th>
                  <th scope="col">Effect</th>
                  <th scope="col">Granted</th>
                  <th scope="col">
                    <span className="ac-visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {grants.map((g) => (
                  <tr key={g.id} data-grant={g.id} data-state={g.state}>
                    <td>
                      <code>{g.grantee}</code>
                    </td>
                    <td>{targetText(g.target)}</td>
                    <td>{g.state}</td>
                    <td data-effect>{g.effect}</td>
                    <td>
                      {g.granted.at ? when(g.granted.at) : "—"}
                      {g.granted.by ? ` by ${g.granted.by}` : ""}
                      {g.ended ? (
                        <span className="ac-hint">
                          {" "}
                          · ended {g.ended.at ? when(g.ended.at) : ""}
                          {g.ended.by ? ` by ${g.ended.by}` : ""}
                        </span>
                      ) : null}
                    </td>
                    <td>
                      {shows("grant.end") && (g.state === "pending" || g.state === "accepted") ? (
                        <ConsoleForm intent="grant-end" className="ac-inline">
                          <input type="hidden" name="grantId" value={g.id} />
                          <Submit intent="grant-end" label={`${endVerb(g.state)} the grant to ${g.grantee} of ${targetText(g.target)}`}>
                            {endVerb(g.state)}
                          </Submit>
                        </ConsoleForm>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Refused intent="grant-end" />
      </section>

      <section className="ac-card" aria-labelledby="received">
        <h2 id="received">Received</h2>
        {received.length === 0 ? (
          <p className="ac-empty">No other project grants this project's services anything.</p>
        ) : (
          <div className="ac-table-wrap">
            <table className="ac-table" aria-describedby="received">
              <thead>
                <tr>
                  <th scope="col">From</th>
                  <th scope="col">Grantee</th>
                  <th scope="col">Target</th>
                  <th scope="col">State</th>
                  <th scope="col">Changes</th>
                </tr>
              </thead>
              <tbody>
                {received.map((r) => (
                  <tr key={r.id} data-received={r.id} data-state={r.state}>
                    <td>
                      {r.grantingProject} <span className="ac-hint">of {r.grantingOrganization}</span>
                    </td>
                    <td>
                      <code>{r.grantee}</code>
                    </td>
                    <td>
                      {targetText(r.target)}
                      {r.topic ? (
                        <span className="ac-hint">
                          {" "}
                          · {r.topic.partitions} partitions{r.topic.compacted ? ", compacted" : ""}
                          {r.topic.retention ? `, kept ${r.topic.retention}` : ""}
                        </span>
                      ) : null}
                    </td>
                    <td>{r.state}</td>
                    <td>
                      <ul className="ac-topics">
                        {r.changes.map((c, i) => (
                          <li key={`${c.change}-${i}`}>
                            {c.change}
                            {c.at ? ` ${when(c.at)}` : ""}
                            {c.by ? ` by ${c.by}` : ""}
                          </li>
                        ))}
                      </ul>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <Panels kind="project" entity={p} loaded={panels} />
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
