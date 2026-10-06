/**
 * A project: its services, kept current while the page is open, its registry credential, its
 * project secrets — by name and entry, never a value — and the topics it declares on the broker.
 */
import { redirect, useLoaderData, type ActionFunctionArgs, type LoaderFunctionArgs, type MetaFunction } from "react-router";
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
    const [project, services, secrets, topics, page] = await Promise.all([
      ctx.client.getProject(id),
      ctx.client.listServices(id),
      ctx.client.listProjectSecrets(id),
      ctx.client.listTopics(id),
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
      case "topic-set":
        await ctx.client.declareTopic(id, text(form, "topicName"), Number(text(form, "topicPartitions")));
        return redirect(self);
      case "topic-unset":
        await ctx.client.removeTopic(id, text(form, "topicName"));
        return redirect(self);
      default:
        throw new Response(`unknown operation '${intent}'`, { status: 400 });
    }
  });
}

export default function Project() {
  const { project: p, organization: o, services: initial, secrets, topics, panels } = useLoaderData<typeof loader>();
  const { services, state } = useProjectStream(p.id, initial);
  const { shows } = useConsole();
  const renameRefusal = useRefusal("rename");
  const deleteRefusal = useRefusal("delete");
  const registryRefusal = useRefusal("registry-set");
  const secretRefusal = useRefusal("secret-set");
  const topicRefusal = useRefusal("topic-set");
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
              <Refused intent="topic-set" />
              <div>
                <Submit intent="topic-set">Declare topic</Submit>
              </div>
            </ConsoleForm>
          </details>
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
                  <th scope="col">Broker</th>
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
                    <td>
                      {t.phase ?? "Nothing reported yet"}
                      {t.detail ? ` — ${t.detail}` : ""}
                    </td>
                    <td>
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
        <Refused intent="topic-unset" />
      </section>

      <Panels kind="project" entity={p} loaded={panels} />
    </Page>
  );
}

export const ErrorBoundary = ConsoleErrorBoundary;
